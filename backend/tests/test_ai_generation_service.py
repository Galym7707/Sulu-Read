import requests
import pytest

from backend.app.schemas import AiGenerateRequest
from backend.app.services import ai_generation_service as service


class FakeResponse:
    def __init__(self, status_code: int, payload: dict | None = None, text: str = ""):
        self.status_code = status_code
        self._payload = payload or {}
        self.text = text

    def json(self) -> dict:
        return self._payload


def build_request() -> AiGenerateRequest:
    return AiGenerateRequest(
        task="Explain this text",
        text="Short reading text",
        language="en",
        mode="explain",
    )


def test_ai_router_uses_gemini_first(monkeypatch):
    calls = []
    monkeypatch.setenv("AI_PRIMARY_PROVIDER", "gemini")
    monkeypatch.setenv("AI_FALLBACK_PROVIDER", "groq")
    monkeypatch.setenv("GEMINI_API_KEY", "gemini-key")
    monkeypatch.setenv("GROQ_API_KEY", "groq-key")

    def fake_post(url, **kwargs):
        calls.append(url)
        return FakeResponse(
            200,
            {
                "candidates": [
                    {"content": {"parts": [{"text": "Gemini answer"}]}}
                ]
            },
        )

    monkeypatch.setattr(service.requests, "post", fake_post)

    result = service.generate_ai_response(build_request())

    assert result.provider == "gemini"
    assert result.result == "Gemini answer"
    assert len(calls) == 1
    assert "generativelanguage.googleapis.com" in calls[0]


def test_ai_router_falls_back_to_groq_on_gemini_429(monkeypatch):
    calls = []
    monkeypatch.setenv("AI_PRIMARY_PROVIDER", "gemini")
    monkeypatch.setenv("AI_FALLBACK_PROVIDER", "groq")
    monkeypatch.setenv("GEMINI_API_KEY", "gemini-key")
    monkeypatch.setenv("GROQ_API_KEY", "groq-key")

    def fake_post(url, **kwargs):
        calls.append(url)
        if "generativelanguage.googleapis.com" in url:
            return FakeResponse(429, text="quota exceeded")
        return FakeResponse(
            200,
            {"choices": [{"message": {"content": "Groq answer"}}]},
        )

    monkeypatch.setattr(service.requests, "post", fake_post)

    result = service.generate_ai_response(build_request())

    assert result.provider == "groq"
    assert result.result == "Groq answer"
    assert len(calls) == 2


def test_ai_router_falls_back_to_groq_on_gemini_timeout(monkeypatch):
    calls = []
    monkeypatch.setenv("AI_PRIMARY_PROVIDER", "gemini")
    monkeypatch.setenv("AI_FALLBACK_PROVIDER", "groq")
    monkeypatch.setenv("GEMINI_API_KEY", "gemini-key")
    monkeypatch.setenv("GROQ_API_KEY", "groq-key")

    def fake_post(url, **kwargs):
        calls.append(url)
        if "generativelanguage.googleapis.com" in url:
            raise requests.Timeout("timed out")
        return FakeResponse(
            200,
            {"choices": [{"message": {"content": "Groq answer"}}]},
        )

    monkeypatch.setattr(service.requests, "post", fake_post)

    result = service.generate_ai_response(build_request())

    assert result.provider == "groq"
    assert result.result == "Groq answer"
    assert len(calls) == 2


def test_ai_router_returns_safe_error_if_both_providers_fail(monkeypatch):
    monkeypatch.setenv("AI_PRIMARY_PROVIDER", "gemini")
    monkeypatch.setenv("AI_FALLBACK_PROVIDER", "groq")
    monkeypatch.setenv("GEMINI_API_KEY", "gemini-key")
    monkeypatch.setenv("GROQ_API_KEY", "groq-key")

    def fake_post(url, **kwargs):
        return FakeResponse(503, text="temporarily unavailable")

    monkeypatch.setattr(service.requests, "post", fake_post)

    with pytest.raises(service.AiProviderError) as exc_info:
        service.generate_ai_response(build_request())

    assert str(exc_info.value) == service.SAFE_AI_ERROR
