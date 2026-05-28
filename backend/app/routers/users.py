from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from .. import models, schemas
from ..database import get_db
from ..services.progress_service import get_or_create_skill_profile


router = APIRouter(prefix="/v1/users", tags=["users"])


@router.post("", response_model=schemas.UserResponse)
def create_user(payload: schemas.CreateUserRequest, db: Session = Depends(get_db)) -> schemas.UserResponse:
    user = models.UserProfile(
        display_name=payload.display_name,
        age=payload.age,
        language_preference=payload.language_preference,
    )
    db.add(user)
    db.flush()
    skill_profile = get_or_create_skill_profile(db, user.id)
    db.commit()
    db.refresh(user)
    db.refresh(skill_profile)
    return to_user_response(user, skill_profile)


@router.get("/{user_id}", response_model=schemas.UserResponse)
def get_user(user_id: str, db: Session = Depends(get_db)) -> schemas.UserResponse:
    user = db.get(models.UserProfile, user_id)
    if user is None:
        raise HTTPException(status_code=404, detail="User not found")
    skill_profile = get_or_create_skill_profile(db, user_id)
    db.commit()
    db.refresh(skill_profile)
    return to_user_response(user, skill_profile)


@router.patch("/{user_id}/language", response_model=schemas.UserResponse)
def update_user_language(
    user_id: str,
    payload: schemas.UpdateUserLanguageRequest,
    db: Session = Depends(get_db),
) -> schemas.UserResponse:
    user = db.get(models.UserProfile, user_id)
    if user is None:
        raise HTTPException(status_code=404, detail="User not found")

    user.language_preference = payload.language_preference
    skill_profile = get_or_create_skill_profile(db, user_id)
    db.commit()
    db.refresh(user)
    db.refresh(skill_profile)
    return to_user_response(user, skill_profile)


def to_user_response(user: models.UserProfile, skill_profile: models.UserSkillProfile) -> schemas.UserResponse:
    return schemas.UserResponse(
        user_id=user.id,
        display_name=user.display_name,
        age=user.age,
        language_preference=user.language_preference,
        skill_profile=schemas.SkillProfileResponse(
            phonological_skill=skill_profile.phonological_skill,
            decoding_fluency=skill_profile.decoding_fluency,
            visual_tracking=skill_profile.visual_tracking,
            current_difficulty=skill_profile.current_difficulty,
            updated_at=skill_profile.updated_at,
        ),
    )
