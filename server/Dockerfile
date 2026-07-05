FROM python:3.11-slim

WORKDIR /app

# Install deps first for layer caching.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# The whole app dir is bind-mounted in docker-compose for hot-reload; this COPY is only for
# building a standalone image.
COPY . .

EXPOSE 8000

# --reload picks up code changes in the mounted volume (point 7).
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--reload"]
