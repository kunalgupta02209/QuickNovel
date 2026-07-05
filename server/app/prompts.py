import logging
import os
import threading
from pathlib import Path

log = logging.getLogger("prompts")
PROMPT_PATH = Path(os.environ.get("PROMPT_PATH", "prompts/system_prompt.md"))

_FALLBACK = (
    "You are a literary editor. Rewrite the machine-translated chapter into clean, natural English. "
    "Fix grammar and pronoun gender, preserve all names and plot beats, output plain prose only."
)


class Prompts:
    """The system prompt lives in an .md file and is hot-reloaded on change (point 5)."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._text = _FALLBACK
        self.reload()

    def reload(self) -> None:
        with self._lock:
            if PROMPT_PATH.exists():
                self._text = PROMPT_PATH.read_text(encoding="utf-8").strip()
                log.info("system prompt reloaded (%d chars)", len(self._text))

    @property
    def system(self) -> str:
        with self._lock:
            return self._text


prompts = Prompts()
