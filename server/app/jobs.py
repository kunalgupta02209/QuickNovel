import asyncio
import logging
import time
import uuid

from . import history
from .fixer import fix_text

log = logging.getLogger("jobs")


class Job:
    def __init__(self, model: str, items: list[dict]):
        self.id = uuid.uuid4().hex[:12]
        self.model = model
        self.items = items  # [{id, text}]
        self.status = "queued"  # queued | running | done | cancelled | error
        self.progress = 0
        self.total = len(items)
        self.results: dict[str, str] = {}  # item id -> fixed text
        self.created = time.time()
        self.started: float | None = None
        self.finished: float | None = None
        self.chars_in = sum(len(i.get("text") or "") for i in items)
        self.chars_out = 0
        self.error: str | None = None
        self.current_chunk = ""
        self._task: asyncio.Task | None = None
        self._cancel = False

    async def run(self) -> None:
        self.status = "running"
        self.started = time.time()
        log.info("job %s started: %d items, model=%s", self.id, self.total, self.model)
        try:
            for it in self.items:
                if self._cancel:
                    self.status = "cancelled"
                    log.info("job %s cancelled at %d/%d", self.id, self.progress, self.total)
                    return
                fixed = await fix_text(
                    it["text"],
                    self.model,
                    previous_chapters=it.get("previous_chapters") or "",
                    character_memory=it.get("character_memory") or "",
                    on_chunk=lambda i, n, out: setattr(self, "current_chunk", f"chunk {i + 1}/{n}"),
                )
                self.results[str(it["id"])] = fixed
                self.chars_out += len(fixed)
                self.progress += 1
            self.status = "done"
            log.info("job %s done", self.id)
        except asyncio.CancelledError:
            self.status = "cancelled"
            raise
        except Exception as e:  # noqa: BLE001
            log.exception("job %s failed", self.id)
            self.status = "error"
            self.error = str(e)
        finally:
            self.finished = time.time()
            self.current_chunk = ""
            history.append({
                "kind": "llm", "id": self.id, "model": self.model, "status": self.status,
                "items": self.total, "done": self.progress,
                "chars_in": self.chars_in, "chars_out": self.chars_out,
                "duration_s": round(self.finished - (self.started or self.finished), 1),
                "error": self.error,
            })

    def cancel(self) -> None:
        self._cancel = True
        if self._task and not self._task.done():
            self._task.cancel()

    def summary(self) -> dict:
        return {
            "id": self.id,
            "model": self.model,
            "status": self.status,
            "progress": self.progress,
            "total": self.total,
            "created": self.created,
            "started": self.started,
            "finished": self.finished,
            "current_chunk": self.current_chunk,
            "error": self.error,
        }

    def detail(self) -> dict:
        d = self.summary()
        d["results"] = [{"id": k, "fixed": v} for k, v in self.results.items()]
        return d


class JobManager:
    def __init__(self) -> None:
        self.jobs: dict[str, Job] = {}

    def submit(self, model: str, items: list[dict]) -> Job:
        job = Job(model, items)
        self.jobs[job.id] = job
        job._task = asyncio.create_task(job.run())
        return job

    def list(self) -> list[dict]:
        return [j.summary() for j in sorted(self.jobs.values(), key=lambda j: -j.created)]

    def get(self, jid: str) -> Job | None:
        return self.jobs.get(jid)

    def cancel(self, jid: str) -> Job | None:
        j = self.jobs.get(jid)
        if j:
            j.cancel()
        return j


jobs = JobManager()
