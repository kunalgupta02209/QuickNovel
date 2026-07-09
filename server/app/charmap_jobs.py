"""Character-map build jobs — extraction over a chapter range (cached per chapter, so re-runs only
pay for new chapters), then a deterministic merge + casting. Pause/resume like the other managers."""

from __future__ import annotations

import asyncio
import logging
import time
import uuid

from . import casting, chapter_texts, charmap, history

log = logging.getLogger("charmap_jobs")


class CharmapJob:
    def __init__(self, book_id: str, start: int, end: int, cast_model: str = "kokoro",
                 jid: str | None = None):
        self.id = jid or uuid.uuid4().hex[:12]
        self.book_id = book_id
        self.start = start
        self.end = end
        self.cast_model = cast_model
        self.status = "queued"
        self.progress = 0
        self.total = max(end - start + 1, 0)
        self.created = time.time()
        self.started: float | None = None
        self.finished: float | None = None
        self.error: str | None = None
        self._task: asyncio.Task | None = None
        self._cancel = False
        self._pause = asyncio.Event()
        self._pause.set()

    async def run(self) -> None:
        self.status = "running"
        self.started = time.time()
        book_name = chapter_texts.meta(self.book_id).get("book_name") or self.book_id
        log.info("charmap job %s: '%s' chapters %d..%d", self.id, book_name, self.start, self.end)
        try:
            for idx in range(self.start, self.end + 1):
                if not self._pause.is_set():
                    self.status = "paused"
                    await self._pause.wait()
                    if not self._cancel:
                        self.status = "running"
                if self._cancel:
                    self.status = "cancelled"
                    return
                await charmap.extract_chapter(self.book_id, idx)
                self.progress += 1
            m = charmap.merge_extractions(self.book_id)
            casting.assign(m, self.cast_model)
            # keep prior locked casting choices across rebuilds
            prior = charmap.load_map(self.book_id)
            if prior:
                locked = {c["id"]: c.get("casting") for c in prior.get("characters") or []
                          if (c.get("casting") or {}).get("locked")}
                for c in m.get("characters") or []:
                    if c["id"] in locked:
                        c["casting"] = locked[c["id"]]
            charmap.save_map(self.book_id, m)
            self.status = "done"
            log.info("charmap job %s done: %d characters", self.id, len(m.get("characters") or []))
        except asyncio.CancelledError:
            self.status = "cancelled"
            raise
        except Exception as e:  # noqa: BLE001
            log.exception("charmap job %s failed", self.id)
            self.status = "error"
            self.error = str(e)
        finally:
            self.finished = time.time()
            from . import job_store
            job_store.remove("charmap", self.id)
            history.append({
                "kind": "llm", "task": "character_summary", "id": self.id, "model": "charmap",
                "status": self.status, "items": self.total, "done": self.progress,
                "duration_s": round(self.finished - (self.started or self.finished), 1),
                "error": self.error,
            })

    def cancel(self) -> None:
        self._cancel = True
        self._pause.set()
        if self._task and not self._task.done():
            self._task.cancel()

    def pause(self) -> None:
        self._pause.clear()

    def resume(self) -> None:
        self._pause.set()
        if self.status == "paused":
            self.status = "running"

    def summary(self) -> dict:
        return {
            "id": self.id, "book_id": self.book_id, "status": self.status,
            "progress": self.progress, "total": self.total, "created": self.created,
            "started": self.started, "finished": self.finished, "error": self.error,
        }


class CharmapJobManager:
    def __init__(self) -> None:
        self.jobs: dict[str, CharmapJob] = {}

    def submit(self, book_id: str, start: int, end: int, cast_model: str = "kokoro",
               jid: str | None = None) -> CharmapJob:
        job = CharmapJob(book_id, start, end, cast_model, jid=jid)
        self.jobs[job.id] = job
        from . import job_store
        job_store.save("charmap", job.id, {"book_id": book_id, "start": start, "end": end,
                                           "cast_model": cast_model})
        job._task = asyncio.create_task(job.run())
        return job

    def running_for(self, book_id: str) -> bool:
        return any(j.book_id == book_id and j.status in ("queued", "running", "paused")
                   for j in self.jobs.values())

    def maybe_auto_build(self, book_id: str) -> None:
        """After a /tts/batch stores chapters: extract only the DELTA (new chapters), if enabled.
        Auto-extraction is CAPPED (cost guard — a 1000+-chapter book must not fire 1000+ cloud
        calls): the earliest chapters carry the character introductions, so the cap covers the
        first N; a manual POST /charmap/build extracts any range explicitly."""
        cfg = config_charmap()
        if not cfg.get("auto_build", True) or not book_id:
            return
        if self.running_for(book_id):
            return
        cap = int(cfg.get("auto_build_max_chapters", 40))
        have = set(charmap.extraction_indices(book_id))
        avail = chapter_texts.chapter_indices(book_id)
        missing = [i for i in avail if i not in have and i < cap]
        if missing:
            self.submit(book_id, min(missing), max(missing))
            log.info("charmap auto-build queued for %s (%d new chapters, cap %d)",
                     book_id, len(missing), cap)

    def list(self) -> list[dict]:
        return [j.summary() for j in sorted(self.jobs.values(), key=lambda j: -j.created)]

    def get(self, jid: str):
        return self.jobs.get(jid)

    def cancel(self, jid: str):
        j = self.jobs.get(jid)
        if j:
            j.cancel()
        return j

    def pause(self, jid: str):
        j = self.jobs.get(jid)
        if j:
            j.pause()
        return j

    def resume(self, jid: str):
        j = self.jobs.get(jid)
        if j:
            j.resume()
        return j


def config_charmap() -> dict:
    from .config import config

    return config.get("charmap", {}) or {}


charmap_jobs = CharmapJobManager()
