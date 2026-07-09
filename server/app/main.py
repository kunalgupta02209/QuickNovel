import asyncio
import logging
import os
import time

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, Response
from pydantic import BaseModel
from watchfiles import awatch

from . import casting, chapter_texts, charmap, dashboard, history, telemetry, tts_engine, tts_storage
from .charmap_jobs import charmap_jobs
from .config import CONFIG_PATH, config
from .fixer import fix_text
from .jobs import jobs
from .logging_config import setup_logging
from .prompts import PROMPT_PATH, prompts
from .tts_jobs import tts_jobs

setup_logging()
log = logging.getLogger("main")
app = FastAPI(title="QuickNovel LLM Fix Server", version="1.0")
app.include_router(dashboard.router)
app.include_router(telemetry.router)


# ---- request/response models ----
class SnippetReq(BaseModel):
    text: str
    model: str | None = None
    script_type: str = "grammar"  # grammar | performance
    book_id: str = ""  # enables character-map memory backfill
    previous_chapters: str | None = ""
    character_memory: str | None = ""


class BatchItem(BaseModel):
    id: str
    text: str
    previous_chapters: str | None = ""
    character_memory: str | None = ""


class BatchReq(BaseModel):
    model: str | None = None
    script_type: str = "grammar"
    book_id: str = ""  # enables character-map memory backfill + performance-script casting
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
    out = []
    for m in config.models:
        kind = m.get("kind") or "local"
        available = True
        if kind == "cloud":
            available = bool(config.api_key_for(m.get("id")))
        out.append({**m, "kind": kind, "available": available})
    return {"models": out, "default": config.default_model, "tasks": config.get("tasks", {}) or {}}


@app.get("/prompt")
def get_prompt(script_type: str = "grammar"):
    return {"prompt": prompts.for_script(script_type), "script_type": script_type}


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
    """Synchronous small-text fix; script_type=performance returns cue-annotated span JSON too."""
    memory = req.character_memory or ""
    if not memory and req.book_id:
        try:
            memory = charmap.prompt_block(req.book_id)
        except Exception:  # noqa: BLE001
            memory = ""
    res = await fix_text(
        req.text, req.model, req.previous_chapters or "", memory,
        script_type=req.script_type,
    )
    return {"fixed": res["fixed"], "paragraphs": res.get("paragraphs"), "script_type": req.script_type}


@app.post("/fix/batch")
async def fix_batch(req: BatchReq):
    """Kick off an async batch job over many chapters (point 2)."""
    if not req.items:
        raise HTTPException(400, "no items")
    job = jobs.submit(req.model, [i.model_dump() for i in req.items], req.script_type, req.book_id)
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


@app.post("/jobs/{jid}/pause")
def pause_job(jid: str):
    j = jobs.pause(jid)
    if not j:
        raise HTTPException(404, "job not found")
    return j.summary()


@app.post("/jobs/{jid}/resume")
def resume_job(jid: str):
    j = jobs.resume(jid)
    if not j:
        raise HTTPException(404, "job not found")
    return j.summary()


# ---- server-side TTS offload ----
class TtsSentence(BaseModel):
    key: str
    text: str
    sid: int | None = None      # per-sentence voice override (P5 casting)
    speed: float | None = None  # per-sentence pace override


class TtsChapter(BaseModel):
    index: int
    name: str = ""  # human-readable chapter title (dashboard)
    sentences: list[TtsSentence]


