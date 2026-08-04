from fastapi import APIRouter, HTTPException

from .. import schemas
from ..services.catalog_service import list_books, load_book

router = APIRouter(prefix="/v1/catalog", tags=["catalog"])


@router.get("", response_model=schemas.CatalogListResponse)
def get_catalog(language_hint: str | None = None) -> dict:
    books = list_books(language_hint)
    return {"status": "success", "count": len(books), "books": books}


@router.get("/{book_id}", response_model=schemas.CatalogBookResponse)
def get_catalog_book(book_id: str) -> dict:
    book = load_book(book_id)
    if book is None:
        raise HTTPException(status_code=404, detail="Book not found")
    return {"status": "success", **book}
