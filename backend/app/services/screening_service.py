KAZAKH_DISCLAIMER = "Бұл медициналық диагноз емес. Бұл тек оқу қолдауының деңгейін шамамен бағалау."
RUSSIAN_DISCLAIMER = "Это не медицинский диагноз. Это примерная оценка уровня поддержки чтения."
ENGLISH_DISCLAIMER = (
    "This is not a medical diagnosis. It is only a rough estimate of the level of reading support needed."
)

DISCLAIMERS = {
    "kk": KAZAKH_DISCLAIMER,
    "ru": RUSSIAN_DISCLAIMER,
    "en": ENGLISH_DISCLAIMER,
}


def disclaimer_for(language_hint: str) -> str:
    if language_hint.startswith("ru"):
        return RUSSIAN_DISCLAIMER
    if language_hint.startswith("en"):
        return ENGLISH_DISCLAIMER
    return KAZAKH_DISCLAIMER


def calculate_reading_screening(
    *,
    words_total: int,
    words_read_correctly: int,
    duration_ms: int,
    language_hint: str = "kk",
) -> dict:
    safe_words_total = max(words_total, 1)
    safe_correct = max(0, min(words_read_correctly, safe_words_total))
    duration_minutes = max(duration_ms, 1) / 60_000
    wpm = safe_correct / duration_minutes
    accuracy = safe_correct / safe_words_total

    if wpm < 30 or accuracy < 0.65:
        support_level = "high"
    elif wpm < 60 or accuracy < 0.85:
        support_level = "moderate"
    else:
        support_level = "low"

    return {
        "wpm": round(wpm, 2),
        "accuracy": round(accuracy, 3),
        "support_level": support_level,
        # Was pinned to the Kazakh text for every caller, so a Russian- or English-speaking
        # family was told the result was not a medical diagnosis in a language they may not read
        # — which is the one sentence here that has to land.
        "disclaimer": disclaimer_for(language_hint),
    }
