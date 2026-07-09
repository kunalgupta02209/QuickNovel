import asyncio
import logging
import json
import re
import time

import litellm

from . import budget, metrics, storage
from .config import config
from .prompts import prompts

log = logging.getLogger("fixer")

# Drop provider-unsupported sampling params instead of erroring — e.g. Ollama rejects
# presence_penalty/frequency_penalty, while OpenAI-style providers accept them.
litellm.drop_params = True

# Serialize LOCAL model calls. Concurrent requests each open a context window; on a small GPU the
# model + multiple KV caches blow the VRAM budget and every request thrashes to a crawl. One
# call at a time keeps each request fast; extra callers simply queue.
_gpu_sem = asyncio.Semaphore(1)
# Cloud calls are network-bound — allow a few in flight.
_cloud_sem = asyncio.Semaphore(4)
# Circuit breaker: after a cloud failure, start straight on the fallback until this timestamp.
_cloud_down_until = 0.0


def gpu_busy() -> bool:
    """True while an LLM chunk is generating (dashboard indicator)."""
    return _gpu_sem.locked()


def cloud_status() -> dict:
    """Dashboard block: today's cloud spend/calls + circuit-breaker state."""
    now = time.time()
    return {
        "today": budget.today(),
        "cap_usd": config.cloud_budget["daily_usd_cap"],
        "over_cap": budget.over_cap(config.cloud_budget["daily_usd_cap"]),
        "down_until": _cloud_down_until if _cloud_down_until > now else None,
        "key_present": bool(config.api_key_for(config.task_model("grammar_fix")["model"])) or any(
            config.api_key_for(m.get("id")) for m in config.models if m.get("kind") == "cloud"
        ),
    }


def _chunk_cost(entry: dict, prompt_tokens: int, completion_tokens: int) -> float:
    return (
        prompt_tokens * float(entry.get("input_usd_per_mtok", 0) or 0)
        + completion_tokens * float(entry.get("output_usd_per_mtok", 0) or 0)
    ) / 1e6


_SENTENCE = re.compile(r"(?<=[.!?…”\"'])\s+")


def _split_sentences(paragraph: str, max_chars: int) -> list[str]:
    """Pack a too-long paragraph into <= max_chars pieces on SENTENCE boundaries (never mid-sentence,
    unless a single sentence itself exceeds max_chars — then hard-split as a last resort)."""
    out: list[str] = []
    buf = ""
    for s in _SENTENCE.split(paragraph):
        s = s.strip()
        if not s:
            continue
        if len(s) > max_chars:
            if buf:
                out.append(buf.strip()); buf = ""
            for i in range(0, len(s), max_chars):
                out.append(s[i : i + max_chars].strip())
        elif len(buf) + len(s) + 1 > max_chars:
            out.append(buf.strip()); buf = s + " "
        else:
            buf += s + " "
    if buf.strip():
        out.append(buf.strip())
    return out


def split_chunks(text: str, max_chars: int) -> list[str]:
    """Pack whole paragraphs into <= max_chars chunks; oversized paragraphs are split on sentence
    boundaries so every chunk the model rewrites ends cleanly (no mid-sentence cuts). Keeping chunks
    below the context budget (with room to generate) is what keeps generation fast + coherent."""
    paras = re.split(r"\n\s*\n", text)
    chunks: list[str] = []
    cur = ""
    for p in paras:
        p = p.strip()
        if not p:
            continue
        if len(p) > max_chars:
            if cur:
                chunks.append(cur.strip()); cur = ""
            chunks.extend(_split_sentences(p, max_chars))
        elif len(cur) + len(p) + 2 > max_chars:
            chunks.append(cur.strip())
            cur = p + "\n\n"
        else:
            cur += p + "\n\n"
    if cur.strip():
        chunks.append(cur.strip())
    return [c for c in chunks if c.strip()]


