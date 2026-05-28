import json
import logging
import re

import requests

from ..config import settings


logger = logging.getLogger("sulu_read_backend.simplification")


def simplify_text(text: str, language_hint: str = "kk") -> str:
    cleaned_text = normalize_for_simplification(text)
    if not cleaned_text:
        return "Коротко: мәтін бос."

    if settings.groq_api_key:
        groq_result = simplify_with_groq(cleaned_text, language_hint)
        if groq_result:
            return groq_result

    return deterministic_simplify(cleaned_text)


def simplify_with_groq(text: str, language_hint: str) -> str:
    if language_hint.startswith("en"):
        language_instruction = "English"
    elif language_hint.startswith("ru"):
        language_instruction = "Russian"
    else:
        language_instruction = "Kazakh"
    try:
        response = requests.post(
            "https://api.groq.com/openai/v1/chat/completions",
            headers={
                "Authorization": f"Bearer {settings.groq_api_key}",
                "Content-Type": "application/json",
            },
            json={
                "model": settings.groq_vision_model,
                "temperature": 0.1,
                "max_completion_tokens": 600,
                "response_format": {"type": "json_object"},
                "messages": [
                    {
                        "role": "user",
                        "content": (
                            f"Simplify this paragraph in {language_instruction}. Preserve factual meaning. "
                            "Do not add facts. Use short simple sentences for about 5th grade reading level. "
                            "Return JSON with one key simplified_text.\n\n"
                            f"{text}"
                        ),
                    }
                ],
            },
            timeout=settings.groq_timeout_seconds,
        )
        if response.status_code >= 400:
            logger.warning("Groq simplification failed with status %s", response.status_code)
            return ""

        content = response.json().get("choices", [{}])[0].get("message", {}).get("content", "")
        parsed = json.loads(content)
        simplified = str(parsed.get("simplified_text", "")).strip()
        return simplified if simplified else ""
    except Exception:
        logger.warning("Groq simplification failed; using deterministic fallback", exc_info=True)
        return ""


def deterministic_simplify(text: str) -> str:
    without_parentheses = re.sub(r"\([^)]*\)", "", text)
    sentences = [
        sentence.strip()
        for sentence in re.findall(r"[^.!?]+[.!?]?", without_parentheses)
        if sentence.strip()
    ]
    selected = " ".join(sentences[:3]).strip() or without_parentheses.strip()
    words = selected.split()
    if len(words) > 48:
        selected = " ".join(words[:48]) + "..."
    return f"Коротко: {selected}"


def normalize_for_simplification(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()
