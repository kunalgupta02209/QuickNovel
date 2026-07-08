"""On-disk store for server-generated TTS WAVs.

Layout mirrors the Android app's TtsAudioCache tree so a chapter's files can be zipped here and
unpacked straight into the app cache:  <audio_dir>/<book_id>/<model_id>/s<sid>/c<index>/<key>.wav
where key = the app's sha1(speakOutMsg)[:24]. book_id/key are client-supplied and sanitized.
"""

from __future__ import annotations

import io
import os
import re
import zipfile
from pathlib import Path

_SAFE = re.compile(r"[^A-Za-z0-9_.-]")


def audio_dir() -> Path:
    return Path(os.environ.get("TTS_AUDIO_DIR", "/data/tts-audio"))


def _safe(seg: str) -> str:
    # Opaque ids only: strip anything that could escape the tree ("/", "..", etc.).
    s = _SAFE.sub("_", seg or "")
    if s in ("", ".", ".."):
        raise ValueError(f"unsafe path segment: {seg!r}")
    return s


def chapter_dir(book_id: str, model_id: str, sid: int, index: int) -> Path:
    return audio_dir() / _safe(book_id) / _safe(model_id) / f"s{int(sid)}" / f"c{int(index)}"


def wav_path(book_id: str, model_id: str, sid: int, index: int, key: str) -> Path:
    return chapter_dir(book_id, model_id, sid, index) / f"{_safe(key)}.wav"


def exists(book_id: str, model_id: str, sid: int, index: int, key: str) -> bool:
    return wav_path(book_id, model_id, sid, index, key).exists()


def write(book_id: str, model_id: str, sid: int, index: int, key: str, data: bytes) -> None:
    dest = wav_path(book_id, model_id, sid, index, key)
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(f".{os.getpid()}.part")
    tmp.write_bytes(data)
    os.replace(tmp, dest)  # atomic


def chapter_keys(book_id: str, model_id: str, sid: int, index: int) -> list[str]:
    d = chapter_dir(book_id, model_id, sid, index)
    if not d.is_dir():
        return []
    return sorted(p.stem for p in d.glob("*.wav"))


def has_any(book_id: str, model_id: str, sid: int, index: int) -> bool:
    d = chapter_dir(book_id, model_id, sid, index)
    return d.is_dir() and any(d.glob("*.wav"))


def zip_chapter(book_id: str, model_id: str, sid: int, index: int) -> bytes:
    """ZIP (STORED, no compression — WAV PCM barely compresses) of the chapter's <key>.wav files."""
    d = chapter_dir(book_id, model_id, sid, index)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as zf:
        if d.is_dir():
            for p in sorted(d.glob("*.wav")):
                zf.write(p, arcname=p.name)  # entry name == "<key>.wav"
    return buf.getvalue()