def _build_messages(system: str, chunk: str, previous_chapters: str, character_memory: str) -> list[dict]:
    user = (
        "RECENT STORY SO FAR (context only, do NOT copy into your output):\n"
        f"{previous_chapters or '(none)'}\n\n"
        "CHARACTER MEMORY (context only — names, pronouns, relationships to keep consistent):\n"
        f"{character_memory or '(none)'}\n\n"
        "CHAPTER TO REWRITE:\n"
        f"{chunk}"
    )
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


_PREAMBLE = re.compile(
    r"^\s*[*#>\-]*\s*(rewritten|revised|corrected|edited|here('?s| is)|the following|sure[,!.])\b.*$",
    re.IGNORECASE,
)


_TRAILER = re.compile(
    r"(please (provide|let|share|give|note)|let me know|feel free|i'?m (ready|happy|here)|i hope (this|that)|"
    r"additional (info|context|information|details)|would you like|if you (have|need|want|'?d)|"
    r"any (other |additional )?(details|info|questions)|happy to (help|assist)|"
    r"(here|this) (is |'?s )?(the |a )?(rewrite|rewritten|revised|translation)|machine translation|"
    r"it'?s important to|keep in mind|as an ai|i'?ve (rewritten|fixed|corrected|revised|made)|"
    r"grammatical error|text-to-speech|readability|^note:|^\*\*note|"
    r"[\U0001F300-\U0001FAFF☀-➿])",
    re.IGNORECASE,
)


def _clean(out: str) -> str:
    out = (out or "").strip()
    if out.startswith("```"):
        out = out.split("\n", 1)[-1].rsplit("```", 1)[0].strip()
    # Small models sometimes prepend a label line like "**Rewritten Chapter**" or
    # "Here is the rewritten text:" — drop a short leading preamble line, keep the prose.
    parts = out.split("\n", 1)
    if len(parts) == 2 and len(parts[0]) < 70 and _PREAMBLE.match(parts[0]):
        out = parts[1].strip()
    # ...and sometimes append a chat trailer ("Please provide me with... 😊"). Drop trailing
    # blocks that read as conversational meta (short + matching the trailer patterns / an emoji).
    blocks = re.split(r"\n{2,}", out)
    while len(blocks) > 1 and len(blocks[-1]) < 500 and _TRAILER.search(blocks[-1]):
        blocks.pop()
    out = "\n\n".join(blocks)
    return out.strip()


# ---- QN-Cue v1 (performance scripts) --------------------------------------------------------
# Closed vocabularies; anything outside them is stripped at parse time so jobs never fail on
# model creativity. Stored form is span JSON (one span per paragraph in this P2 stub; P4 adds
# real speaker spans from the character map).
EVENT_TAGS = {"laugh", "chuckle", "sigh", "gasp", "breath", "groan", "yawn", "cough", "sniffle", "cry", "pant"}
DELIVERY_TAGS = {"neutral", "soft", "whisper", "excited", "angry", "sad", "fearful", "tired", "shout"}
_MARKER = re.compile(r"\[\[P(\d+)\]\]")
_TAG = re.compile(r"<([a-z]+)>\s*")

SCRIPT_TASKS = {"grammar": "grammar_fix", "performance": "performance_script"}


def split_paragraphs(text: str) -> list[str]:
    return [p.strip() for p in re.split(r"\n\s*\n", text) if p.strip()]


def _mark_paragraphs(paragraphs: list[str]) -> str:
    return "\n\n".join(f"[[P{i}]]\n{p}" for i, p in enumerate(paragraphs))


def _parse_perf_tags(llm_out: str) -> dict[int, dict]:
    """Marker-anchored parse of inline-tag LLM output (LOCAL models) -> {i: paragraph}."""
    parts: dict[int, str] = {}
    matches = list(_MARKER.finditer(llm_out))
    for j, m in enumerate(matches):
        end = matches[j + 1].start() if j + 1 < len(matches) else len(llm_out)
        parts[int(m.group(1))] = llm_out[m.end():end].strip()

    out: dict[int, dict] = {}
    for i, t in parts.items():
        if not t:
            continue
        delivery = "neutral"
        events: list[dict] = []
        lead = _TAG.match(t)
        while lead:
            tag = lead.group(1)
            if tag in DELIVERY_TAGS:
                delivery = tag
            elif tag in EVENT_TAGS:
                events.append({"tag": tag, "pos": "before"})
            t = t[lead.end():]
            lead = _TAG.match(t)

        def _capture(mm: re.Match) -> str:
            tag = mm.group(1)
            if tag in EVENT_TAGS:
                events.append({"tag": tag, "pos": "before"})
            return ""

        t = _TAG.sub(_capture, t).strip()
        if t:
            out[i] = {"i": i, "src": "llm", "spans": [
                {"text": t, "speaker": "narrator", "delivery": delivery, "events": events}]}
    return out


