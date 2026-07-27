from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from .. import models, schemas
from ..database import get_db
from ..services.screening_service import calculate_reading_screening


router = APIRouter(prefix="/v1/screening", tags=["screening"])


@router.post("/reading-test", response_model=schemas.ReadingTestResponse)
def submit_reading_test(
    payload: schemas.ReadingTestRequest,
    db: Session = Depends(get_db),
) -> schemas.ReadingTestResponse:
    if db.get(models.UserProfile, payload.user_id) is None:
        raise HTTPException(status_code=404, detail="User not found")

    calculated = calculate_reading_screening(
        words_total=payload.words_total,
        words_read_correctly=payload.words_read_correctly,
        duration_ms=payload.duration_ms,
    )
    result = models.ScreeningResult(
        user_id=payload.user_id,
        test_type=payload.test_type,
        words_total=payload.words_total,
        words_read_correctly=payload.words_read_correctly,
        errors_count=payload.errors_count,
        duration_ms=payload.duration_ms,
        wpm=calculated["wpm"],
        accuracy=calculated["accuracy"],
        support_level=calculated["support_level"],
        disclaimer=calculated["disclaimer"],
    )
    db.add(result)
    db.commit()
    return schemas.ReadingTestResponse(**calculated)
