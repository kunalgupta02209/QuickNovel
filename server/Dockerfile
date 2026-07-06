# uv-enabled image (uv + Python 3.11 preinstalled).
FROM ghcr.io/astral-sh/uv:python3.11-bookworm-slim

WORKDIR /app

# Keep the venv OUTSIDE /app so the docker-compose bind-mount (for hot-reload) can't shadow it,
# and don't fetch a managed Python — use the image's 3.11.
ENV UV_PROJECT_ENVIRONMENT=/opt/uvenv \
    UV_PYTHON_DOWNLOADS=never \
    UV_COMPILE_BYTECODE=1

# Install deps first (cached layer) from the lockfile.
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev

# App code (bind-mounted over this in compose; COPY is for a standalone image).
COPY . .

EXPOSE 8000

# --no-sync: use the prebuilt /opt/uvenv as-is; --reload picks up mounted code changes.
CMD ["uv", "run", "--no-sync", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--reload"]
