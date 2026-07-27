KAZAKH_DISCLAIMER = "Бұл медициналық диагноз емес. Бұл тек оқу қолдауының деңгейін шамамен бағалау."
RUSSIAN_DISCLAIMER = "Это не медицинский диагноз. Это примерная оценка уровня поддержки чтения."


def calculate_reading_screening(
    *,
    words_total: int,
    words_read_correctly: int,
    duration_ms: int,
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
        "disclaimer": KAZAKH_DISCLAIMER,
    }
