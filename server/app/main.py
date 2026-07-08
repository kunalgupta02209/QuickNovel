import asyncio
import logging
import os
import time

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, Response
from pydantic import BaseModel
from watchfiles import awatch

from . import tts_engine, tts_storage
from .config import CONFIG_PATH, config
from .fixer import fix_text
from .jobs import jobs
from .logging_config import setup_logging
from .prompts import PROMPT_PATH, prompts
from .tts_jobs import tts_jobs

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


# ---- server-side TTS offload ----
class TtsSentence(BaseModel):
    key: str
    text: str


class TtsChapter(BaseModel):
    index: int
    sentences: list[TtsSentence]


class TtsBatchReq(BaseModel):
    book_id: str
    model_id: str
    sid: int = 0
    sample_rate: int = 24000
    items: list[TtsChapter]


@app.get("/tts/health")
def tts_health():
    return {"ok": True}


@app.get("/tts/models")
def tts_models():
    return {
        "models": [
            {"id": m.id, "kind": m.kind, "speakers": m.speakers,
             "sample_rate": m.sample_rate, "ready": tts_engine.is_ready(m)}
            for m in tts_engine.MODELS.values()
        ],
    }


@app.post("/tts/batch")
def tts_batch(req: TtsBatchReq):
    """Submit a book's chapters for server-side synthesis; audio is fetched by book identity."""
    defn = tts_engine.MODELS.get(req.model_id)
    if defn is None:
        raise HTTPException(400, f"unknown model {req.model_id}")
    if req.sid < 0 or req.sid >= defn.speakers:
        raise HTTPException(400, f"sid {req.sid} out of range for {req.model_id}")
    if not req.items:
        raise HTTPException(400, "no items")
    job = tts_jobs.submit(
        req.book_id, req.model_id, req.sid, req.sample_rate,
        [i.model_dump() for i in req.items], config.tts_num_threads,
    )
    return {"job_id": job.id}


@app.get("/tts/jobs")
def tts_list_jobs():
    return {"jobs": tts_jobs.list()}


@app.get("/tts/jobs/{jid}")
def tts_job_detail(jid: str):
    j = tts_jobs.get(jid)
    if not j:
        raise HTTPException(404, "job not found")
    return j.detail()


@app.post("/tts/jobs/{jid}/cancel")
def tts_cancel_job(jid: str):
    j = tts_jobs.cancel(jid)
    if not j:
        raise HTTPException(404, "job not found")
    return j.summary()


@app.get("/tts/audio/{book_id}/{model_id}/{sid}/{index}/manifest")
def tts_manifest(book_id: str, model_id: str, sid: int, index: int):
    return {"keys": tts_storage.chapter_keys(book_id, model_id, sid, index),
            "count": len(tts_storage.chapter_keys(book_id, model_id, sid, index))}


@app.get("/tts/audio/{book_id}/{model_id}/{sid}/{index}/{key}.wav")
def tts_audio_one(book_id: str, model_id: str, sid: int, index: int, key: str):
    path = tts_storage.wav_path(book_id, model_id, sid, index, key)
    if not path.exists():
        raise HTTPException(404, "not generated")
    return FileResponse(str(path), media_type="audio/wav")


@app.get("/tts/audio/{book_id}/{model_id}/{sid}/{index}.zip")
def tts_audio_zip(book_id: str, model_id: str, sid: int, index: int):
    if not tts_storage.has_any(book_id, model_id, sid, index):
        raise HTTPException(404, "chapter not ready")
    data = tts_storage.zip_chapter(book_id, model_id, sid, index)
    return Response(content=data, media_type="application/zip")


# ---- hot-reload watcher for the prompt .md and config.yaml (points 5 + 9) ----
@app.on_event("startup")
async def _startup():
    asyncio.create_task(_watch())
    # Size the TTS synthesis semaphore + optionally pre-provision models (download only, no engine).
    from .tts_jobs import configure as _tts_configure
    _tts_configure(config.tts_max_concurrent)
    for mid in config.tts_preload:
        defn = tts_engine.MODELS.get(mid)
        if defn is not None and not tts_engine.is_ready(defn):
            asyncio.create_task(asyncio.to_thread(tts_engine.ensure_model, defn))
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
