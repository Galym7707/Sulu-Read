import logging
import os
from dataclasses import dataclass
from typing import Any
from urllib.parse import quote

import requests

from ..schemas import AiGenerateRequest


logger = logging.getLogger("sulu_read_backend.ai")

GEMINI_GENERATE_CONTENT_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
GROQ_CHAT_COMPLETIONS_URL = "https://api.groq.com/openai/v1/chat/completions"
DEFAULT_PRIMARY_PROVIDER = "gemini"
DEFAULT_FALLBACK_PROVIDER = "groq"
DEFAULT_GEMINI_MODEL = "gemini-3.5-flash"
DEFAULT_GROQ_MODEL = "llama-3.3-70b-versatile"
SAFE_AI_ERROR = "AI help is temporarily unavailable. Please try again later."
RETRYABLE_HTTP_STATUSES = {429, 500, 502, 503, 504}
RETRYABLE_ERROR_MARKERS = (
    "quota exceeded",
    "rate limit",
    "rate_limit",
    "overloaded",
    "temporarily unavailable",
    "temporary",
    "timeout",
    "model unavailable",
)


@dataclass(frozen=True)
class AiProviderResult:
    provider: str
    model: str
    result: str


class AiProviderError(Exception):
    def __init__(self, provider: str, reason: str, retryable: bool = True):
        super().__init__(reason)
        self.provider = provider
        self.retryable = retryable


def generate_ai_response(payload: AiGenerateRequest) -> AiProviderResult:
    primary_provider = read_provider("AI_PRIMARY_PROVIDER", DEFAULT_PRIMARY_PROVIDER)
    fallback_provider = read_provider("AI_FALLBACK_PROVIDER", DEFAULT_FALLBACK_PROVIDER)
    provider_order = [primary_provider]
    if fallback_provider != primary_provider:
        provider_order.append(fallback_provider)

    last_error: AiProviderError | None = None
    for index, provider in enumerate(provider_order):
        try:
            return call_provider(provider, payload)
        except AiProviderError as exc:
            last_error = exc
            logger.warning(
                "AI provider %s failed with retryable=%s; trying fallback=%s",
                exc.provider,
                exc.retryable,
                index + 1 < len(provider_order),
            )
            if index == 0 and not exc.retryable:
                continue

    raise AiProviderError(
        provider=last_error.provider if last_error else "unknown",
        reason=SAFE_AI_ERROR,
        retryable=False,
    )


def read_provider(env_name: str, default: str) -> str:
    return os.getenv(env_name, default).strip().lower() or default


def call_provider(provider: str, payload: AiGenerateRequest) -> AiProviderResult:
    if provider == "gemini":
        return call_gemini(payload)
    if provider == "groq":
        return call_groq(payload)
    raise AiProviderError(provider=provider, reason="Unsupported AI provider", retryable=True)


def call_gemini(payload: AiGenerateRequest) -> AiProviderResult:
    api_key = (os.getenv("GEMINI_API_KEY") or "").strip()
    model = os.getenv("GEMINI_MODEL", DEFAULT_GEMINI_MODEL).strip() or DEFAULT_GEMINI_MODEL
    timeout = read_timeout()
    if not api_key:
        raise AiProviderError(provider="gemini", reason="Gemini key is not configured", retryable=True)

    response = post_json(
        provider="gemini",
        url=GEMINI_GENERATE_CONTENT_URL.format(model=quote(model, safe="")),
        headers={
            "Content-Type": "application/json",
            "x-goog-api-key": api_key,
        },
        json_payload={
            "systemInstruction": {
                "parts": [{"text": build_system_prompt()}],
            },
            "contents": [
                {
                    "role": "user",
                    "parts": [{"text": build_user_prompt(payload)}],
                }
            ],
            "generationConfig": {
                "temperature": 0.2,
                "maxOutputTokens": 2048,
            },
        },
        timeout=timeout,
    )
    text = extract_gemini_text(response)
    if not text:
        raise AiProviderError(provider="gemini", reason="Gemini returned empty response", retryable=True)
    return AiProviderResult(provider="gemini", model=model, result=text)


