"""Lightweight in-process metrics for the dashboard.

Ring buffers of recent work items (LLM chunks / TTS sentences) give live throughput rates, and a set
of guarded samplers report resource consumption (process, cgroup, host, disk, Ollama). Every sampler
is try/except-guarded and returns None on failure — the dashboard renders "n/a", never breaks.
"""

from __future__ import annotations

import asyncio
import logging
import os
import time
from collections import deque
from pathlib import Path
from threading import Lock

log = logging.getLogger("metrics")

# ---- work-rate ring buffers -----------------------------------------------------------------

_llm_lock = Lock()
_llm_chunks: deque = deque(maxlen=50)   # (ts, duration_s, prompt_tokens, completion_tokens)
_tts_lock = Lock()
_tts_sentences: deque = deque(maxlen=200)  # (ts, duration_s, chars)


def record_llm_chunk(duration_s: float, prompt_tokens: int, completion_tokens: int) -> None:
    with _llm_lock:
        _llm_chunks.append((time.time(), duration_s, prompt_tokens, completion_tokens))


def record_tts_sentence(duration_s: float, chars: int) -> None:
    with _tts_lock:
        _tts_sentences.append((time.time(), duration_s, chars))


def llm_rates(window_s: float = 120.0) -> dict:
    now = time.time()
    with _llm_lock:
        recent = [c for c in _llm_chunks if now - c[0] <= window_s]
    if not recent:
        return {"chunks_per_min": 0.0, "tok_per_s": 0.0, "avg_chunk_s": None}
    busy = sum(c[1] for c in recent)
    out_toks = sum(c[3] for c in recent)
    return {
        "chunks_per_min": round(len(recent) * 60.0 / window_s, 2),
        "tok_per_s": round(out_toks / busy, 1) if busy > 0 else 0.0,
        "avg_chunk_s": round(busy / len(recent), 2),
    }


def tts_rates(window_s: float = 120.0) -> dict:
    now = time.time()
    with _tts_lock:
        recent = [s for s in _tts_sentences if now - s[0] <= window_s]
    if not recent:
        return {"sentences_per_min": 0.0, "avg_sentence_s": None, "chars_per_s": 0.0}
    busy = sum(s[1] for s in recent)
    chars = sum(s[2] for s in recent)
    return {
        "sentences_per_min": round(len(recent) * 60.0 / window_s, 2),
        "avg_sentence_s": round(busy / len(recent), 2),
        "chars_per_s": round(chars / busy, 1) if busy > 0 else 0.0,
    }


# ---- resource samplers ----------------------------------------------------------------------

_proc = None


def proc_stats() -> dict | None:
    """This uvicorn process: CPU% (since last call) + RSS."""
    global _proc
    try:
        import psutil

        if _proc is None:
            _proc = psutil.Process()
            _proc.cpu_percent(None)  # prime; first real value on the next poll
        with _proc.oneshot():
            return {
                "cpu_percent": round(_proc.cpu_percent(None), 1),
                "rss_mb": round(_proc.memory_info().rss / (1024 * 1024), 1),
                "threads": _proc.num_threads(),
                "uptime_s": int(time.time() - _proc.create_time()),
            }
    except Exception:  # noqa: BLE001
        return None


_cg_prev: tuple[float, int] | None = None  # (wall_ts, usage_usec)


def cgroup_stats() -> dict | None:
    """Container-level CPU%/memory from cgroup v2 (None outside a container)."""
    global _cg_prev
    try:
        cpu = Path("/sys/fs/cgroup/cpu.stat")
        mem = Path("/sys/fs/cgroup/memory.current")
        if not cpu.exists():
            return None
        usage = None
        for line in cpu.read_text().splitlines():
            if line.startswith("usage_usec"):
                usage = int(line.split()[1])
                break
        if usage is None:
            return None
        now = time.time()
        pct = None
        if _cg_prev is not None:
            dt = now - _cg_prev[0]
            if dt > 0:
                pct = round((usage - _cg_prev[1]) / (dt * 1e6) * 100.0, 1)
        _cg_prev = (now, usage)
        out = {"cpu_percent": pct}
        if mem.exists():
            out["mem_mb"] = round(int(mem.read_text().strip()) / (1024 * 1024), 1)
        return out
    except Exception:  # noqa: BLE001
        return None


def host_stats() -> dict | None:
    try:
        import psutil

        vm = psutil.virtual_memory()
        return {
            "cpu_percent": psutil.cpu_percent(None),
            "mem_used_mb": round(vm.used / (1024 * 1024)),
            "mem_total_mb": round(vm.total / (1024 * 1024)),
        }
    except Exception:  # noqa: BLE001
        return None


# disk usage: os.walk over the audio tree can touch ~50k files — do it in a thread with a TTL cache.
_disk_lock = Lock()
_disk_cache: dict = {"ts": 0.0, "data": None, "running": False}
_DISK_TTL_S = 60.0


def _du(path: str) -> int:
    total = 0
    for root, _dirs, files in os.walk(path):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
            except OSError:
                pass
    return total


async def disk_stats() -> dict | None:
    """TTL-cached sizes of the TTS audio + model volumes (stale-while-revalidate)."""
    audio = os.environ.get("TTS_AUDIO_DIR", "/data/tts-audio")
    models = os.environ.get("TTS_MODELS_DIR", "/models")
    with _disk_lock:
        fresh = time.time() - _disk_cache["ts"] < _DISK_TTL_S
        data = _disk_cache["data"]
        if fresh or _disk_cache["running"]:
            return data
        _disk_cache["running"] = True
    try:
        audio_b = await asyncio.to_thread(_du, audio) if os.path.isdir(audio) else 0
        models_b = await asyncio.to_thread(_du, models) if os.path.isdir(models) else 0
        new = {"tts_audio_mb": round(audio_b / (1024 * 1024), 1), "models_mb": round(models_b / (1024 * 1024), 1)}
        with _disk_lock:
            _disk_cache.update(ts=time.time(), data=new, running=False)
        return new
    except Exception:  # noqa: BLE001
        with _disk_lock:
            _disk_cache["running"] = False
        return data


async def ollama_status(base_url: str | None) -> dict | None:
    """Loaded models + VRAM from the Ollama API (the LLM backend's real resource user)."""
    if not base_url:
        return None
    try:
        import httpx

        async with httpx.AsyncClient(timeout=1.5) as client:
            ps = (await client.get(f"{base_url.rstrip('/')}/api/ps")).json()
        models = [
            {
                "name": m.get("name"),
                "size_vram_mb": round((m.get("size_vram") or 0) / (1024 * 1024)),
                "size_mb": round((m.get("size") or 0) / (1024 * 1024)),
                "expires_at": m.get("expires_at"),
            }
            for m in (ps.get("models") or [])
        ]
        return {"loaded_models": models}
    except Exception:  # noqa: BLE001
        return None
