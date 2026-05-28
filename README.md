---
title: Sulu Read Backend
colorFrom: green
colorTo: yellow
sdk: docker
app_port: 7860
pinned: false
license: mit
---

# Sulu-Read Backend

FastAPI + Android MVP for adapting textbook pages and article links into dyslexia-friendly reading support.

The app does not diagnose dyslexia and does not claim treatment or cure. Screening results are only reading support indicators: `low`, `moderate`, or `high`.

## Backend Endpoints

- `GET /health`
- `POST /v1/adapt-url`
- `POST /v1/adapt-image`
- `POST /v1/users`
- `GET /v1/users/{user_id}`
- `POST /v1/exercises/generate`
- `POST /v1/exercises/attempt`
- `POST /v1/screening/reading-test`
- `GET /v1/progress/{user_id}`
- `POST /v1/simplify`

## Environment

Create `.env` locally. Do not commit it. `.env`, `*.db`, and logs are ignored by Git in this repository.

```env
SUPABASE_PROJECT_URL=your_project_url
SUPABASE_DB_PASSWORD=your_db_password
SUPABASE_PUBLISHABLE_KEY=your_publishable_key
SUPABASE_DIRECT_CONNECTION_STRING=your_direct_connection_string
GROQ_API=your_optional_groq_api_key
```

The backend reads `SUPABASE_DIRECT_CONNECTION_STRING` with SQLAlchemy and uses `postgresql+psycopg`. The URL is normalized for `sslmode=require` and for passwords containing special characters. If `SUPABASE_DIRECT_CONNECTION_STRING` is missing, the app logs a warning and falls back to `sqlite:///./sulu_read_local.db` for local development only. If the Supabase URL is present but unreachable or invalid, the backend logs the connection failure and `/health` returns `db_ready: false`; it does not silently switch production traffic to SQLite.

## Run Backend

```bash
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 7860
```

Docker/Hugging Face Spaces still starts with:

```bash
uvicorn main:app --host 0.0.0.0 --port 7860
```

## Run Tests

```bash
python -m pytest backend/tests
```

The live Supabase integration test is skipped unless `SUPABASE_DIRECT_CONNECTION_STRING` is exported in the test process. To verify table creation and connectivity against Supabase:

```bash
set SUPABASE_DIRECT_CONNECTION_STRING=your_direct_connection_string
python -m pytest backend/tests/test_supabase_connectivity.py
```

On PowerShell:

```powershell
$env:SUPABASE_DIRECT_CONNECTION_STRING="your_direct_connection_string"
python -m pytest backend/tests/test_supabase_connectivity.py
```

## Android

Open the project in Android Studio and run the `app` module. The Android app talks only to the FastAPI backend; it does not contain Supabase database credentials and does not connect directly to PostgreSQL.
