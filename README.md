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

On startup the backend runs `Base.metadata.create_all(bind=engine)` for the MVP tables:

- `user_profiles`
- `user_skill_profiles`
- `reading_sessions`
- `exercise_attempts`
- `screening_results`

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

Useful local checks:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

Backend base URLs are centralized in `app/src/main/java/com/example/sulu_read/data/ApiClient.kt`. For emulator testing, keep the FastAPI backend running locally and use `http://10.0.2.2:8000` or update the configured LAN URL for a physical device.

## Manual End-to-End Check

1. Start the FastAPI backend with a valid Supabase `SUPABASE_DIRECT_CONNECTION_STRING`.
2. Confirm `GET /health` returns `db_ready: true`.
3. Launch the Android app and confirm anonymous user creation succeeds.
4. Scan or choose a textbook image and confirm `/v1/adapt-image` opens the reader.
5. Toggle reader display settings; restart the app and confirm they persist.
6. Start training from the reader text, submit attempts, and confirm `/v1/exercises/attempt` returns feedback.
7. Run the screening flow and confirm the result uses only support-level language.
8. Open Progress and confirm saved exercise/screening data persists across app restarts.
