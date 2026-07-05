import logging
import re

import litellm

from . import storage
from .config import config
from .prompts import prompts

log = logging.getLogger("fixer")


def split_chunks(text: str, max_chars: int) -> list[str]:
    """Split a chapter into <= max_chars chunks on paragraph boundaries (hard-splitting giants)."""
    paras = re.split(r"\n\s*\n", text)
    chunks: list[str] = []
    cur = ""
    for p in paras:
        p = p.strip()
        if not p:
            continue
        if len(p) > max_chars:
            if cur:
                chunks.append(cur.strip())
                cur = ""
            for i in range(0, len(p), max_chars):
                chunks.append(p[i : i + max_chars].strip())
        elif len(cur) + len(p) > max_chars:
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


def _clean(out: str) -> str:
    out = (out or "").strip()
    if out.startswith("```"):
        out = out.split("\n", 1)[-1].rsplit("```", 1)[0]
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
    api_base = config.ollama_base_url if str(litellm_model).startswith("ollama/") else None
    sampling = config.sampling

    chunks = split_chunks(text, config.chunk_chars)
    log.info("fix start: model=%s chars=%d chunks=%d", litellm_model, len(text), len(chunks))
    parts: list[str] = []
    for i, chunk in enumerate(chunks):
        messages = _build_messages(system, chunk, previous_chapters if i == 0 else "", character_memory)
        resp = await litellm.acompletion(
            model=litellm_model,
            messages=messages,
            api_base=api_base,
            num_retries=2,
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
