"""Device telemetry — the app POSTs periodic state snapshots (downloads, TTS cache fill, active
remote/pregen/fix jobs) and the dashboard renders a per-device card. Snapshot protocol (idempotent,
survives --reload; the phone re-sends state, nothing is lost)."""

from __future__ import annotations

import json
import logging
import re
import threading
import time
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request

log = logging.getLogger("telemetry")

TELEMETRY_DIR = Path("data/telemetry")
_ONLINE_WINDOW_S = 90.0
_SAFE_ID = re.compile(r"[^A-Za-z0-9_.-]")


class TelemetryRegistry:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._latest: dict[str, dict] = {}  # device_id -> last snapshot (+received_at)
        self._commands: dict[str, list[dict]] = {}  # device_id -> pending commands (piggyback channel)

    def ingest(self, snapshot: dict) -> list[dict]:
        """Store the snapshot; return (and clear) any pending commands for this device — the
        telemetry POST response is the server->device command channel (no push needed)."""
        device_id = str(snapshot.get("device_id") or "").strip()
        if not device_id:
            raise ValueError("device_id required")
        snapshot = {**snapshot, "received_at": time.time()}
        with self._lock:
            self._latest[device_id] = snapshot
            commands = self._commands.pop(device_id, [])
        self._persist(device_id, snapshot)
        return commands

    def queue_command(self, device_id: str, command: dict) -> None:
        with self._lock:
            self._commands.setdefault(device_id, []).append(command)

    def latest(self) -> list[dict]:
        now = time.time()
        with self._lock:
            snaps = list(self._latest.values())
        return [{**s, "online": now - s.get("received_at", 0) < _ONLINE_WINDOW_S} for s in snaps]

    def history(self, device_id: str, limit: int = 100) -> list[dict]:
        path = self._path(device_id)
        if not path.exists():
            return []
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()[-limit:]
            return [json.loads(ln) for ln in lines if ln.strip()][::-1]
        except Exception:  # noqa: BLE001
            return []

    def _path(self, device_id: str) -> Path:
        return TELEMETRY_DIR / f"{_SAFE_ID.sub('_', device_id)[:64]}.jsonl"

    def _persist(self, device_id: str, snapshot: dict) -> None:
        try:
            TELEMETRY_DIR.mkdir(parents=True, exist_ok=True)
            path = self._path(device_id)
            if path.exists() and path.stat().st_size > 5 * 1024 * 1024:
                path.replace(path.with_suffix(".1.jsonl"))
            with path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(snapshot, ensure_ascii=False) + "\n")
        except Exception:  # noqa: BLE001
            log.exception("telemetry persist failed")


telemetry = TelemetryRegistry()
router = APIRouter()


@router.post("/telemetry/device")
async def ingest_device(request: Request):
    # Permissive by design: never 422 the phone. Only device_id is required.
    try:
        snapshot = await request.json()
        if not isinstance(snapshot, dict):
            raise ValueError("object expected")
        commands = telemetry.ingest(snapshot)
    except ValueError as e:
        raise HTTPException(400, str(e))
    except Exception:  # noqa: BLE001
        log.exception("telemetry ingest failed")
        raise HTTPException(400, "bad snapshot")
    return {"ok": True, "commands": commands}


@router.get("/telemetry/devices")
def list_devices():
    return {"devices": telemetry.latest()}


@router.post("/telemetry/devices/{device_id}/command")
async def queue_device_command(device_id: str, request: Request):
    """Queue a command for a device; delivered in its next telemetry POST response (<=60s away
    while the app is open). e.g. {"type": "sync_books"} -> upload downloaded chapters for TTS."""
    try:
        cmd = await request.json()
        if not isinstance(cmd, dict) or not cmd.get("type"):
            raise ValueError("command object with type required")
    except ValueError as e:
        raise HTTPException(400, str(e))
    telemetry.queue_command(device_id, cmd)
    return {"ok": True}


@router.get("/telemetry/devices/{device_id}/history")
def device_history(device_id: str, limit: int = 100):
    return {"history": telemetry.history(device_id, min(limit, 1000))}
