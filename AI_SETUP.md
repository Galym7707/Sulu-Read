# Sulu-Read AI setup

Sulu-Read calls AI providers only through the backend endpoint:

```text
POST /ai/generate
```

Android must not call Gemini or Groq directly and must not contain provider API keys.

## Backend environment variables

```text
AI_PRIMARY_PROVIDER=gemini
AI_FALLBACK_PROVIDER=groq

GEMINI_API_KEY=
GEMINI_MODEL=gemini-3.5-flash

GROQ_API_KEY=
GROQ_MODEL=llama-3.3-70b-versatile

AI_PROVIDER_TIMEOUT_SECONDS=30
```

Defaults:

- Primary provider: `gemini`
- Fallback provider: `groq`
- Gemini model: `gemini-3.5-flash`
- Groq model: `llama-3.3-70b-versatile`

## Android configuration

Set the public backend URL at build time:

```text
AI_BACKEND_URL=https://YOUR_BACKEND_HOST
```

For local emulator testing, the app still keeps existing fallback backend URLs, including `http://10.0.2.2:8000`.

Do not place `GEMINI_API_KEY` or `GROQ_API_KEY` in Android code, Android resources, or Gradle properties used by the APK.

## Request body

```json
{
  "task": "string",
  "text": "string",
  "language": "kk",
  "level": "optional string",
  "mode": "explain",
  "extra": {}
}
```

Allowed `language` values: `kk`, `ru`, `en`.

Allowed `mode` values: `explain`, `exercise`, `check_answer`, `generate_task`, `reading_help`, `morphology_help`.

## Success response

```json
{
  "success": true,
  "provider": "gemini",
  "model": "gemini-3.5-flash",
  "result": "string"
}
```

## Error response

```json
{
  "success": false,
  "error": "AI help is temporarily unavailable. Please try again later."
}
```

The backend must not return raw provider errors or secrets.

## Fallback behavior

The backend tries Gemini first. If Gemini fails, it tries Groq. Fallback is used for timeout, network errors, HTTP 429, HTTP 5xx, quota or rate-limit messages, temporary provider failures, invalid provider responses, and empty provider responses.

## Supabase Edge Functions alternative

This repository currently has a Python FastAPI backend, so `/ai/generate` is implemented there. If you later move AI routing to Supabase Edge Functions, keep provider keys as Supabase secrets:

```bash
supabase secrets set GEMINI_API_KEY=...
supabase secrets set GROQ_API_KEY=...
supabase functions deploy ai-generate
```

Secrets can also be set in Supabase Dashboard -> Edge Functions / Project Secrets.

## Manual test

1. Deploy or run the backend with `GEMINI_API_KEY` and `GROQ_API_KEY` set.
2. Build Android with `AI_BACKEND_URL` pointing to the public backend.
3. Open the reading screen.
4. Trigger AI help.
5. Confirm the response succeeds and reports `provider: gemini` in backend/API output.
6. Temporarily break `GEMINI_API_KEY`.
7. Trigger AI help again.
8. Confirm the response succeeds with `provider: groq`.

## Useful commands

```bash
./gradlew build
./gradlew test
python -m pytest backend/tests/test_ai_generation_service.py
uvicorn main:app --reload
```

Reference docs:

- Gemini `models.generateContent`: https://ai.google.dev/api/generate-content
- Groq Chat Completions: https://console.groq.com/docs/api-reference
