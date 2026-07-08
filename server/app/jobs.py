import asyncio
import logging
import time
import uuid

from . import history
from .fixer import fix_text

log = logging.getLogger("jobs")


class Job:
    def __init__(self, model: str | None, items: list[dict], script_type: str = "grammar", book_id: str = ""):
        self.id = uuid.uuid4().hex[:12]
        self.model = model or "auto"  # "auto" -> fix_text routes via the script task
        self.script_type = script_type  # grammar | performance
        self.book_id = book_id  # enables charmap memory backfill
        self.items = items  # [{id, text}]
        self.status = "queued"  # queued | running | paused | done | cancelled | error
        self.progress = 0
        self.total = len(items)
        self.results: dict[str, str] = {}       # item id -> fixed display text
        self.paragraphs: dict[str, list] = {}   # item id -> span JSON (performance only)
        self.created = time.time()
        self.started: float | None = None
        self.finished: float | None = None
        self.chars_in = sum(len(i.get("text") or "") for i in items)
        self.chars_out = 0
        self.error: str | None = None
        self.current_chunk = ""
        self._task: asyncio.Task | None = None
        self._cancel = False
        self._pause = asyncio.Event()
        self._pause.set()  # set = running

    async def run(self) -> None:
        self.status = "running"
        self.started = time.time()
        log.info("job %s started: %d items, model=%s script=%s", self.id, self.total, self.model, self.script_type)
        try:
            for it in self.items:
                if not self._pause.is_set():
                    self.status = "paused"
                    await self._pause.wait()
                    if not self._cancel:
                        self.status = "running"
                if self._cancel:
                    self.status = "cancelled"
                    log.info("job %s cancelled at %d/%d", self.id, self.progress, self.total)
                    return
                # Backfill character memory from the server-side map when the app sent none —
                # this alone resurrects the character-consistency feature with zero app change.
                memory = it.get("character_memory") or ""
                if not memory and self.book_id:
                    try:
                        from . import charmap
                        memory = charmap.prompt_block(self.book_id, int(it.get("id", -1)) if str(it.get("id", "")).lstrip("-").isdigit() else None)
                    except Exception:  # noqa: BLE001
                        memory = ""
                res = await fix_text(
                    it["text"],
                    None if self.model == "auto" else self.model,
                    previous_chapters=it.get("previous_chapters") or "",
                    character_memory=memory,
                    on_chunk=lambda i, n, out: setattr(self, "current_chunk", f"chunk {i + 1}/{n}"),
                    script_type=self.script_type,
                    pause_event=self._pause,
                )
                self.results[str(it["id"])] = res["fixed"]
                if res.get("paragraphs") is not None:
                    self.paragraphs[str(it["id"])] = res["paragraphs"]
                    # server-side ScriptDoc copy (future /scripts sync + debugging)
                    if self.book_id:
                        try:
                            from pathlib import Path
                            import json as _json
                            d = Path("data/scripts") / self.book_id / self.script_type
                            d.mkdir(parents=True, exist_ok=True)
                            (d / f"c{it['id']}.json").write_text(
                                _json.dumps(res["paragraphs"], ensure_ascii=False), encoding="utf-8")
                        except Exception:  # noqa: BLE001
                            pass
                self.chars_out += len(res["fixed"])
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
                "script_type": self.script_type, "items": self.total, "done": self.progress,
                "chars_in": self.chars_in, "chars_out": self.chars_out,
                "duration_s": round(self.finished - (self.started or self.finished), 1),
                "error": self.error,
            })

    def cancel(self) -> None:
        self._cancel = True
        self._pause.set()  # wake a paused loop so it can exit
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
            "id": self.id,
            "model": self.model,
            "script_type": self.script_type,
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
        d["results"] = [
            {"id": k, "fixed": v, "paragraphs": self.paragraphs.get(k)}
            for k, v in self.results.items()
        ]
        return d


class JobManager:
    def __init__(self) -> None:
        self.jobs: dict[str, Job] = {}

    def submit(self, model: str | None, items: list[dict], script_type: str = "grammar", book_id: str = "") -> Job:
        job = Job(model, items, script_type, book_id)
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

    def pause(self, jid: str) -> Job | None:
        j = self.jobs.get(jid)
        if j:
            j.pause()
        return j

    def resume(self, jid: str) -> Job | None:
        j = self.jobs.get(jid)
        if j:
            j.resume()
        return j


jobs = JobManager()
