import asyncio
import logging
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
    task: str = "grammar_fix",
) -> str:
    """Rewrite a chapter: chunk -> LiteLLM per chunk -> stitch. Model comes from the explicit
    model_id, else the task->model routing; cloud failures/quota/budget fall back per-chunk to the
    task's local fallback so a job never dies on a cloud hiccup."""
    global _cloud_down_until
    routing = config.task_model(task)
    primary_id = model_id or routing["model"] or config.default_model
    fallback_id = routing.get("fallback") if primary_id != routing.get("fallback") else None

    system = system_prompt if system_prompt is not None else prompts.system
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
    parts: list[str] = []
    fallbacks = 0
    for i, chunk in enumerate(chunks):
        messages = _build_messages(system, chunk, prev if i == 0 else "", character_memory)
        # A rewrite is ~the input length; hard-cap generation (tight floor) so short inputs don't
        # over-generate a chat trailer and a looping model can't run away.
        max_gen = min(1536, len(chunk) // 3 + 96)
        out = None
        for attempt_params in ([active] if not (active["is_cloud"] and fallback_id) else
                               [active, _call_params(fallback_id, task)]):
            sem = _cloud_sem if attempt_params["is_cloud"] else _gpu_sem
            try:
                async with sem:
                    _t0 = time.time()
                    resp = await litellm.acompletion(
                        model=attempt_params["litellm_model"],
                        messages=messages,
                        api_base=attempt_params["api_base"],
                        api_key=attempt_params["api_key"],
                        num_retries=2 if attempt_params["is_cloud"] else 1,
                        timeout=240,
                        max_tokens=max_gen,
                        **attempt_params["sampling"],
                    )
                _usage = getattr(resp, "usage", None)
                pt = getattr(_usage, "prompt_tokens", 0) or 0
                ct = getattr(_usage, "completion_tokens", 0) or 0
                metrics.record_llm_chunk(time.time() - _t0, pt, ct)
                if attempt_params["is_cloud"]:
                    budget.record(task, attempt_params["model_id"], pt, ct,
                                  _chunk_cost(attempt_params["entry"], pt, ct))
                out = _clean(resp.choices[0].message.content)
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
        log.info("  chunk %d/%d in=%d out=%d model=%s",
                 i + 1, len(chunks), len(chunk), len(out), active["model_id"])
        if out:
            parts.append(out)
            if on_chunk:
                on_chunk(i, len(chunks), out)

    result = "\n\n".join(parts).strip()
    storage.store(primary_id, text, result, meta={"chunks": len(chunks), "task": task, "fallbacks": fallbacks})
    return result