def call_groq(payload: AiGenerateRequest) -> AiProviderResult:
    api_key = (os.getenv("GROQ_API_KEY") or os.getenv("GROQ_API") or "").strip()
    model = os.getenv("GROQ_MODEL", DEFAULT_GROQ_MODEL).strip() or DEFAULT_GROQ_MODEL
    timeout = read_timeout()
    if not api_key:
        raise AiProviderError(provider="groq", reason="Groq key is not configured", retryable=True)

    response = post_json(
        provider="groq",
        url=GROQ_CHAT_COMPLETIONS_URL,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        json_payload={
            "model": model,
            "temperature": 0.2,
            "max_completion_tokens": 2048,
            "messages": [
                {"role": "system", "content": build_system_prompt()},
                {"role": "user", "content": build_user_prompt(payload)},
            ],
        },
        timeout=timeout,
    )
    text = extract_groq_text(response)
    if not text:
        raise AiProviderError(provider="groq", reason="Groq returned empty response", retryable=True)
    return AiProviderResult(provider="groq", model=model, result=text)


def post_json(
    provider: str,
    url: str,
    headers: dict[str, str],
    json_payload: dict[str, Any],
    timeout: float,
) -> dict[str, Any]:
    try:
        response = requests.post(
            url,
            headers=headers,
            json=json_payload,
            timeout=timeout,
        )
    except requests.Timeout as exc:
        raise AiProviderError(provider=provider, reason="Provider timeout", retryable=True) from exc
    except requests.RequestException as exc:
        raise AiProviderError(provider=provider, reason="Provider network error", retryable=True) from exc

    response_text = response.text or ""
    if response.status_code >= 400:
        retryable = is_retryable_provider_failure(response.status_code, response_text)
        raise AiProviderError(
            provider=provider,
            reason=f"Provider HTTP {response.status_code}",
            retryable=retryable,
        )

    try:
        payload = response.json()
    except ValueError as exc:
        raise AiProviderError(provider=provider, reason="Provider returned invalid JSON", retryable=True) from exc
    return payload


def is_retryable_provider_failure(status_code: int, response_text: str) -> bool:
    if status_code in RETRYABLE_HTTP_STATUSES:
        return True
    lowered = response_text.lower()
    return any(marker in lowered for marker in RETRYABLE_ERROR_MARKERS)


def extract_gemini_text(payload: dict[str, Any]) -> str:
    parts: list[str] = []
    for candidate in payload.get("candidates", []):
        content = candidate.get("content", {}) if isinstance(candidate, dict) else {}
        for part in content.get("parts", []):
            if isinstance(part, dict):
                text = str(part.get("text", "")).strip()
                if text:
                    parts.append(text)
    return "\n".join(parts).strip()


def extract_groq_text(payload: dict[str, Any]) -> str:
    choices = payload.get("choices", [])
    if not choices:
        return ""
    first_choice = choices[0] if isinstance(choices[0], dict) else {}
    message = first_choice.get("message", {})
    content = message.get("content", "") if isinstance(message, dict) else ""
    return str(content).strip()


def build_system_prompt() -> str:
    return (
        "You are Sulu-Read's educational AI helper for reading and language learning. "
        "Support Kazakh, Russian, and English. Explain clearly and briefly. "
        "For students, give guidance and examples instead of only final answers in learning modes. "
        "For check_answer mode, compare the student's answer with the expected answer and explain mistakes. "
        "For generate_task mode, create reading, morphology, or language exercises based on the level. "
        "Keep output structured, safe, and easy to display in a mobile UI."
    )


def build_user_prompt(payload: AiGenerateRequest) -> str:
    lines = [
        f"Mode: {payload.mode}",
        f"Language: {payload.language}",
        f"Level: {payload.level or 'not specified'}",
        f"Task: {payload.task.strip()}",
        "Text:",
        payload.text.strip(),
    ]
    if payload.extra:
        lines.extend(["Extra context:", safe_extra_context(payload.extra)])
    return "\n".join(lines)


def safe_extra_context(extra: dict[str, Any]) -> str:
    safe_items = []
    for key, value in extra.items():
        safe_key = str(key)[:80]
        safe_value = str(value)[:500]
        safe_items.append(f"{safe_key}: {safe_value}")
    return "\n".join(safe_items)


def read_timeout() -> float:
    raw_value = os.getenv("AI_PROVIDER_TIMEOUT_SECONDS", "30")
    try:
        return max(1.0, float(raw_value))
    except ValueError:
        return 30.0
