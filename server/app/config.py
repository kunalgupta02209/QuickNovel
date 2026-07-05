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
        return self.get("ollama_base_url")

    @property
    def chunk_chars(self) -> int:
        return int(self.get("chunk_chars", 4000))

    @property
    def sampling(self) -> dict:
        return {
            "temperature": float(self.get("temperature", 0.4)),
            "frequency_penalty": float(self.get("frequency_penalty", 0.3)),
            "presence_penalty": float(self.get("presence_penalty", 0.1)),
        }

    def litellm_for(self, model_id):
        for m in self.models:
            if m.get("id") == model_id:
                return m.get("litellm")
        return model_id  # allow passing a raw litellm string too


config = Config()
