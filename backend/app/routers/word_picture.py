from fastapi import APIRouter

from .. import schemas
from ..services.word_picture_service import find_picture, looks_like_noun

router = APIRouter(prefix="/v1/word-picture", tags=["word-picture"])


@router.get("", response_model=schemas.WordPictureResponse)
def get_word_picture(word: str, language_hint: str = "kk") -> dict:
    """A picture for a tapped word, or a plain "no picture" answer.

    Not finding one is an ordinary outcome, not an error: most words in any sentence are not
    concrete nouns. The client shows nothing in that case, so this never returns a failure
    status for it.
    """
    if not looks_like_noun(word):
        return {"status": "success", "found": False, "is_noun": False}

    picture = find_picture(word, language_hint)
    if picture is None:
        return {"status": "success", "found": False, "is_noun": True}

    return {
        "status": "success",
        "found": True,
        "is_noun": True,
        "word": picture.word,
        "image_url": picture.image_url,
        "page_url": picture.page_url,
        "attribution": picture.attribution,
        "license_name": picture.license_name,
    }
