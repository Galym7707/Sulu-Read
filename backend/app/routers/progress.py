from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from .. import models, schemas
from ..database import get_db
from ..services.progress_service import build_progress_summary


router = APIRouter(prefix="/v1/progress", tags=["progress"])


@router.get("/{user_id}", response_model=schemas.ProgressResponse)
def get_progress(user_id: str, db: Session = Depends(get_db)) -> dict:
    if db.get(models.UserProfile, user_id) is None:
        raise HTTPException(status_code=404, detail="User not found")

    summary = build_progress_summary(db, user_id)
    skill_profile = summary["skill_profile"]
    return {
        "user_id": summary["user_id"],
        "total_exercises": summary["total_exercises"],
        "exercise_accuracy": summary["exercise_accuracy"],
        "average_response_time_ms": summary["average_response_time_ms"],
        "latest_support_level": summary["latest_support_level"],
        "skill_profile": {
            "phonological_skill": skill_profile.phonological_skill,
            "decoding_fluency": skill_profile.decoding_fluency,
            "visual_tracking": skill_profile.visual_tracking,
            "current_difficulty": skill_profile.current_difficulty,
            "updated_at": skill_profile.updated_at,
        },
        "recent_screenings": [
            {
                "id": screening.id,
                "test_type": screening.test_type,
                "words_total": screening.words_total,
                "words_read_correctly": screening.words_read_correctly,
                "errors_count": screening.errors_count,
                "duration_ms": screening.duration_ms,
                "wpm": screening.wpm,
                "accuracy": screening.accuracy,
                "support_level": screening.support_level,
                "disclaimer": screening.disclaimer,
                "created_at": screening.created_at,
            }
            for screening in summary["recent_screenings"]
        ],
        "daily_activity": summary["daily_activity"],
        "daily_wpm": summary["daily_wpm"],
    }
