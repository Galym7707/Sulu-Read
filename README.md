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
- `POST /v1/users/register`
- `POST /v1/users/login`
- `GET /v1/users/{user_id}`
- `PATCH /v1/users/{user_id}/language`
- `POST /v1/exercises/generate`
- `POST /v1/exercises/attempt`
- `POST /v1/screening/reading-test`
- `GET /v1/progress/{user_id}`
- `POST /v1/simplify`
- `POST /ai/generate`

`/v1/progress/{user_id}` includes `daily_wpm`, an array of `{date, wpm, accuracy}` objects built from saved reading checks for the progress chart.

## Exercise Types

`POST /v1/exercises/generate` supports:

- `syllable_order`
- `missing_syllable`
- `word_to_syllables`
- `auditory_match`
- `root_suffix_identification`: choose the correct root and suffix chain, for example `балаларымызға` -> `бала + лар + ымыз + ға`.
- `word_segmentation`: choose the base word for a joined suffix chain, for example `ларымызға` -> `бала`.
- `mixed`: cycles through the available task types and skips morphology tasks when no suffix split is available.

Morphology tasks use lightweight Kazakh suffix heuristics only. They are practice aids, not linguistic analysis guarantees.

## Environment

Create `.env` locally. Do not commit it. `.env`, `*.db`, and logs are ignored by Git in this repository.

```env
SUPABASE_PROJECT_URL=your_project_url
SUPABASE_DB_PASSWORD=your_db_password
SUPABASE_PUBLISHABLE_KEY=your_publishable_key
SUPABASE_DIRECT_CONNECTION_STRING=your_direct_connection_string
GROQ_API=your_optional_groq_api_key
AI_PRIMARY_PROVIDER=gemini
AI_FALLBACK_PROVIDER=groq
GEMINI_API_KEY=your_gemini_api_key
GEMINI_MODEL=gemini-3.5-flash
GROQ_API_KEY=your_groq_api_key
GROQ_MODEL=llama-3.3-70b-versatile
SULU_READ_RUNTIME_SQLITE_FALLBACK=true
SULU_READ_OCR_CORRECTION=true
```

`SULU_READ_OCR_CORRECTION` controls the post-OCR correction layer applied to `/v1/adapt-image` output. It strips Latin/Cyrillic homoglyphs (e.g. a Latin `k` inside a Cyrillic word), fixes a word-initial `ң` misread, restores a small closed set of `һ` loanwords (e.g. `гаухар` → `гауһар`), and — using the bundled Kazakh hunspell dictionary as the decision procedure — restores Kazakh letters OCR flattened onto their Russian lookalikes (`қ ғ ә ө ұ ү і ң һ`) when exactly one restoration is a real Kazakh word. A word already valid in Russian (checked against a bundled Russian dictionary) is always left alone, since `/v1/adapt-image` defaults to `language_hint="kk"` and would otherwise rewrite correct Russian text such as `доска` → `досқа`. It no longer rewrites a scanned `нын`/`дын` ending into `ның`/`дың` — that was tried and rewrote already-correct text, because a string pattern cannot tell a flattened suffix from a genuinely different, correctly-spelled word (`-ын`/`-ін` possessive-accusative, e.g. `телефонын`, is a productive ending, not a scanning error). It is on by default; set it to `false` to return the raw engine text. `/health` reports the active state as `ocr_correction_enabled`.

To measure recognition quality after changing anything in the OCR path, run the synthetic evaluation harness (`python scripts/ocr_eval.py`). It reports CER, WER, and per-letter recovery for the Kazakh alphabet.

```bash
python scripts/ocr_eval.py
```

### Third-Party Data

`backend/app/data/` vendors two hunspell dictionaries, byte-for-byte and unmodified, used only by the OCR correction layer above:

