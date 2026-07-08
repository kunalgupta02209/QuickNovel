"""Server-side store of chapter TEXTS, populated as a side effect of /tts/batch submissions (the
app already ships every chapter's sentences there — joining them reconstructs the chapter). Powers:
character-map extraction (P3), the reader search, and device-to-device book sync.

Layout: data/chapters/<book_id>/c<idx>.txt + meta.json {book_name, api_name, author, chapters:{idx: name}}
Longer-wins overwrite: a resubmission with MORE text (e.g. cache-skips excluded fewer sentences)
replaces a shorter stored copy; never the reverse.
"""

from __future__ import annotations

import json
import logging
import re
import threading
from pathlib import Path

log = logging.getLogger("chapter_texts")

ROOT = Path("data/chapters")
_SAFE = re.compile(r"[^A-Za-z0-9_.-]")
_lock = threading.Lock()


def _dir(book_id: str) -> Path:
    return ROOT / _SAFE.sub("_", book_id)[:64]


def _meta_path(book_id: str) -> Path:
    return _dir(book_id) / "meta.json"


def _load_meta(book_id: str) -> dict:
    p = _meta_path(book_id)
    if p.exists():
        try:
            return json.loads(p.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            pass
    return {"book_name": "", "api_name": "", "author": "", "chapters": {}}


def store_batch(book_id: str, book_name: str, items: list[dict],
                api_name: str = "", author: str = "") -> None:
    """Persist chapter text reconstructed from a /tts/batch's sentence lists (longer wins)."""
    if not book_id:
        return
    try:
        with _lock:
            d = _dir(book_id)
            d.mkdir(parents=True, exist_ok=True)
            meta = _load_meta(book_id)
            if book_name:
                meta["book_name"] = book_name
            if api_name:
                meta["api_name"] = api_name
            if author:
                meta["author"] = author
            for it in items:
                idx = int(it.get("index", -1))
                if idx < 0:
                    continue
                sentences = [s.get("text") or "" for s in (it.get("sentences") or [])]
                text = "\n".join(t for t in sentences if t).strip()
                if not text:
                    continue
                f = d / f"c{idx}.txt"
                if not f.exists() or f.stat().st_size < len(text.encode("utf-8")):
                    f.write_text(text, encoding="utf-8")
                if it.get("name"):
                    meta["chapters"][str(idx)] = it["name"]
            _meta_path(book_id).write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")
    except Exception:  # noqa: BLE001
        log.exception("store_batch failed for %s", book_id)


def get_text(book_id: str, index: int) -> str | None:
    f = _dir(book_id) / f"c{index}.txt"
    return f.read_text(encoding="utf-8", errors="replace") if f.exists() else None


def chapter_indices(book_id: str) -> list[int]:
    d = _dir(book_id)
    if not d.is_dir():
        return []
    out = []
    for f in d.glob("c*.txt"):
        try:
            out.append(int(f.stem[1:]))
        except ValueError:
            pass
    return sorted(out)


def meta(book_id: str) -> dict:
    return _load_meta(book_id)


def list_books() -> list[dict]:
    out = []
    if ROOT.is_dir():
        for d in ROOT.iterdir():
            if d.is_dir():
                m = _load_meta(d.name)
                idxs = chapter_indices(d.name)
                out.append({
                    "book_id": d.name, "book_name": m.get("book_name") or d.name,
                    "api_name": m.get("api_name") or "", "author": m.get("author") or "",
                    "chapters": len(idxs), "max_index": max(idxs) if idxs else -1,
                })
    return sorted(out, key=lambda b: b["book_name"].lower())
