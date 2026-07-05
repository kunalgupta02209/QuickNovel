# QuickNovel LLM Fix Server — design

On-device rewriting (Qwen 1.5B via llama.cpp) is ~3–4 min/chapter. This adds an optional
**GPU-backed server** the app offloads to, using **LiteLLM** to route to a good small model
(Qwen2.5 / Gemma via **Ollama** on the GPU, or any LiteLLM-supported provider). On-device stays
as the offline fallback; the server is opt-in via a URL setting.

## Architecture

```
 Android app  ──HTTP──▶  FastAPI server ──LiteLLM──▶  Ollama (GPU)  /  any provider
   (setting:              /fix/snippet   (sync)
    server URL)           /fix/batch     (async job)
                          /jobs, /jobs/{id}, /cancel
                          /models
                          /prompt, /config
```

- **Language/stack:** Python 3.11, FastAPI + Uvicorn, LiteLLM, watchfiles (hot-reload), PyYAML.
- **Model host:** Ollama (easiest "good small model on GPU"); LiteLLM `ollama/<name>`. Any other
  LiteLLM provider works by editing `config.yaml` (e.g. `openai/gpt-4o-mini`, `vllm/...`).
- **Everything lives in `server/`** (mounted into Docker for hot-reload).

## Endpoints (maps to the requested points)

| # | Requirement | How |
|---|---|---|
| 1 | Web server the app connects to | FastAPI on `:8000`; app has a `Fix server URL` setting |
| 2 | Batch + snippet APIs → fixed text | `POST /fix/snippet` (sync), `POST /fix/batch` (async job) |
| 3 | See running jobs on mobile | `GET /jobs`, `GET /jobs/{id}`, `POST /jobs/{id}/cancel` → in-app jobs UI |
| 4 | List of models to select | `GET /models` (from `config.yaml`) → in-app picker |
| 5 | System prompt from a hot-reloaded `.md` | `prompts/system_prompt.md`, watched via `watchfiles`; served at `GET /prompt` |
| 6 | Server logging for debugging | `logging` → console + rotating `logs/server.log`; per-request + per-fix logs |
| 7 | Docker, app dir mounted for hot-reload | `docker-compose.yml` mounts `./server:/app`; `uvicorn --reload` |
| 8 | CLI playground for the same flow | `cli.py` calls the same `fixer.fix_text()` — iterate on prompts locally |
| 9 | Store input/output by a runtime flag | `store_samples` in `config.yaml` (hot-reloaded, no restart) → `samples/*.jsonl` |

## Server modules (`server/app/`)

- `config.py` — load `config.yaml`, hot-reload on change (models, ollama base, `store_samples`, chunking).
- `prompts.py` — load `prompts/system_prompt.md`, hot-reload; `{previous_chapters}`/`{character_memory}` placeholders.
- `models.py` — model registry from config → `/models`.
- `fixer.py` — the fix flow (shared by API + CLI): chunk → `litellm.acompletion` per chunk → clean → dedupe.
- `jobs.py` — in-memory async job manager (batch): progress, cancel, results.
- `storage.py` — append `{ts, model, input, output}` to `samples/samples.jsonl` when `store_samples` on.
- `logging_config.py` — structured logging setup.
- `main.py` — FastAPI app wiring the above; a `watchfiles` task hot-reloads prompt + config.

## Mobile integration (task SRV-4)

- Setting `LLM_FIX_SERVER_URL` (blank = on-device). When set, the fix flow POSTs to the server:
  on-the-spot → `/fix/snippet`; background → `/fix/batch` + poll `/jobs/{id}` (much faster than device).
- A **Server jobs** screen (reader → fix panel) lists `GET /jobs` with cancel.
- Model picker fed by `GET /models`.
- Falls back to on-device if the server is unreachable.

## Run

```
cd server
cp config.example.yaml config.yaml     # edit models / ollama url
docker compose up --build              # server on :8000, hot-reloads on edits
# or locally: pip install -r requirements.txt && uvicorn app.main:app --reload
python cli.py "He walk to store."      # CLI playground
```
