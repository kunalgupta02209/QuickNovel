import logging
import os
import threading
from pathlib import Path

import yaml

log = logging.getLogger("config")
CONFIG_PATH = Path(os.environ.get("CONFIG_PATH", "config.yaml"))


class Config:
    """Hot-reloadable server config (point 9: flags toggle without a restart)."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._data: dict = {}
        self.reload()

    def reload(self) -> None:
        with self._lock:
            if CONFIG_PATH.exists():
                self._data = yaml.safe_load(CONFIG_PATH.read_text(encoding="utf-8")) or {}
                log.info("config loaded from %s", CONFIG_PATH)
            elif not self._data:
                # fall back to the example so the server still starts
                ex = CONFIG_PATH.with_name("config.example.yaml")
                if ex.exists():
                    self._data = yaml.safe_load(ex.read_text(encoding="utf-8")) or {}
                    log.warning("config.yaml missing; using config.example.yaml")

    def get(self, key, default=None):
        with self._lock:
            return self._data.get(key, default)

    def set_runtime(self, key, value) -> None:
        """In-memory override (survives until the next file reload) for API toggles."""
        with self._lock:
            self._data[key] = value

    @property
    def models(self):
        return self.get("models", []) or []

    @property
    def default_model(self):
        return self.get("default_model") or (self.models[0]["id"] if self.models else None)

    @property
    def store_samples(self) -> bool:
        return bool(self.get("store_samples", False))

    @property
    def ollama_base_url(self):
        # Env wins so the same config.yaml works locally (localhost) and in Docker
        # (compose sets OLLAMA_BASE_URL=http://host.docker.internal:11434).
        return os.environ.get("OLLAMA_BASE_URL") or self.get("ollama_base_url")

    @property
    def chunk_chars(self) -> int:
        return int(self.get("chunk_chars", 2200))

    @property
    def num_ctx(self) -> int:
        return int(self.get("num_ctx", 2048))

    @property
    def send_previous_chapters(self) -> bool:
        return bool(self.get("send_previous_chapters", False))

    @property
    def sampling(self) -> dict:
        return {
            "temperature": float(self.get("temperature", 0.4)),
            "frequency_penalty": float(self.get("frequency_penalty", 0.3)),
            "presence_penalty": float(self.get("presence_penalty", 0.1)),
        }

    # ---- server-side TTS offload ----
    @property
    def tts(self) -> dict:
        return self.get("tts", {}) or {}

    @property
    def tts_num_threads(self) -> int:
        return int(self.tts.get("num_threads", 4))

    @property
    def tts_max_concurrent(self) -> int:
        return int(self.tts.get("max_concurrent", 2))

    @property
    def tts_preload(self) -> list:
        return self.tts.get("preload", []) or []

    def litellm_for(self, model_id):
        for m in self.models:
            if m.get("id") == model_id:
                return m.get("litellm")
        return model_id  # allow passing a raw litellm string too

    # ---- cloud (OpenCode) routing ----
    def model_entry(self, model_id) -> dict | None:
        for m in self.models:
            if m.get("id") == model_id:
                return m
        return None

    def is_cloud(self, model_id) -> bool:
        return (self.model_entry(model_id) or {}).get("kind") == "cloud"

    @property
    def opencode(self) -> dict:
        return self.get("opencode", {}) or {}

    def api_base_for(self, model_id) -> str | None:
        entry = self.model_entry(model_id) or {}
        return entry.get("api_base") or (self.opencode.get("api_base") if entry.get("kind") == "cloud" else None)

    def api_key_for(self, model_id) -> str | None:
        entry = self.model_entry(model_id) or {}
        env = entry.get("api_key_env") or self.opencode.get("api_key_env") or ""
        return os.environ.get(env) or None if env else None

    def task_model(self, task: str) -> dict:
        """{"model": id, "fallback": id|None} for a routing task; default_model when unconfigured."""
        t = (self.get("tasks", {}) or {}).get(task) or {}
        return {"model": t.get("model") or self.default_model, "fallback": t.get("fallback")}

    @property
    def cloud_budget(self) -> dict:
        b = self.get("cloud_budget", {}) or {}
        return {
            "daily_usd_cap": float(b.get("daily_usd_cap", 0.0)),
            "on_cap": b.get("on_cap", "fallback"),
            "cooldown_s": int(b.get("cooldown_s", 600)),
            "quota_cooldown_s": int(b.get("quota_cooldown_s", 1800)),
        }


config = Config()
