import json
import logging
import threading
import time
from pathlib import Path

from .config import config

log = logging.getLogger("storage")
SAMPLES_PATH = Path("samples/samples.jsonl")
_lock = threading.Lock()


def store(model: str, input_text: str, output_text: str, meta: dict | None = None) -> None:
    """Append an input/output pair for later testing — only when store_samples is on (point 9)."""
    if not config.store_samples:
        return
    try:
        SAMPLES_PATH.parent.mkdir(parents=True, exist_ok=True)
        rec = {"ts": time.time(), "model": model, "input": input_text, "output": output_text}
        if meta:
            rec["meta"] = meta
        with _lock, SAMPLES_PATH.open("a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    except Exception:
        log.exception("failed to store sample")
