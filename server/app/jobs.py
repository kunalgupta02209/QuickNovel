import asyncio
import logging
import time
import uuid

from . import history
from .fixer import fix_text

log = logging.getLogger("jobs")


class Job:
    def __init__(self, model: str | None, items: list[dict], script_type: str = "grammar", book_id: str = "",
                 jid: str | None = None):
        self.id = jid or uuid.uuid4().hex[:12]
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

    def _parallel_items(self) -> int:
        """Concurrent ITEMS (chapters) for this job. Cloud models are network-bound -> several
        chapters at once (still bounded by fixer's cloud semaphore); local Ollama stays serial."""
        from .config import config
        from .fixer import SCRIPT_TASKS

        model_id = None if self.model == "auto" else self.model
        if model_id is None:
            model_id = config.task_model(SCRIPT_TASKS.get(self.script_type, "grammar_fix"))["model"]
        if config.is_cloud(model_id):
            return max(1, int(config.get("fix_parallel_items", 4)))
        return 1

    async def run(self) -> None:
        self.status = "running"
        self.started = time.time()
        parallel = self._parallel_items()
        log.info("job %s started: %d items, model=%s script=%s parallel=%d",
                 self.id, self.total, self.model, self.script_type, parallel)
        item_sem = asyncio.Semaphore(parallel)

        async def do_item(it: dict) -> None:
            async with item_sem:
                if self._cancel:
                    return
                if not self._pause.is_set():
                    self.status = "paused"
                    await self._pause.wait()
                    if not self._cancel:
                        self.status = "running"
                if self._cancel:
                    return
                # Backfill character memory from the server-side map when the app sent none —
                # this alone resurrects the character-consistency feature with zero app change.
                memory = it.get("character_memory") or ""
                if not memory and self.book_id:
                    try:
                        from . import charmap
                        memory = charmap.prompt_block(
                            self.book_id,
                            int(it.get("id", -1)) if str(it.get("id", "")).lstrip("-").isdigit() else None,
                        )
                    except Exception:  # noqa: BLE001
                        memory = ""
                res = await fix_text(
                    it["text"],
                    None if self.model == "auto" else self.model,
                    previous_chapters=it.get("previous_chapters") or "",
                    character_memory=memory,
                    on_chunk=lambda i, n, out: setattr(self, "current_chunk", f"ch {it['id']} chunk {i + 1}/{n}"),
                    script_type=self.script_type,
                    pause_event=self._pause,
                )
                self.results[str(it["id"])] = res["fixed"]
                # grammar results persist as plain text so devices can fetch server-generated fixes
                if self.book_id and self.script_type == "grammar" and res["fixed"]:
                    try:
                        from pathlib import Path
                        d = Path("data/scripts") / self.book_id / "grammar"
                        d.mkdir(parents=True, exist_ok=True)
                        tmp = d / f"c{it['id']}.txt.part"
                        tmp.write_text(res["fixed"], encoding="utf-8")
                        import os as _os
                        _os.replace(tmp, d / f"c{it['id']}.txt")  # atomic: clients fetch these
                    except Exception:  # noqa: BLE001
                        pass
                if res.get("paragraphs") is not None:
                    self.paragraphs[str(it["id"])] = res["paragraphs"]
                    # server-side ScriptDoc copy (future /scripts sync + debugging)
                    if self.book_id:
                        try:
                            from pathlib import Path
                            import json as _json
                            d = Path("data/scripts") / self.book_id / self.script_type
                            d.mkdir(parents=True, exist_ok=True)
                            tmp = d / f"c{it['id']}.json.part"
                            tmp.write_text(
                                _json.dumps(res["paragraphs"], ensure_ascii=False), encoding="utf-8")
                            import os as _os
                            _os.replace(tmp, d / f"c{it['id']}.json")  # atomic: clients fetch these
                        except Exception:  # noqa: BLE001
                            pass
                self.chars_out += len(res["fixed"])
                self.progress += 1

        try:
            # Chapters run through a bounded gather; results are keyed by id so order is irrelevant.
            await asyncio.gather(*(do_item(it) for it in self.items))
            if self._cancel:
                self.status = "cancelled"
                log.info("job %s cancelled at %d/%d", self.id, self.progress, self.total)
                return
            self.status = "done"
            log.info("job %s done", self.id)
            # scripts -> audio auto-chain: a finished performance-script job queues the book's CAST
            # audio (idempotent — existing WAVs skip instantly), unless disabled in config.
            if self.script_type == "performance" and self.book_id:
                try:
                    from . import tts_generate as _gen
                    if _gen.auto_cast_audio_enabled():
                        out = _gen.generate(self.book_id, use_script=True)
                        log.info("auto-chained cast audio for %s: %s", self.book_id, out)
                except Exception as e:  # noqa: BLE001
                    log.warning("cast-audio auto-chain skipped for %s: %s", self.book_id, str(e)[:120])
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
            from . import job_store
            job_store.remove("fix", self.id)
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
        book_name = ""
        if self.book_id:
            try:
                from . import chapter_texts
                book_name = chapter_texts.meta(self.book_id).get("book_name") or ""
            except Exception:  # noqa: BLE001
                pass
        return {
            "id": self.id,
            "model": self.model,
            "book_id": self.book_id,
            "book_name": book_name,
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

    def submit(self, model: str | None, items: list[dict], script_type: str = "grammar", book_id: str = "",
               jid: str | None = None) -> Job:
        job = Job(model, items, script_type, book_id, jid=jid)
        self.jobs[job.id] = job
        from . import job_store
        job_store.save("fix", job.id, {"model": model, "items": items,
                                       "script_type": script_type, "book_id": book_id})
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
