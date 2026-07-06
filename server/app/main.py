import asyncio
import logging
import os
import time

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel
from watchfiles import awatch

from .config import CONFIG_PATH, config
from .fixer import fix_text
from .jobs import jobs
from .logging_config import setup_logging
from .prompts import PROMPT_PATH, prompts

setup_logging()
log = logging.getLogger("main")
app = FastAPI(title="QuickNovel LLM Fix Server", version="1.0")


# ---- request/response models ----
class SnippetReq(BaseModel):
    text: str
    model: str | None = None
    previous_chapters: str | None = ""
    character_memory: str | None = ""


class BatchItem(BaseModel):
    id: str
    text: str
    previous_chapters: str | None = ""
    character_memory: str | None = ""


class BatchReq(BaseModel):
    model: str | None = None
    items: list[BatchItem]


# ---- request logging (point 6) ----
@app.middleware("http")
async def log_requests(request: Request, call_next):
    t0 = time.time()
    resp = await call_next(request)
    log.info("%s %s -> %s (%.0f ms)", request.method, request.url.path, resp.status_code, (time.time() - t0) * 1000)
    return resp


# ---- health / models / prompt / config ----
@app.get("/health")
def health():
    return {"ok": True, "default_model": config.default_model}


@app.get("/download/apk")
def download_apk():
    """Serve the staged QuickNovel APK for on-phone download over the tailnet."""
    path = "static/quicknovel.apk"
    if not os.path.exists(path):
        raise HTTPException(404, "apk not staged")
    return FileResponse(
        path,
        media_type="application/vnd.android.package-archive",
        filename="QuickNovel.apk",
    )


@app.get("/models")
def models():
    return {"models": config.models, "default": config.default_model}


@app.get("/prompt")
def get_prompt():
    return {"prompt": prompts.system}


@app.get("/config")
def get_config():
    return {
        "store_samples": config.store_samples,
        "default_model": config.default_model,
        "chunk_chars": config.chunk_chars,
        "ollama_base_url": config.ollama_base_url,
    }


@app.post("/config/store_samples")
def set_store_samples(on: bool):
    # Runtime toggle, no restart (point 9). Edits are in-memory; config.yaml still hot-reloads over it.
    config.set_runtime("store_samples", on)
    log.info("store_samples set to %s (runtime)", on)
    return {"store_samples": config.store_samples}


# ---- fixing ----
@app.post("/fix/snippet")
async def fix_snippet(req: SnippetReq):
    """Synchronous small-text fix (point 2)."""
    out = await fix_text(req.text, req.model, req.previous_chapters or "", req.character_memory or "")
    return {"fixed": out}


@app.post("/fix/batch")
async def fix_batch(req: BatchReq):
    """Kick off an async batch job over many chapters (point 2)."""
    if not req.items:
        raise HTTPException(400, "no items")
    job = jobs.submit(req.model or config.default_model, [i.model_dump() for i in req.items])
    return {"job_id": job.id}


# ---- jobs (point 3) ----
@app.get("/jobs")
def list_jobs():
    return {"jobs": jobs.list()}


@app.get("/jobs/{jid}")
def job_detail(jid: str):
    j = jobs.get(jid)
    if not j:
        raise HTTPException(404, "job not found")
    return j.detail()


@app.post("/jobs/{jid}/cancel")
def cancel_job(jid: str):
    j = jobs.cancel(jid)
    if not j:
        raise HTTPException(404, "job not found")
    return j.summary()


# ---- hot-reload watcher for the prompt .md and config.yaml (points 5 + 9) ----
@app.on_event("startup")
async def _startup():
    asyncio.create_task(_watch())
    log.info("server ready — default model %s", config.default_model)


async def _watch():
    watch_dirs = {str(CONFIG_PATH.resolve().parent), str(PROMPT_PATH.resolve().parent)}
    try:
        async for changes in awatch(*watch_dirs):
            for _change, path in changes:
                if path.endswith(CONFIG_PATH.name):
                    config.reload()
                    log.info("config.yaml hot-reloaded")
                elif path.endswith(PROMPT_PATH.name):
                    prompts.reload()
                    log.info("system prompt hot-reloaded")
    except Exception:
        log.exception("file watcher stopped")
