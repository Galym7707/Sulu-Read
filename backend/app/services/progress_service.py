from collections import defaultdict
from datetime import datetime
from statistics import median

from sqlalchemy import desc
from sqlalchemy.orm import Session

from .. import models


MORPHOLOGY_EXERCISE_TYPES = {"root_suffix_identification", "word_segmentation", "morphology"}


def clamp(value: float, lower: float = 0.0, upper: float = 1.0) -> float:
    return min(upper, max(lower, value))


def update_skill_profile_after_attempt(
    skill_profile: models.UserSkillProfile,
    recent_attempts: list[models.ExerciseAttempt],
    is_correct: bool,
    response_time_ms: int,
    exercise_type: str | None = None,
    sub_exercise: str | None = None,
) -> models.UserSkillProfile:
    phonological_skill = skill_profile.phonological_skill if skill_profile.phonological_skill is not None else 0.50
    decoding_fluency = skill_profile.decoding_fluency if skill_profile.decoding_fluency is not None else 0.50
    visual_tracking = skill_profile.visual_tracking if skill_profile.visual_tracking is not None else 0.50
    current_difficulty = skill_profile.current_difficulty if skill_profile.current_difficulty is not None else 1

    attempt_score = 1.0 if is_correct else 0.0
    if response_time_ms > 10_000:
        attempt_score *= 0.85

    is_morphology = exercise_type in MORPHOLOGY_EXERCISE_TYPES or sub_exercise in MORPHOLOGY_EXERCISE_TYPES
    phonological_weight = 0.30 if is_morphology else 0.25
    decoding_weight = 0.24 if is_morphology else 0.20

    skill_profile.phonological_skill = clamp(
        phonological_skill * (1 - phonological_weight) + attempt_score * phonological_weight
    )
    skill_profile.decoding_fluency = clamp(decoding_fluency * (1 - decoding_weight) + attempt_score * decoding_weight)
    skill_profile.visual_tracking = clamp(visual_tracking * 0.90 + attempt_score * 0.10)
    skill_profile.current_difficulty = current_difficulty

    considered_attempts = recent_attempts[-10:]
    if considered_attempts:
        accuracy = sum(1 for attempt in considered_attempts if attempt.is_correct) / len(considered_attempts)
        response_times = [attempt.response_time_ms for attempt in considered_attempts]
        median_response_time_ms = median(response_times)

        if accuracy >= 0.80 and median_response_time_ms < 7_000:
            skill_profile.current_difficulty = min(5, current_difficulty + 1)
        elif accuracy < 0.50:
            skill_profile.current_difficulty = max(1, current_difficulty - 1)

    skill_profile.updated_at = models.utc_now()
    return skill_profile


def get_or_create_skill_profile(db: Session, user_id: str) -> models.UserSkillProfile:
    skill_profile = db.get(models.UserSkillProfile, user_id)
    if skill_profile is not None:
        return skill_profile

    skill_profile = models.UserSkillProfile(user_id=user_id)
    db.add(skill_profile)
    db.flush()
    return skill_profile


def record_exercise_attempt(db: Session, payload) -> tuple[models.ExerciseAttempt, models.UserSkillProfile]:
    is_correct = normalize_answer(payload.correct_answer) == normalize_answer(payload.user_answer)
    attempt = models.ExerciseAttempt(
        user_id=payload.user_id,
        exercise_type=payload.exercise_type,
        sub_exercise=payload.sub_exercise,
        target_word=payload.target_word,
        correct_answer=payload.correct_answer,
        user_answer=payload.user_answer,
        is_correct=is_correct,
        response_time_ms=payload.response_time_ms,
        difficulty_level=payload.difficulty_level,
        language_hint=payload.language_hint,
    )
    db.add(attempt)
    db.flush()

    skill_profile = get_or_create_skill_profile(db, payload.user_id)
    recent_attempts = (
        db.query(models.ExerciseAttempt)
        .filter(models.ExerciseAttempt.user_id == payload.user_id)
        .order_by(desc(models.ExerciseAttempt.created_at))
        .limit(10)
        .all()
    )
    update_skill_profile_after_attempt(
        skill_profile=skill_profile,
        recent_attempts=list(reversed(recent_attempts)),
        is_correct=is_correct,
        response_time_ms=payload.response_time_ms,
        exercise_type=payload.exercise_type,
        sub_exercise=payload.sub_exercise,
    )
    db.commit()
    db.refresh(attempt)
    db.refresh(skill_profile)
    return attempt, skill_profile


def normalize_answer(answer: str) -> str:
    return answer.strip().lower().replace(" ", "")


def build_progress_summary(db: Session, user_id: str) -> dict:
    skill_profile = get_or_create_skill_profile(db, user_id)
    attempts = (
        db.query(models.ExerciseAttempt)
        .filter(models.ExerciseAttempt.user_id == user_id)
        .order_by(models.ExerciseAttempt.created_at)
        .all()
    )
    screenings = (
        db.query(models.ScreeningResult)
        .filter(models.ScreeningResult.user_id == user_id)
        .order_by(desc(models.ScreeningResult.created_at))
        .limit(5)
        .all()
    )
    screenings_for_graph = (
        db.query(models.ScreeningResult)
        .filter(models.ScreeningResult.user_id == user_id)
        .order_by(models.ScreeningResult.created_at)
        .all()
    )

    total_exercises = len(attempts)
    exercise_accuracy = (
        sum(1 for attempt in attempts if attempt.is_correct) / total_exercises
        if total_exercises
        else 0.0
    )
    average_response_time_ms = (
        int(sum(attempt.response_time_ms for attempt in attempts) / total_exercises)
        if total_exercises
        else 0
    )
    latest_support_level = screenings[0].support_level if screenings else None

    daily_counts: dict[str, dict[str, int]] = defaultdict(lambda: {"exercises": 0, "screenings": 0})
    for attempt in attempts[-30:]:
        daily_counts[date_key(attempt.created_at)]["exercises"] += 1
    for screening in screenings:
        daily_counts[date_key(screening.created_at)]["screenings"] += 1

    daily_graph: dict[str, dict[str, float]] = defaultdict(lambda: {"wpm": 0.0, "accuracy": 0.0, "count": 0.0})
    for screening in screenings_for_graph[-30:]:
        key = date_key(screening.created_at)
        daily_graph[key]["wpm"] += screening.wpm
        daily_graph[key]["accuracy"] += screening.accuracy
        daily_graph[key]["count"] += 1

    return {
        "user_id": user_id,
        "total_exercises": total_exercises,
        "exercise_accuracy": round(exercise_accuracy, 3),
        "average_response_time_ms": average_response_time_ms,
        "latest_support_level": latest_support_level,
        "skill_profile": skill_profile,
        "recent_screenings": screenings,
        "daily_activity": [
            {"date": key, **value}
            for key, value in sorted(daily_counts.items(), reverse=True)[:14]
        ],
        "daily_wpm": [
            {
                "date": key,
                "wpm": round(value["wpm"] / value["count"], 2),
                "accuracy": round(value["accuracy"] / value["count"], 3),
            }
            for key, value in sorted(daily_graph.items())
            if value["count"] > 0
        ],
    }


def date_key(value: datetime) -> str:
    return value.date().isoformat()
