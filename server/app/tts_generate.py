"""Server-side TTS generation from stored chapter texts — shared by the /tts/generate endpoint,
the dashboard, and the scripts->audio auto-chain (a completed performance-script job queues its
book's cast audio when `auto_cast_audio` is enabled)."""

from __future__ import annotations

import hashlib
import logging

from . import casting, chapter_texts
from .config import config
from .tts_jobs import tts_jobs

log = logging.getLogger("tts_generate")


def generate(book_id: str, model_id: str = "kokoro", sid: int = 1,
             start: int = 0, end: int = -1, use_script: bool = False,
             device: str = "dashboard") -> dict:
    """Queue a synthesis job. use_script=True casts each line via the performance ScriptDoc +
    charmap (skipping script-less chapters); False = plain single-voice. Raises ValueError when
    there is nothing to synthesize."""
    idxs = chapter_texts.chapter_indices(book_id)
    if not idxs:
        raise ValueError("no stored chapters for this book")
    end = end if end >= 0 else max(idxs)
    meta = chapter_texts.meta(book_id)
    items = []
    skipped_no_script = 0
    for i in idxs:
        if not (start <= i <= end):
            continue
        text = chapter_texts.get_text(book_id, i) or ""
        lines = [ln for ln in text.splitlines() if ln.strip()]
        if use_script:
            cast = casting.cast_lines(book_id, model_id, i, lines)
            if cast is None:
                skipped_no_script += 1
                continue
            sentences = [
                {"key": hashlib.sha1(c["text"].encode("utf-8")).hexdigest()[:24],
                 "text": c["text"], "sid": c["sid"], "speed": c["speed"]}
                for c in cast
            ]
        else:
            sentences = [
                {"key": hashlib.sha1(ln.encode("utf-8")).hexdigest()[:24], "text": ln}
                for ln in lines
            ]
        if sentences:
            items.append({"index": i, "name": (meta.get("chapters") or {}).get(str(i), ""),
                          "sentences": sentences})
    if not items:
        raise ValueError(f"no synthesizable sentences in range (skipped {skipped_no_script} script-less chapters)")
    job = tts_jobs.submit(
        book_id, model_id, sid, 24000, items, config.tts_num_threads,
        meta.get("book_name") or book_id, device, device,
    )
    return {"job_id": job.id, "chapters": len(items),
            "cast": use_script, "skipped_no_script": skipped_no_script}


def auto_cast_audio_enabled() -> bool:
    return bool(config.get("auto_cast_audio", True))
