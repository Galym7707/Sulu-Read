from datetime import datetime, timezone

from backend.app import models
from backend.app.database import Base
from backend.app.services.progress_service import build_progress_summary
from backend.app.services.progress_service import update_skill_profile_after_attempt
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker


def make_attempt(is_correct: bool, response_time_ms: int) -> models.ExerciseAttempt:
    return models.ExerciseAttempt(
        user_id="user-1",
        exercise_type="syllable_order",
        target_word="балаларымызға",
        correct_answer="ба-ла-ла-ры-мыз-ға",
        user_answer="ба-ла-ла-ры-мыз-ға" if is_correct else "ба-ла",
        is_correct=is_correct,
        response_time_ms=response_time_ms,
        difficulty_level=2,
        language_hint="kk",
    )


def test_adaptive_difficulty_increase():
    skill = models.UserSkillProfile(user_id="user-1", current_difficulty=2)
    attempts = [make_attempt(True, 4_000) for _ in range(10)]

    updated = update_skill_profile_after_attempt(skill, attempts, True, 4_000)

    assert updated.current_difficulty == 3
    assert updated.phonological_skill > 0.50


def test_adaptive_difficulty_decrease():
    skill = models.UserSkillProfile(user_id="user-1", current_difficulty=3)
    attempts = [make_attempt(False, 8_000) for _ in range(10)]

    updated = update_skill_profile_after_attempt(skill, attempts, False, 8_000)

    assert updated.current_difficulty == 2
    assert updated.phonological_skill < 0.50


def test_morphology_attempt_has_stronger_skill_signal():
    standard = models.UserSkillProfile(user_id="standard", current_difficulty=2)
    morphology = models.UserSkillProfile(user_id="morphology", current_difficulty=2)
    attempts = [make_attempt(True, 4_000) for _ in range(10)]

    standard_updated = update_skill_profile_after_attempt(
        standard,
        attempts,
        True,
        4_000,
        exercise_type="syllable_order",
    )
    morphology_updated = update_skill_profile_after_attempt(
        morphology,
        attempts,
        True,
        4_000,
        exercise_type="root_suffix_identification",
        sub_exercise="morphology",
    )

    assert morphology_updated.phonological_skill > standard_updated.phonological_skill
    assert morphology_updated.decoding_fluency > standard_updated.decoding_fluency


def test_progress_summary_with_empty_user_data():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(bind=engine)
    Session = sessionmaker(bind=engine)
    db = Session()
    try:
        user = models.UserProfile(id="user-empty", display_name="Оқушы")
        db.add(user)
        db.commit()

        summary = build_progress_summary(db, "user-empty")

        assert summary["total_exercises"] == 0
        assert summary["exercise_accuracy"] == 0.0
        assert summary["average_response_time_ms"] == 0
        assert summary["latest_support_level"] is None
        assert summary["daily_activity"] == []
        assert summary["daily_wpm"] == []
    finally:
        db.close()


def test_progress_graph_data():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(bind=engine)
    Session = sessionmaker(bind=engine)
    db = Session()
    try:
        user = models.UserProfile(id="user-graph", display_name="Оқушы")
        db.add(user)
        db.add_all(
            [
                models.ScreeningResult(
                    user_id="user-graph",
                    test_type="short_reading",
                    words_total=100,
                    words_read_correctly=80,
                    errors_count=20,
                    duration_ms=100_000,
                    wpm=60.0,
                    accuracy=0.8,
                    support_level="moderate",
                    disclaimer="Not a diagnosis.",
                    created_at=datetime(2026, 5, 1, tzinfo=timezone.utc),
                ),
                models.ScreeningResult(
                    user_id="user-graph",
                    test_type="short_reading",
                    words_total=100,
                    words_read_correctly=90,
                    errors_count=10,
                    duration_ms=83_000,
                    wpm=72.0,
                    accuracy=0.9,
                    support_level="low",
                    disclaimer="Not a diagnosis.",
                    created_at=datetime(2026, 5, 1, tzinfo=timezone.utc),
                ),
                models.ScreeningResult(
                    user_id="user-graph",
                    test_type="short_reading",
                    words_total=80,
                    words_read_correctly=76,
                    errors_count=4,
                    duration_ms=80_000,
                    wpm=60.0,
                    accuracy=0.95,
                    support_level="low",
                    disclaimer="Not a diagnosis.",
                    created_at=datetime(2026, 5, 2, tzinfo=timezone.utc),
                ),
            ]
        )
        db.commit()

        summary = build_progress_summary(db, "user-graph")

        assert summary["daily_wpm"] == [
            {"date": "2026-05-01", "wpm": 66.0, "accuracy": 0.85},
            {"date": "2026-05-02", "wpm": 60.0, "accuracy": 0.95},
        ]
    finally:
        db.close()
