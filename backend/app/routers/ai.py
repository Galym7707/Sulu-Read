from fastapi import APIRouter

from .. import schemas
from ..services.ai_generation_service import SAFE_AI_ERROR, AiProviderError, generate_ai_response


router = APIRouter(prefix="/ai", tags=["ai"])


@router.post(
    "/generate",
    response_model=schemas.AiGenerateResponse | schemas.AiGenerateErrorResponse,
)
def generate_ai(payload: schemas.AiGenerateRequest) -> schemas.AiGenerateResponse | schemas.AiGenerateErrorResponse:
    try:
        result = generate_ai_response(payload)
    except AiProviderError:
        return schemas.AiGenerateErrorResponse(error=SAFE_AI_ERROR)

    return schemas.AiGenerateResponse(
        provider=result.provider,
        model=result.model,
        result=result.result,
    )