class TtsBatchReq(BaseModel):
    book_id: str
    book_name: str = ""  # human-readable book title (dashboard)
    api_name: str = ""   # provider identity (book sync between devices)
    author: str = ""
    model_id: str
    sid: int = 0
    sample_rate: int = 24000
    device_id: str = ""    # submitting device (dashboard attribution; cancel decisions stay informed)
    device_name: str = ""
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
async def tts_batch(req: TtsBatchReq):
    """Submit a book's chapters for server-side synthesis; audio is fetched by book identity.
    Must be async: tts_jobs.submit calls asyncio.create_task, which needs the running event loop."""
    defn = tts_engine.MODELS.get(req.model_id)
    if defn is None:
        raise HTTPException(400, f"unknown model {req.model_id}")
    if req.sid < 0 or req.sid >= defn.speakers:
        raise HTTPException(400, f"sid {req.sid} out of range for {req.model_id}")
    if not req.items:
        raise HTTPException(400, "no items")
    items = [i.model_dump() for i in req.items]
    # Side effects: persist chapter texts (charmap/search/book-sync source) + delta-build the map.
    try:
        chapter_texts.store_batch(req.book_id, req.book_name, items, req.api_name, req.author)
        charmap_jobs.maybe_auto_build(req.book_id)
    except Exception:  # noqa: BLE001
        log.exception("chapter_texts/charmap hook failed")
    job = tts_jobs.submit(
        req.book_id, req.model_id, req.sid, req.sample_rate,
        items, config.tts_num_threads, req.book_name,
        req.device_id, req.device_name,
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


@app.post("/tts/jobs/{jid}/pause")
def tts_pause_job(jid: str):
    j = tts_jobs.pause(jid)
    if not j:
        raise HTTPException(404, "job not found")
    return j.summary()


@app.post("/tts/jobs/{jid}/resume")
def tts_resume_job(jid: str):
    j = tts_jobs.resume(jid)
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


# ---- character maps (P3) ----
class CharmapBuildReq(BaseModel):
    book_id: str
    start: int = 0
    end: int = -1  # -1 = all stored chapters
    cast_model: str = "kokoro"


@app.post("/charmap/build")
async def charmap_build(req: CharmapBuildReq):
    idxs = chapter_texts.chapter_indices(req.book_id)
    if not idxs:
        raise HTTPException(404, "no stored chapters for this book (sync it via /tts/batch first)")
    end = req.end if req.end >= 0 else max(idxs)
    job = charmap_jobs.submit(req.book_id, req.start, end, req.cast_model)
    return {"job_id": job.id}


@app.get("/charmap")
def charmap_list():
    return {"maps": charmap.list_maps()}


@app.get("/charmap/jobs")
def charmap_list_jobs():
    return {"jobs": charmap_jobs.list()}


@app.post("/charmap/jobs/{jid}/cancel")
def charmap_cancel(jid: str):
    j = charmap_jobs.cancel(jid) or _404()
    return j.summary()


@app.post("/charmap/jobs/{jid}/pause")
def charmap_pause(jid: str):
    j = charmap_jobs.pause(jid) or _404()
    return j.summary()


@app.post("/charmap/jobs/{jid}/resume")
def charmap_resume(jid: str):
    j = charmap_jobs.resume(jid) or _404()
    return j.summary()


def _404():
    raise HTTPException(404, "job not found")


@app.get("/charmap/{book_id}")
def charmap_get(book_id: str, through_chapter: int | None = None):
    m = charmap.load_map(book_id)
    if not m:
        raise HTTPException(404, "no map for this book")
    if through_chapter is not None:  # spoiler gate: rebuild the view from extractions
        m = charmap.merge_extractions(book_id, through_chapter)
        casting.assign(m)
    return m


@app.get("/charmap/{book_id}/search")
def charmap_search(book_id: str, q: str, through_chapter: int | None = None):
    """Reader lookup: previous occurrences of a character + how they interacted + world/locations."""
    if not charmap.load_map(book_id):
        raise HTTPException(404, "no map for this book")
    return charmap.search(book_id, q, through_chapter)


class CastingPatch(BaseModel):
    characters: dict[str, dict]  # char id -> partial casting {sid?, model?, pitch?, speed?, locked?}


@app.patch("/charmap/{book_id}/casting")
def charmap_patch_casting(book_id: str, patch: CastingPatch):
    m = charmap.load_map(book_id)
    if not m:
        raise HTTPException(404, "no map for this book")
    for c in m.get("characters") or []:
        upd = patch.characters.get(c["id"])
        if upd:
            c["casting"] = {**(c.get("casting") or {}), **upd, "locked": upd.get("locked", True)}
    m.setdefault("casting_meta", {})["cast_version"] = \
        int((m.get("casting_meta") or {}).get("cast_version", 0)) + 1
    charmap.save_map(book_id, m)
    return {"ok": True, "cast_version": m["casting_meta"]["cast_version"]}


@app.post("/charmap/{book_id}/recast")
def charmap_recast(book_id: str, cast_model: str = "kokoro"):
    m = charmap.load_map(book_id)
    if not m:
        raise HTTPException(404, "no map for this book")
    casting.assign(m, cast_model)
    charmap.save_map(book_id, m)
    return {"ok": True, "cast_version": m["casting_meta"]["cast_version"]}


# ---- server-side generation from stored chapters (dashboard book management) ----
class ScriptsGenReq(BaseModel):
    book_id: str
    script_type: str = "performance"  # performance | grammar
    start: int = 0
    end: int = -1  # -1 = all stored chapters
    model: str | None = None


@app.post("/scripts/generate")
async def scripts_generate(req: ScriptsGenReq):
    """Generate the PERFORMANCE script for a book entirely server-side (from stored chapter texts).
    Runs as a normal /fix job (script_type=performance) so pause/cancel/dashboard all apply; the
    charmap CAST backfill drives the speakers; ScriptDocs persist under data/scripts/."""
    idxs = chapter_texts.chapter_indices(req.book_id)
    if not idxs:
        raise HTTPException(404, "no stored chapters for this book")
    end = req.end if req.end >= 0 else max(idxs)
    have = {int(f.stem[1:]) for f in
            (charmap.ROOT.parent / "scripts" / req.book_id / "performance").glob("c*.json")} \
        if (charmap.ROOT.parent / "scripts" / req.book_id / "performance").is_dir() else set()
    items = []
    for i in idxs:
        if req.start <= i <= end and i not in have:
            text = chapter_texts.get_text(req.book_id, i)
            if text:
                items.append({"id": str(i), "text": text})
    if not items:
        return {"job_id": None, "skipped": "all requested chapters already have scripts"}
    job = jobs.submit(req.model, items, req.script_type, req.book_id)
    return {"job_id": job.id, "chapters": len(items), "script_type": req.script_type}


@app.get("/scripts/{book_id}")
def scripts_list(book_id: str):
    d = charmap.ROOT.parent / "scripts" / book_id / "performance"
    idxs = sorted(int(f.stem[1:]) for f in d.glob("c*.json")) if d.is_dir() else []
    return {"book_id": book_id, "script_type": "performance", "chapters": idxs}


@app.get("/scripts/{book_id}/performance/c{index}.json")
def scripts_get(book_id: str, index: int):
    f = charmap.ROOT.parent / "scripts" / book_id / "performance" / f"c{index}.json"
    if not f.exists():
        raise HTTPException(404, "no script for this chapter")
    return Response(content=f.read_bytes(), media_type="application/json")


@app.get("/scripts/{book_id}/grammar/c{index}.txt")
def scripts_get_grammar(book_id: str, index: int):
    f = charmap.ROOT.parent / "scripts" / book_id / "grammar" / f"c{index}.txt"
    if not f.exists():
        raise HTTPException(404, "no grammar fix for this chapter")
    return Response(content=f.read_bytes(), media_type="text/plain; charset=utf-8")


class TtsGenReq(BaseModel):
    book_id: str
    model_id: str = "kitten"
    sid: int = 0
    start: int = 0
    end: int = -1


@app.post("/tts/generate")
async def tts_generate(req: TtsGenReq):
    """Server-side TTS generation from stored chapter texts — no device needed. Stored text is
    line-per-sentence exactly as the app submitted it, so sha1(line) reproduces the app's cache
    keys: devices later pull this audio as instant cache hits."""
    import hashlib
    idxs = chapter_texts.chapter_indices(req.book_id)
    if not idxs:
        raise HTTPException(404, "no stored chapters for this book")
    end = req.end if req.end >= 0 else max(idxs)
    meta = chapter_texts.meta(req.book_id)
    items = []
    for i in idxs:
        if not (req.start <= i <= end):
            continue
        text = chapter_texts.get_text(req.book_id, i) or ""
        sentences = [
            {"key": hashlib.sha1(ln.encode("utf-8")).hexdigest()[:24], "text": ln}
            for ln in text.splitlines() if ln.strip()
        ]
        if sentences:
            items.append({"index": i, "name": (meta.get("chapters") or {}).get(str(i), ""),
                          "sentences": sentences})
    if not items:
        raise HTTPException(400, "no synthesizable sentences in range")
    job = tts_jobs.submit(
        req.book_id, req.model_id, req.sid, 24000, items, config.tts_num_threads,
        meta.get("book_name") or req.book_id, "dashboard", "web dashboard",
    )
    return {"job_id": job.id, "chapters": len(items)}


# ---- book sync between devices (server-held chapter texts) ----
@app.get("/books")
def books_list():
    books = chapter_texts.list_books()
    # enrich with map/script/audio presence so the dashboard can render management state
    for b in books:
        bid = b["book_id"]
        m = charmap.load_map(bid)
        b["map_characters"] = len(m.get("characters") or []) if m else 0
        b["map_through"] = m.get("through_chapter", -1) if m else -1
        d = charmap.ROOT.parent / "scripts" / bid / "performance"
        b["script_chapters"] = len(list(d.glob("c*.json"))) if d.is_dir() else 0
        b["audio_voices"] = tts_storage.voices_for(bid)
    return {"books": books}


@app.get("/books/{book_id}/chapters.zip")
def book_chapters_zip(book_id: str):
    import io
    import zipfile

    idxs = chapter_texts.chapter_indices(book_id)
    if not idxs:
        raise HTTPException(404, "no chapters stored")
    meta = chapter_texts.meta(book_id)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("meta.json", __import__("json").dumps(
            {**meta, "book_id": book_id, "chapters_count": len(idxs), "max_index": max(idxs)}))
        for i in idxs:
            t = chapter_texts.get_text(book_id, i)
            if t:
                zf.writestr(f"{i}.txt", t)
    return Response(content=buf.getvalue(), media_type="application/zip")


# ---- hot-reload watcher for the prompt .md and config.yaml (points 5 + 9) ----
@app.on_event("startup")
async def _startup():
    history.on_server_start()
    try:
        from . import job_store
        job_store.resume_all()
    except Exception:  # noqa: BLE001
        log.exception("job resume failed")
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
                elif path.endswith(".md") and "prompts" in path:
                    prompts.reload()
                    log.info("prompts hot-reloaded (%s)", path.rsplit("/", 1)[-1])
    except Exception:
        log.exception("file watcher stopped")
