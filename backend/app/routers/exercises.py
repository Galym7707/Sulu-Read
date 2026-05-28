from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from .. import models, schemas
from ..database import get_db
from ..services.exercise_generator import generate_exercises
from ..services.progress_service import get_or_create_skill_profile, record_exercise_attempt


router = APIRouter(prefix="/v1/exercises", tags=["exercises"])


@router.post("/generate", response_model=list[schemas.ExerciseResponse])
def generate_training_exercises(
    payload: schemas.GenerateExerciseRequest,
    db: Session = Depends(get_db),
) -> list[dict]:
    if db.get(models.UserProfile, payload.user_id) is None:
        raise HTTPException(status_code=404, detail="User not found")

    skill_profile = get_or_create_skill_profile(db, payload.user_id)
    db.commit()
    return generate_exercises(
        source_words=payload.source_words,
        exercise_type=payload.exercise_type,
        count=payload.count,
        difficulty_level=skill_profile.current_difficulty,
        language_hint=payload.language_hint,
    )


@router.post("/attempt", response_model=schemas.ExerciseAttemptResponse)
def submit_exercise_attempt(
    payload: schemas.ExerciseAttemptRequest,
    db: Session = Depends(get_db),
) -> schemas.ExerciseAttemptResponse:
    if db.get(models.UserProfile, payload.user_id) is None:
        raise HTTPException(status_code=404, detail="User not found")

    attempt, skill_profile = record_exercise_attempt(db, payload)
    feedback = "Дұрыс! Жақсы жұмыс." if attempt.is_correct else "Попробуй ещё раз. Келесі сөзге өтейік."
    return schemas.ExerciseAttemptResponse(
        is_correct=attempt.is_correct,
        updated_difficulty=skill_profile.current_difficulty,
        skill_profile=schemas.SkillProfileResponse(
            phonological_skill=skill_profile.phonological_skill,
            decoding_fluency=skill_profile.decoding_fluency,
            visual_tracking=skill_profile.visual_tracking,
            current_difficulty=skill_profile.current_difficulty,
            updated_at=skill_profile.updated_at,
        ),
        feedback=feedback,
    )
