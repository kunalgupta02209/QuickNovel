import logging
import os
import threading
from pathlib import Path

log = logging.getLogger("prompts")
PROMPT_PATH = Path(os.environ.get("PROMPT_PATH", "prompts/system_prompt.md"))
PROMPTS_DIR = PROMPT_PATH.parent

_FALLBACK = (
    "You are a literary editor. Rewrite the machine-translated chapter into clean, natural English. "
    "Fix grammar and pronoun gender, preserve all names and plot beats, output plain prose only."
)

# script_type -> prompt file stem
SCRIPT_PROMPTS = {"grammar": "system_prompt", "performance": "performance_prompt"}


class Prompts:
    """Named prompt registry: every prompts/*.md is loaded by stem and hot-reloaded on change.
    `system_prompt` keeps its legacy filename/accessor (= the grammar prompt)."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._texts: dict[str, str] = {}
        self.reload()

    def reload(self) -> None:
        with self._lock:
            try:
                for f in PROMPTS_DIR.glob("*.md"):
                    self._texts[f.stem] = f.read_text(encoding="utf-8").strip()
                log.info("prompts reloaded: %s", sorted(self._texts))
            except Exception:  # noqa: BLE001
                log.exception("prompt reload failed")

    def get(self, name: str) -> str:
        with self._lock:
            return self._texts.get(name) or _FALLBACK

    def for_script(self, script_type: str) -> str:
        return self.get(SCRIPT_PROMPTS.get(script_type, "system_prompt"))

    @property
    def system(self) -> str:  # legacy accessor (grammar prompt)
        return self.get("system_prompt")


prompts = Prompts()
