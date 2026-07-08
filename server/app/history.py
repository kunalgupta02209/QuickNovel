"""Persistent job history (JSONL) — jobs live in memory and vanish on --reload/restart, so every
terminal job outcome (and server start) is appended here. Powers the dashboard's recent-history
table, 24h stats, and an optional alert webhook on errors. JSONL (not SQLite) to stay friendly to
the Windows bind-mount."""

from __future__ import annotations

import json
import logging
import os
import threading
import time
from collections import deque
from pathlib import Path

log = logging.getLogger("history")

HISTORY_PATH = Path(os.environ.get("HISTORY_PATH", "data/history.jsonl"))
_ROTATE_BYTES = 10 * 1024 * 1024

_lock = threading.Lock()
_recent: deque = deque(maxlen=1000)


def _load_tail() -> None:
    try:
        if HISTORY_PATH.exists():
            lines = HISTORY_PATH.read_text(encoding="utf-8", errors="replace").splitlines()[-1000:]
            for ln in lines:
                try:
                    _recent.append(json.loads(ln))
                except Exception:  # noqa: BLE001
                    pass
    except Exception:  # noqa: BLE001
        log.exception("history tail load failed")


def append(record: dict) -> None:
    """Append one record (adds ts). kind: llm | tts | server. Never raises."""
    try:
        record = {"ts": time.time(), **record}
        with _lock:
            HISTORY_PATH.parent.mkdir(parents=True, exist_ok=True)
            if HISTORY_PATH.exists() and HISTORY_PATH.stat().st_size > _ROTATE_BYTES:
                os.replace(HISTORY_PATH, HISTORY_PATH.with_name("history.1.jsonl"))
            with HISTORY_PATH.open("a", encoding="utf-8") as f:
                f.write(json.dumps(record, ensure_ascii=False) + "\n")
            _recent.append(record)
        if record.get("status") == "error":
            _fire_alert(record)
    except Exception:  # noqa: BLE001
        log.exception("history append failed")


def recent(kind: str | None = None, limit: int = 100) -> list[dict]:
    with _lock:
        items = list(_recent)
    if kind:
        items = [r for r in items if r.get("kind") == kind]
    return items[-limit:][::-1]


def stats_24h() -> dict:
    cutoff = time.time() - 86400
    with _lock:
        items = [r for r in _recent if r.get("ts", 0) >= cutoff]
    out: dict = {}
    for kind in ("llm", "tts"):
        rows = [r for r in items if r.get("kind") == kind]
        done = [r for r in rows if r.get("status") == "done"]
        err = [r for r in rows if r.get("status") == "error"]
        out[kind] = {
            "jobs": len(rows),
            "done": len(done),
            "errors": len(err),
            "avg_duration_s": round(
                sum(r.get("duration_s") or 0 for r in done) / len(done), 1
            ) if done else None,
        }
    out["server_starts"] = sum(1 for r in items if r.get("kind") == "server")
    return out


def on_server_start() -> None:
    _load_tail()
    append({"kind": "server", "event": "start"})


def _fire_alert(record: dict) -> None:
    """Optional fire-and-forget webhook (e.g. ntfy.sh) on job errors."""
    try:
        from .config import config

        url = (config.get("dashboard", {}) or {}).get("alert_webhook") or ""
        if not url:
            return
        import threading as _t
        import urllib.request

        def _post() -> None:
            try:
                body = f"QuickNovel {record.get('kind')} job error: {record.get('error') or record}"
                req = urllib.request.Request(url, data=body.encode("utf-8"), method="POST")
                urllib.request.urlopen(req, timeout=5)  # noqa: S310
            except Exception:  # noqa: BLE001
                pass

        _t.Thread(target=_post, daemon=True).start()
    except Exception:  # noqa: BLE001
        pass
