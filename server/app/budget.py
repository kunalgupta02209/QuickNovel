"""Cloud usage ledger + daily spend guardrail.

Every cloud LLM call appends a JSONL record (task, model, tokens, cost). Cost is approximate —
token counts x per-model prices from config when present, else $0 (GO-plan models are quota-included;
the provider's own 429 then drives the fallback). over_cap() gates new cloud calls."""

from __future__ import annotations

import json
import logging
import os
import threading
import time
from pathlib import Path

log = logging.getLogger("budget")

USAGE_PATH = Path(os.environ.get("USAGE_PATH", "data/usage.jsonl"))
_lock = threading.Lock()
_today: list[dict] = []  # in-memory records for the current UTC day
_today_key = ""


def _day_key(ts: float | None = None) -> str:
    return time.strftime("%Y-%m-%d", time.gmtime(ts or time.time()))


def _roll() -> None:
    global _today, _today_key
    key = _day_key()
    if key != _today_key:
        _today_key = key
        _today = []
        # reload today's records from disk (survives --reload/restart)
        try:
            if USAGE_PATH.exists():
                for ln in USAGE_PATH.read_text(encoding="utf-8", errors="replace").splitlines()[-5000:]:
                    try:
                        r = json.loads(ln)
                        if _day_key(r.get("ts", 0)) == key:
                            _today.append(r)
                    except Exception:  # noqa: BLE001
                        pass
        except Exception:  # noqa: BLE001
            log.exception("usage reload failed")


def record(task: str, model: str, prompt_tokens: int, completion_tokens: int,
           cost_usd: float, fallback: bool = False) -> None:
    rec = {
        "ts": time.time(), "task": task, "model": model,
        "prompt_tokens": prompt_tokens, "completion_tokens": completion_tokens,
        "cost_usd": round(cost_usd, 6), "fallback": fallback,
    }
    try:
        with _lock:
            _roll()
            USAGE_PATH.parent.mkdir(parents=True, exist_ok=True)
            with USAGE_PATH.open("a", encoding="utf-8") as f:
                f.write(json.dumps(rec) + "\n")
            _today.append(rec)
    except Exception:  # noqa: BLE001
        log.exception("usage record failed")


def today() -> dict:
    with _lock:
        _roll()
        rows = list(_today)
    by_task: dict[str, dict] = {}
    for r in rows:
        t = by_task.setdefault(r.get("task") or "?", {"calls": 0, "tokens": 0, "usd": 0.0, "fallbacks": 0})
        t["calls"] += 1
        t["tokens"] += (r.get("prompt_tokens") or 0) + (r.get("completion_tokens") or 0)
        t["usd"] += r.get("cost_usd") or 0.0
        if r.get("fallback"):
            t["fallbacks"] += 1
    return {
        "date": _today_key or _day_key(),
        "usd": round(sum(r.get("cost_usd") or 0.0 for r in rows), 4),
        "calls": len(rows),
        "tokens": sum((r.get("prompt_tokens") or 0) + (r.get("completion_tokens") or 0) for r in rows),
        "by_task": {k: {**v, "usd": round(v["usd"], 4)} for k, v in by_task.items()},
    }


def over_cap(daily_usd_cap: float) -> bool:
    if daily_usd_cap <= 0:
        return False
    return today()["usd"] >= daily_usd_cap