- `kk_KZ.dic` / `kk_KZ.aff` — Kazakh, from [taem/hunspell-kk](https://github.com/taem/hunspell-kk). Sulu-Read elects the Mozilla Public License version 1.1 for these two files (see `backend/app/data/LICENSE-kk_KZ.txt`).
- `ru_RU.dic` / `ru_RU.aff` — Russian, from [wooorm/dictionaries](https://github.com/wooorm/dictionaries) (`dictionaries/ru`), © 1997-2008 Alexander I. Lebedev, **BSD-3-Clause** (see `backend/app/data/LICENSE-ru_RU.txt`).

Both are MPL/BSD file-level; the rest of the repository stays MIT licensed.

The backend reads `SUPABASE_DIRECT_CONNECTION_STRING` with SQLAlchemy and uses `postgresql+psycopg`. Mixed-case local aliases such as `SUPABASE_direct_connection_string`, `SUPABASE_project_url`, and `SUPABASE_publishable_key` are also accepted for compatibility, but new environments should use the uppercase names above. The URL is normalized for `sslmode=require` and for passwords containing special characters. If `SUPABASE_DIRECT_CONNECTION_STRING` is missing, the app logs a warning and falls back to `sqlite:///./sulu_read_local.db`.

If the configured Supabase URL is present but unreachable or invalid, the backend can also switch to the same SQLite fallback at startup so registration and training remain usable. This is controlled by `SULU_READ_RUNTIME_SQLITE_FALLBACK`; set it to `false` to fail closed instead. `/health` reports `db_runtime_sqlite_fallback` so deployments can detect when fallback storage is active.

AI setup, fallback behavior, Android `AI_BACKEND_URL`, and manual test steps are documented in [AI_SETUP.md](AI_SETUP.md).

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

The live Supabase integration test is skipped unless `SUPABASE_DIRECT_CONNECTION_STRING` is available in the test process or `.env`. To verify table creation and connectivity against Supabase:

```bash
set SUPABASE_DIRECT_CONNECTION_STRING=your_direct_connection_string
python -m pytest backend/tests/test_supabase_connectivity.py
```

On PowerShell:

```powershell
$env:SUPABASE_DIRECT_CONNECTION_STRING="your_direct_connection_string"
python -m pytest backend/tests/test_supabase_connectivity.py
```

For a direct operational check outside pytest, run:

```bash
python scripts/supabase_check.py
```

The script loads `.env`, reads `SUPABASE_DIRECT_CONNECTION_STRING`, connects through SQLAlchemy, runs `Base.metadata.create_all(bind=engine)`, and executes `SELECT 1`. Run it only from a machine or deployment environment that can reach the Supabase PostgreSQL host; firewalls, paused projects, or DNS restrictions should produce a readable failure message. `/health` also performs a live database check and returns `db_ready: false` when the configured Supabase connection is unavailable.

## Android

Open the project in Android Studio and run the `app` module. The Android app talks only to the FastAPI backend; it does not contain Supabase database credentials and does not connect directly to PostgreSQL.

The app supports runtime language switching for English, Russian, and Kazakh. Open the `Settings` tab, choose `English`, `Русский`, or `Қазақша`, and the Compose UI will recompose immediately. The selected language is saved in DataStore and is used as the backend `language_hint` for simplification, exercises, and future adaptation requests.

The Settings tab also supports registration and login with a username and password. Passwords are stored on the backend as salted PBKDF2 hashes; the MVP still uses the returned `user_id` as the client session identifier.

Exercise attempts are protected against transient network loss. If submission fails, the Android app stores a local `PendingAttempt` in DataStore with `status=pending`, shows the sync status on the training screen, and uses WorkManager with a connected-network constraint to retry pending attempts in the background.

The Progress tab shows summary cards plus a Canvas-based chart for reading speed (WPM) and accuracy by date. If no reading-check data exists, the chart area shows `No data`.

Useful local checks:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

Backend base URLs are centralized in `app/src/main/java/com/example/sulu_read/data/ApiClient.kt`. For emulator testing, keep the FastAPI backend running locally and use `http://10.0.2.2:8000` or update the configured LAN URL for a physical device.

## Adding Translations

String resources live in:

- `app/src/main/res/values/strings.xml` for English defaults
- `app/src/main/res/values-ru/strings.xml` for Russian
- `app/src/main/res/values-kk/strings.xml` for Kazakh

When adding a visible UI label, add the same key to all three files and read it with `stringResource(...)`. Keep backend language hints aligned with `en`, `ru`, and `kk`.

## Manual End-to-End Check

1. Start the FastAPI backend with a valid Supabase `SUPABASE_DIRECT_CONNECTION_STRING`.
2. Confirm `GET /health` returns `db_ready: true`.
3. Launch the Android app and confirm anonymous user creation succeeds.
4. Scan or choose a textbook image and confirm `/v1/adapt-image` opens the reader.
5. Toggle reader display settings; restart the app and confirm they persist.
6. Start training from the reader text, submit attempts, and confirm `/v1/exercises/attempt` returns feedback.
7. Run the screening flow and confirm the result uses only support-level language.
8. Open Progress and confirm saved exercise/screening data persists across app restarts.
