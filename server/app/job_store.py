"""Reload-surviving jobs: every submitted job's spec (+ its id) persists to data/jobs/ and is
resurrected on startup with the SAME id, so devices polling that id continue seamlessly. Specs are
deleted on terminal states. Uvicorn --reload wiped in-flight jobs four times in one day — each
pipeline already skips work that exists on disk, so resuming is cheap."""

from __future__ import annotations

import hashlib
import json
import logging
import os
from pathlib import Path

log = logging.getLogger("job_store")

ROOT = Path("data/jobs")


def _path(kind: str, jid: str) -> Path:
    return ROOT / f"{kind}-{jid}.json"


def save(kind: str, jid: str, spec: dict) -> None:
    try:
        ROOT.mkdir(parents=True, exist_ok=True)
        tmp = _path(kind, jid).with_suffix(".part")
        tmp.write_text(json.dumps({"kind": kind, "id": jid, **spec}, ensure_ascii=False),
                       encoding="utf-8")
        os.replace(tmp, _path(kind, jid))
    except Exception:  # noqa: BLE001
        log.exception("job spec save failed (%s %s)", kind, jid)


def remove(kind: str, jid: str) -> None:
    try:
        _path(kind, jid).unlink(missing_ok=True)
    except Exception:  # noqa: BLE001
        pass


def _load_all() -> list[dict]:
    out = []
    if ROOT.is_dir():
        for f in sorted(ROOT.glob("*.json")):
            try:
                out.append(json.loads(f.read_text(encoding="utf-8")))
            except Exception:  # noqa: BLE001
                log.warning("dropping unreadable job spec %s", f.name)
                f.unlink(missing_ok=True)
    return out


def resume_all() -> int:
    """Resurrect persisted jobs (same ids). Fix items are re-filtered against already-persisted
    script files; TTS/charmap pipelines skip existing work internally."""
    from . import chapter_texts  # noqa: F401  (import order safety)
    from .charmap_jobs import charmap_jobs
    from .jobs import jobs
    from .tts_jobs import tts_jobs

    resumed = 0
    for spec in _load_all():
        kind = spec.get("kind")
        jid = spec.get("id") or ""
        try:
            if kind == "fix":
                items = spec.get("items") or []
                book_id = spec.get("book_id") or ""
                script = spec.get("script_type") or "grammar"
                if book_id:  # skip chapters whose script file already landed
                    ext = "json" if script == "performance" else "txt"
                    sdir = Path("data/scripts") / book_id / script
                    have = {f.stem[1:] for f in sdir.glob(f"c*.{ext}")} if sdir.is_dir() else set()
                    items = [i for i in items if str(i.get("id")) not in have]
                if items:
                    jobs.submit(spec.get("model"), items, script, book_id, jid=jid)
                    resumed += 1
                else:
                    remove(kind, jid)
            elif kind == "tts":
                tts_jobs.submit(
                    spec["book_id"], spec["model_id"], int(spec.get("sid", 0)),
                    int(spec.get("sample_rate", 24000)), spec.get("items") or [],
                    int(spec.get("num_threads", 3)), spec.get("book_name") or "",
                    spec.get("device_id") or "", spec.get("device_name") or "", jid=jid,
                )
                resumed += 1
            elif kind == "charmap":
                charmap_jobs.submit(spec["book_id"], int(spec.get("start", 0)),
                                    int(spec.get("end", 0)), spec.get("cast_model") or "kokoro",
                                    jid=jid)
                resumed += 1
        except Exception:  # noqa: BLE001
            log.exception("job resume failed (%s %s)", kind, jid)
    if resumed:
        log.info("resumed %d persisted job(s) after restart", resumed)
    return resumed