def _parse_perf_json(llm_out: str, allowed_speakers: set[str]) -> dict[int, dict]:
    """Parse span-JSON output (CLOUD models, P4) -> {i: paragraph}, validating against the closed
    vocabularies + the CAST ids (invalid speaker -> narrator, invalid event -> dropped)."""
    try:
        data = json.loads(llm_out[llm_out.find("{"): llm_out.rfind("}") + 1])
    except Exception:  # noqa: BLE001
        return {}
    out: dict[int, dict] = {}
    for p in (data.get("paragraphs") or []):
        try:
            i = int(p.get("i"))
        except (TypeError, ValueError):
            continue
        spans = []
        for s in (p.get("spans") or []):
            text = (s.get("text") or "").strip()
            if not text:
                continue
            speaker = s.get("speaker") or "narrator"
            if speaker != "narrator":
                sid = speaker.removeprefix("char:")
                if f"char:{sid}" not in allowed_speakers:
                    speaker = "narrator"
            spans.append({
                "text": text,
                "speaker": speaker,
                "delivery": s.get("delivery") if s.get("delivery") in DELIVERY_TAGS else "neutral",
                "events": [e for e in (s.get("events") or [])
                           if isinstance(e, dict) and e.get("tag") in EVENT_TAGS][:3],
                "pauseBeforeMs": max(0, min(int(s.get("pauseBeforeMs") or 0), 1500)),
            })
        if spans:
            mood = p.get("mood") if p.get("mood") in ("calm", "tense", "joyful", "somber", "action") else None
            out[i] = {"i": i, "src": "llm", "mood": mood, "spans": spans}
    return out


def _call_params(model_id: str, task: str) -> dict:
    """Everything needed to call one model: litellm string, cloud/local kind, endpoint, sampling."""
    entry = config.model_entry(model_id) or {}
    litellm_model = entry.get("litellm") or config.litellm_for(model_id)
    is_cloud = entry.get("kind") == "cloud"
    is_ollama = str(litellm_model).startswith("ollama/")
    sampling = dict(config.sampling)
    if is_ollama:
        # Ollama silently drops frequency/presence_penalty, so give it its NATIVE repeat penalty —
        # without it a small model loops forever on repetitive machine-translated text.
        sampling["repeat_penalty"] = 1.3
        sampling["num_ctx"] = config.num_ctx  # smaller KV cache -> more fits on the GPU
    # Stop before common chat trailers so no model wastes tokens on meta-commentary.
    # OpenAI-compatible APIs allow AT MOST 4 stop sequences (more -> 400 -> retry loop), so cloud
    # models get the 4 highest-value ones; local Ollama takes the full list.
    stops = [
        "\n\n**Please", "\n\nPlease note", "\n\n**Note", "\n\nNote:", "\n\nThis rewrite",
        "\n\nI hope this", "\n\n(Note", "\n\n---",
    ]
    sampling["stop"] = stops[:4] if is_cloud else stops
    return {
        "model_id": model_id,
        "entry": entry,
        "litellm_model": litellm_model,
        "is_cloud": is_cloud,
        "api_base": config.api_base_for(model_id) if is_cloud else (config.ollama_base_url if is_ollama else None),
        "api_key": config.api_key_for(model_id) if is_cloud else None,
        "sampling": sampling,
        "task": task,
        "effort": config.reasoning_effort(task) if is_cloud else None,  # OpenAI-style reasoning level
    }


