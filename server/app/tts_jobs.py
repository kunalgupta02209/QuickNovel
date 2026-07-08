"""Async TTS batch jobs — twin of jobs.py but for audio synthesis.

A job carries one book's chapters (each a list of {key, text} sentences). Sentences already on disk
are skipped (resume). Synthesis is blocking C++ (sherpa) so it runs in a thread, bounded by a
semaphore so it never starves the LLM GPU/CPU. Audio is fetched by book identity (see main.py), not
from the job — the job is only a readiness signal.
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid

from . import history, metrics, tts_engine, tts_storage

log = logging.getLogger("tts_jobs")

# Serialize synthesis across all TTS jobs (analogue of fixer.py's _gpu_sem). Sized from config.
_sem: asyncio.Semaphore | None = None
_sem_size = 2
_in_flight = 0  # syntheses currently inside the semaphore (dashboard)


def configure(max_concurrent: int) -> None:
    global _sem, _sem_size
    _sem_size = max(1, int(max_concurrent))
    _sem = asyncio.Semaphore(_sem_size)
    tts_engine.set_pool_size(_sem_size)  # one OfflineTts per concurrent stream


def _semaphore() -> asyncio.Semaphore:
    global _sem
    if _sem is None:
        _sem = asyncio.Semaphore(_sem_size)
    return _sem


def sem_stats() -> dict:
    return {"in_flight": _in_flight, "max_concurrent": _sem_size}


class TtsJob:
    def __init__(self, book_id: str, model_id: str, sid: int, sample_rate: int, items: list[dict], num_threads: int):
        self.id = uuid.uuid4().hex[:12]
        self.book_id = book_id
        self.model_id = model_id
        self.sid = sid
        self.sample_rate = sample_rate
        self.num_threads = num_threads
        self.items = items  # [{index, sentences:[{key, text}]}]
        self.status = "queued"  # queued | running | done | cancelled | error
        self.total = sum(len(it.get("sentences") or []) for it in items)
        self.progress = 0
        self.synthesized = 0  # actually generated (excludes cache-skips) -> honest rate
        self.chapters: dict[int, dict] = {
            int(it["index"]): {"total": len(it.get("sentences") or []), "done": 0} for it in items
        }
        self.created = time.time()
        self.started: float | None = None
        self.finished: float | None = None
        self.error: str | None = None
        self._task: asyncio.Task | None = None
        self._cancel = False

    async def run(self) -> None:
        global _in_flight
        self.status = "running"
        self.started = time.time()
        log.info("tts job %s started: book=%s model=%s sid=%d sentences=%d",
                 self.id, self.book_id, self.model_id, self.sid, self.total)
        try:
            for it in self.items:
                if self._cancel:
                    self.status = "cancelled"
                    return
                index = int(it["index"])
                for sent in (it.get("sentences") or []):
                    if self._cancel:
                        self.status = "cancelled"
                        return
                    key = sent["key"]
                    if not tts_storage.exists(self.book_id, self.model_id, self.sid, index, key):
                        async with _semaphore():
                            _in_flight += 1
                            try:
                                _t0 = time.time()
                                wav = await asyncio.to_thread(
                                    tts_engine.synth_wav, self.model_id, self.sid, sent["text"], self.num_threads
                                )
                                metrics.record_tts_sentence(time.time() - _t0, len(sent.get("text") or ""))
                            finally:
                                _in_flight -= 1
                        tts_storage.write(self.book_id, self.model_id, self.sid, index, key, wav)
                        self.synthesized += 1
                    self.progress += 1
                    self.chapters[index]["done"] += 1
            self.status = "done"
            log.info("tts job %s done", self.id)
        except asyncio.CancelledError:
            self.status = "cancelled"
            raise
        except Exception as e:  # noqa: BLE001
            log.exception("tts job %s failed", self.id)
            self.status = "error"
            self.error = str(e)
        finally:
            self.finished = time.time()
            history.append({
                "kind": "tts", "id": self.id, "book_id": self.book_id, "model": self.model_id,
                "sid": self.sid, "status": self.status, "sentences": self.total,
                "done": self.progress, "synthesized": self.synthesized,
                "duration_s": round(self.finished - (self.started or self.finished), 1),
                "error": self.error,
            })

    def cancel(self) -> None:
        self._cancel = True
        if self._task and not self._task.done():
            self._task.cancel()

    def summary(self) -> dict:
        return {
            "id": self.id, "book_id": self.book_id, "model_id": self.model_id, "sid": self.sid,
            "status": self.status, "progress": self.progress, "total": self.total,
            "synthesized": self.synthesized, "created": self.created,
            "started": self.started, "finished": self.finished, "error": self.error,
        }

    def detail(self) -> dict:
        d = self.summary()
        d["ready_chapters"] = [i for i, c in self.chapters.items() if c["total"] > 0 and c["done"] >= c["total"]]
        d["chapters"] = [
            {"index": i, "done": c["done"], "total": c["total"], "ready": c["done"] >= c["total"]}
            for i, c in sorted(self.chapters.items())
        ]
        return d


class TtsJobManager:
    def __init__(self) -> None:
        self.jobs: dict[str, TtsJob] = {}

    def submit(self, book_id: str, model_id: str, sid: int, sample_rate: int, items: list[dict], num_threads: int) -> TtsJob:
        job = TtsJob(book_id, model_id, sid, sample_rate, items, num_threads)
        self.jobs[job.id] = job
        job._task = asyncio.create_task(job.run())
        return job

    def list(self) -> list[dict]:
        return [j.summary() for j in sorted(self.jobs.values(), key=lambda j: -j.created)]

    def get(self, jid: str) -> TtsJob | None:
        return self.jobs.get(jid)

    def cancel(self, jid: str) -> TtsJob | None:
        j = self.jobs.get(jid)
        if j:
            j.cancel()
        return j


tts_jobs = TtsJobManager()
