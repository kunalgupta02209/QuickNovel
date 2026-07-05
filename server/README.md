# QuickNovel LLM Fix Server

GPU-backed rewriting server the app offloads to (on-device is ~3–4 min/chapter; a 3B on a GPU
via Ollama is seconds). FastAPI + **LiteLLM** (routes to Ollama / any provider).

## Quick start (Docker — recommended)

```bash
cd server
cp config.example.yaml config.yaml         # edit models / ollama url
# make sure Ollama is running on the host with your model pulled, e.g.:
#   ollama pull qwen2.5:3b
docker compose up --build                  # server on http://<host>:8000, hot-reloads on edits
```

Point the app at `http://<your-machine-ip>:8000` in Read-aloud → LLM fixer settings.

## Local (no Docker)

```bash
cd server
pip install -r requirements.txt
cp config.example.yaml config.yaml
uvicorn app.main:app --reload              # http://127.0.0.1:8000
```

## CLI playground (iterate on the system prompt)

```bash
python cli.py "He walk to the store. She give him a apple."
python cli.py --file chapter.txt --model qwen2.5-7b --prompt prompts/system_prompt.md
```

## API

| Method | Path | Body / notes |
|---|---|---|
| GET  | `/health` | liveness |
| GET  | `/models` | models from `config.yaml` |
| GET  | `/prompt` | current system prompt (hot-reloaded from `prompts/system_prompt.md`) |
| GET/POST | `/config` · `/config/store_samples?on=true` | runtime sample-storage toggle |
| POST | `/fix/snippet` | `{text, model?, previous_chapters?, character_memory?}` → `{fixed}` |
| POST | `/fix/batch` | `{model?, items:[{id,text}]}` → `{job_id}` |
| GET  | `/jobs` · `/jobs/{id}` | list / detail |
| POST | `/jobs/{id}/cancel` | cancel a running job |

## Configuration

- **Models** — edit the `models:` list in `config.yaml` (hot-reloaded). Each has `id`, `name`, and a
  LiteLLM string (`ollama/qwen2.5:3b`, `openai/gpt-4o-mini`, …).
- **System prompt** — `prompts/system_prompt.md`, hot-reloaded on save.
- **Sample storage** — set `store_samples: true` (or `POST /config/store_samples?on=true`); pairs land
  in `samples/samples.jsonl`.
- **Logs** — console + `logs/server.log` (rotating).