def _cloud_usable(p: dict) -> bool:
    """Cloud model is callable right now: key present, circuit closed, budget under cap."""
    if not p["is_cloud"]:
        return True
    if not p["api_key"]:
        return False
    if _cloud_down_until > time.time():
        return False
    if budget.over_cap(config.cloud_budget["daily_usd_cap"]) and config.cloud_budget["on_cap"] == "fallback":
        return False
    return True


async def fix_text(
    text: str,
    model_id: str | None = None,
    previous_chapters: str = "",
    character_memory: str = "",
    system_prompt: str | None = None,
    on_chunk=None,
    task: str | None = None,
    script_type: str = "grammar",
    pause_event: asyncio.Event | None = None,
) -> dict:
    """Rewrite a chapter: chunk -> LiteLLM per chunk -> stitch. Returns {"fixed": str,
    "paragraphs": list|None} (paragraphs only for script_type=performance). Model comes from the
    explicit model_id, else the script_type-derived task routing; cloud failures/quota/budget fall
    back per-chunk to the task's local fallback so a job never dies on a cloud hiccup. A cleared
    pause_event suspends between chunks (job pause/resume)."""
    global _cloud_down_until
    task = task or SCRIPT_TASKS.get(script_type, "grammar_fix")
    routing = config.task_model(task)
    primary_id = model_id or routing["model"] or config.default_model
    fallback_id = routing.get("fallback") if primary_id != routing.get("fallback") else None

    system = system_prompt if system_prompt is not None else prompts.for_script(script_type)
    is_performance = script_type == "performance"
    paragraphs_src = split_paragraphs(text) if is_performance else None
    if is_performance and paragraphs_src:
        text = _mark_paragraphs(paragraphs_src)  # [[P<n>]] markers survive chunking (paragraph-packed)
    active = _call_params(primary_id, task)
    if not _cloud_usable(active) and fallback_id:
        log.warning("cloud model %s unavailable (key/circuit/budget) -> starting on fallback %s",
                    primary_id, fallback_id)
        active = _call_params(fallback_id, task)

    # Previous-chapter context bloats the prompt (slower eval); gate it behind a config flag.
    prev = previous_chapters if config.send_previous_chapters else ""

    chunks = split_chunks(text, config.chunk_chars)
    log.info("fix start: task=%s model=%s chars=%d chunks=%d prev=%s",
             task, active["litellm_model"], len(text), len(chunks), bool(prev))
    # P4: speakers the cloud model may assign, derived from the CAST block already flowing in as
    # character_memory (the charmap backfill) — no extra fetch, spoiler gating inherited.
    allowed_speakers = {f"char:{m}" for m in re.findall(r"char:([0-9a-fA-F]{6,})", character_memory or "")}

    parts: list[str] = []
    perf_matched: dict[int, dict] = {}
    fallbacks = 0
    for i, chunk in enumerate(chunks):
        if pause_event is not None and not pause_event.is_set():
            await pause_event.wait()  # job paused: suspend between chunks
        # Local models get a tight cap (runaway/looping protection). Cloud models burn HIDDEN
        # reasoning tokens inside max_tokens (measured: minimax/deepseek return EMPTY content with
        # finish=length on tight caps), so they get a roomy budget — reasoning is fast and the
        # visible output is still bounded by the prompt contract.
        tight = min(2048, len(chunk) // 2 + 192) if is_performance else min(1536, len(chunk) // 3 + 96)
        # Span-JSON output is verbose (keys + structure) on top of hidden reasoning: performance
        # chunks get the full budget (measured: 1863 starved minimax even on a 330-char chunk).
        roomy = 4096 if is_performance else min(4096, len(chunk) + 1536)
        out = None
        out_cloud_json = False
        for attempt_params in ([active] if not (active["is_cloud"] and fallback_id) else
                               [active, _call_params(fallback_id, task)]):
            # Performance uses TWO wire formats: cloud models emit validated span JSON (multi-voice
            # dialogue), local fallback keeps the inline-tag/marker contract.
            attempt_cloud_json = is_performance and attempt_params["is_cloud"]
            attempt_system = prompts.get("performance_prompt_json") if attempt_cloud_json else system
            messages = _build_messages(attempt_system, chunk, prev if i == 0 else "", character_memory)
            sem = _cloud_sem if attempt_params["is_cloud"] else _gpu_sem
            try:
                async with sem:
                    _t0 = time.time()
                    extra = {"response_format": {"type": "json_object"}} if attempt_cloud_json else {}
                    # reasoning_effort via extra_body so it survives litellm.drop_params for custom
                    # cloud model names (OpenAI-compatible endpoints read it as a top-level body field).
                    if attempt_params["is_cloud"] and attempt_params.get("effort"):
                        extra["extra_body"] = {"reasoning_effort": attempt_params["effort"]}
                    resp = await litellm.acompletion(
                        model=attempt_params["litellm_model"],
                        messages=messages,
                        api_base=attempt_params["api_base"],
                        api_key=attempt_params["api_key"],
                        num_retries=2 if attempt_params["is_cloud"] else 1,
                        timeout=240,
                        max_tokens=roomy if attempt_params["is_cloud"] else tight,
                        **attempt_params["sampling"],
                        **extra,
                    )
                _usage = getattr(resp, "usage", None)
                pt = getattr(_usage, "prompt_tokens", 0) or 0
                ct = getattr(_usage, "completion_tokens", 0) or 0
                metrics.record_llm_chunk(time.time() - _t0, pt, ct)
                if attempt_params["is_cloud"]:
                    budget.record(task, attempt_params["model_id"], pt, ct,
                                  _chunk_cost(attempt_params["entry"], pt, ct))
                raw = resp.choices[0].message.content or ""
                out = raw if attempt_cloud_json else _clean(raw)
                out_cloud_json = attempt_cloud_json
                break
            except Exception as e:  # noqa: BLE001
                if attempt_params["is_cloud"]:
                    cool = config.cloud_budget["quota_cooldown_s"] if "429" in str(e) \
                        else config.cloud_budget["cooldown_s"]
                    _cloud_down_until = time.time() + cool
                    fallbacks += 1
                    log.warning("cloud chunk failed (%s) -> circuit open %ds, falling back", e, cool)
                    budget.record(task, attempt_params["model_id"], 0, 0, 0.0, fallback=True)
                    active = _call_params(fallback_id, task) if fallback_id else active
                    continue  # retry this chunk on the fallback
                raise  # local failure propagates as before
        if out is None:
            raise RuntimeError(f"chunk {i + 1}/{len(chunks)} failed on all models")
        log.info("  chunk %d/%d in=%d out=%d model=%s%s",
                 i + 1, len(chunks), len(chunk), len(out), active["model_id"],
                 " (span-json)" if out_cloud_json else "")
        if is_performance:
            matched = _parse_perf_json(out, allowed_speakers) if out_cloud_json else _parse_perf_tags(out)
            perf_matched.update(matched)
            if on_chunk:
                on_chunk(i, len(chunks), " ".join(
                    s["text"] for p in matched.values() for s in p["spans"]))
        elif out:
            parts.append(out)
            if on_chunk:
                on_chunk(i, len(chunks), out)

    paragraphs = None
    if is_performance and paragraphs_src is not None:
        # any paragraph the model mangled/dropped falls back to the raw original — never fail a job
        paragraphs = [
            perf_matched.get(i) or {"i": i, "src": "raw", "spans": [
                {"text": src_text, "speaker": "narrator", "delivery": "neutral", "events": []}]}
            for i, src_text in enumerate(paragraphs_src)
        ]
        # display text = joined span texts (cues live ONLY in the structured spans, never in prose)
        result = "\n\n".join(" ".join(s["text"] for s in p["spans"]) for p in paragraphs).strip()
    else:
        result = "\n\n".join(parts).strip()
    storage.store(primary_id, text, result, meta={"chunks": len(chunks), "task": task, "fallbacks": fallbacks})
    return {"fixed": result, "paragraphs": paragraphs}
