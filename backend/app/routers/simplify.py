from fastapi import APIRouter

from .. import schemas
from ..services.simplification_service import simplify_text


router = APIRouter(prefix="/v1/simplify", tags=["simplify"])


@router.post("", response_model=schemas.SimplifyResponse)
def simplify(payload: schemas.SimplifyRequest) -> schemas.SimplifyResponse:
    return schemas.SimplifyResponse(
        status="success",
        simplified_text=simplify_text(payload.text, payload.language_hint),
    )
