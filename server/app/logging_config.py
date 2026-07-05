import logging
import logging.handlers
import os
from pathlib import Path


def setup_logging() -> None:
    """Console + rotating file logging so the server is debuggable (point 6)."""
    Path("logs").mkdir(exist_ok=True)
    level = os.environ.get("LOG_LEVEL", "INFO").upper()
    fmt = "%(asctime)s %(levelname)-5s %(name)s: %(message)s"
    handlers = [
        logging.StreamHandler(),
        logging.handlers.RotatingFileHandler(
            "logs/server.log", maxBytes=5_000_000, backupCount=3, encoding="utf-8"
        ),
    ]
    logging.basicConfig(level=level, format=fmt, handlers=handlers, force=True)
    # LiteLLM is very chatty at INFO; keep it at WARNING unless debugging.
    logging.getLogger("LiteLLM").setLevel(os.environ.get("LITELLM_LOG", "WARNING").upper())
    logging.getLogger("httpx").setLevel("WARNING")
