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
    finally:
        db.close()
