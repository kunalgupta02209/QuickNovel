import asyncio
import logging
import re

import litellm

from . import storage
from .config import config
from .prompts import prompts

log = logging.getLogger("fixer")

# Drop provider-unsupported sampling params instead of erroring — e.g. Ollama rejects
# presence_penalty/frequency_penalty, while OpenAI-style providers accept them.
litellm.drop_params = True

# Serialize model calls. Concurrent requests each open a context window; on a small GPU the
# model + multiple KV caches blow the VRAM budget and every request thrashes to a crawl. One
# call at a time keeps each request fast; extra callers simply queue.
_gpu_sem = asyncio.Semaphore(1)


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


def _clean(out: str) -> str:
    out = (out or "").strip()
    if out.startswith("```"):
        out = out.split("\n", 1)[-1].rsplit("```", 1)[0].strip()
    # Small models sometimes prepend a label line like "**Rewritten Chapter**" or
    # "Here is the rewritten text:" — drop a short leading preamble line, keep the prose.
    parts = out.split("\n", 1)
    if len(parts) == 2 and len(parts[0]) < 70 and _PREAMBLE.match(parts[0]):
        out = parts[1].strip()
    return out.strip()


async def fix_text(
    text: str,
    model_id: str | None = None,
    previous_chapters: str = "",
    character_memory: str = "",
    system_prompt: str | None = None,
    on_chunk=None,
) -> str:
    """Rewrite a chapter: chunk -> LiteLLM per chunk (real repeat penalty) -> stitch. Shared by API + CLI."""
    model_id = model_id or config.default_model
    litellm_model = config.litellm_for(model_id)
    system = system_prompt if system_prompt is not None else prompts.system
    is_ollama = str(litellm_model).startswith("ollama/")
    api_base = config.ollama_base_url if is_ollama else None
    sampling = dict(config.sampling)
    # Ollama silently drops frequency/presence_penalty, so give it its NATIVE repeat penalty —
    # without it a small model loops forever on repetitive machine-translated text, which is what
    # made single chunks run for minutes / time out.
    if is_ollama:
        sampling["repeat_penalty"] = 1.3
        sampling["num_ctx"] = config.num_ctx  # smaller context -> smaller KV cache -> more fits on GPU

    # Previous-chapter context bloats the prompt (slower eval); gate it behind a config flag.
    prev = previous_chapters if config.send_previous_chapters else ""

    chunks = split_chunks(text, config.chunk_chars)
    log.info(
        "fix start: model=%s chars=%d chunks=%d ctx=%d prev=%s",
        litellm_model, len(text), len(chunks), config.num_ctx, bool(prev),
    )
    parts: list[str] = []
    for i, chunk in enumerate(chunks):
        messages = _build_messages(system, chunk, prev if i == 0 else "", character_memory)
        # A rewrite is ~the input length; hard-cap generation so a looping model can't run away.
        max_gen = min(1536, len(chunk) // 3 + 256)
        async with _gpu_sem:
            resp = await litellm.acompletion(
                model=litellm_model,
                messages=messages,
                api_base=api_base,
                num_retries=1,
                timeout=240,
                max_tokens=max_gen,
                **sampling,
            )
        out = _clean(resp.choices[0].message.content)
        log.info("  chunk %d/%d in=%d out=%d", i + 1, len(chunks), len(chunk), len(out))
        if out:
            parts.append(out)
            if on_chunk:
                on_chunk(i, len(chunks), out)

    result = "\n\n".join(parts).strip()
    storage.store(model_id, text, result, meta={"chunks": len(chunks)})
    return result
