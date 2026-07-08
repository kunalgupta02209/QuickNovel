"""Web dashboard — GET /dashboard serves a single static HTML page that polls
GET /dashboard/api/overview for everything: LLM fix jobs, TTS generation, device telemetry
(book/chapter syncs), and resource consumption. All samplers nullable; the page renders n/a."""

from __future__ import annotations

import logging
import time
from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import FileResponse, PlainTextResponse

from . import history, metrics, tts_engine
from .config import config
from .fixer import gpu_busy
from .jobs import jobs
from .telemetry import telemetry
from .tts_jobs import sem_stats, tts_jobs

log = logging.getLogger("dashboard")
router = APIRouter()

_STARTED = time.time()
_PAGE = Path("static/dashboard.html")


def _job_rate_eta(j: dict, done_key: str = "progress") -> dict:
    """Live rate (units/s since start) + ETA for a running job summary dict."""
    started = j.get("started")
    if not started or j.get("status") != "running":
        return {"rate": None, "eta_s": None}
    elapsed = max(time.time() - started, 1e-6)
    done = j.get("synthesized") or j.get(done_key) or 0
    rate = done / elapsed
    remaining = max((j.get("total") or 0) - (j.get(done_key) or 0), 0)
    return {
        "rate": round(rate, 2),
        "eta_s": int(remaining / rate) if rate > 0 else None,
    }


@router.get("/dashboard")
def dashboard_page():
    return FileResponse(str(_PAGE), media_type="text/html", headers={"Cache-Control": "no-store"})


@router.get("/dashboard/api/overview")
async def overview():
    llm_jobs = [{**j, **_job_rate_eta(j)} for j in jobs.list()[:25]]
    tts_list = []
    for j in tts_jobs.list()[:25]:
        detail = tts_jobs.get(j["id"])
        chapters = detail.detail().get("chapters") if detail else None
        tts_list.append({**j, **_job_rate_eta(j), "chapters": chapters})

    return {
        "server": {
            "now": time.time(),
            "uptime_s": int(time.time() - _STARTED),
            "default_model": config.default_model,
            "gpu_busy": gpu_busy(),
        },
        "llm": {"jobs": llm_jobs, "rates": metrics.llm_rates()},
        "tts": {
            "jobs": tts_list,
            "rates": metrics.tts_rates(),
            "sem": sem_stats(),
            "pool": tts_engine.pool_stats(),
        },
        "devices": telemetry.latest(),
        "resources": {
            "proc": metrics.proc_stats(),
            "cgroup": metrics.cgroup_stats(),
            "host_vm": metrics.host_stats(),
            "disk": await metrics.disk_stats(),
            "ollama": await metrics.ollama_status(config.ollama_base_url),
        },
        "history_24h": history.stats_24h(),
    }


@router.get("/dashboard/api/history")
def api_history(kind: str | None = None, limit: int = 100):
    return {"history": history.recent(kind, min(limit, 500))}


@router.get("/dashboard/api/logs", response_class=PlainTextResponse)
def api_logs(n: int = 200):
    try:
        path = Path("logs/server.log")
        if not path.exists():
            return "no log file"
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        return "\n".join(lines[-min(n, 1000):])
    except Exception as e:  # noqa: BLE001
        return f"log read failed: {e}"
