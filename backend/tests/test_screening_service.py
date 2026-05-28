from backend.app.services.screening_service import calculate_reading_screening


def test_screening_calculates_moderate_support():
    result = calculate_reading_screening(
        words_total=80,
        words_read_correctly=58,
        duration_ms=90_000,
    )

    assert result["wpm"] == 38.67
    assert result["accuracy"] == 0.725
    assert result["support_level"] == "moderate"
    assert "диагноз" in result["disclaimer"].lower()


def test_screening_high_support_for_low_accuracy():
    result = calculate_reading_screening(
        words_total=100,
        words_read_correctly=60,
        duration_ms=60_000,
    )

    assert result["support_level"] == "high"
