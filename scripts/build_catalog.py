"""Builds the ready-to-read catalogue from the declared public-domain sources.

Run from the repository root:

    python -m scripts.build_catalog

Each work is fetched once and then goes through the app's own pipeline — the same OCR-agnostic
text preparation and the same pager the file upload uses — so a catalogue book is divided into
pages exactly like a book the reader uploads. The result is committed, so the app ships with the
work already done and never fetches anything at runtime.

Sources that cannot be fetched are reported and skipped. A catalogue entry with invented text
would be worse than a missing one, so nothing is written for a source that did not come back.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from backend.app.services.document_extraction import split_into_pages  # noqa: E402
from backend.app.services.text_preparation import prepare_text_for_adaptation  # noqa: E402
from scripts.catalog_sources import CatalogSource, public_domain_sources  # noqa: E402
from scripts.wikisource_fetch import fetch_text  # noqa: E402

CATALOG_DIRECTORY = Path("backend/app/data/catalog")
INDEX_FILE = CATALOG_DIRECTORY / "index.json"

# Wikisource text is CC BY-SA 4.0 as an edition, while the works themselves are public domain.
# Both facts travel with every book so the app can show them.
EDITION_LICENSE = "CC BY-SA 4.0"
WORK_LICENSE = "Public domain"

MINIMUM_BOOK_WORDS = 12


def build_book(source: CatalogSource) -> dict | None:
    fetched = fetch_text(source.host, source.page)
    if fetched is None:
        return None

    prepared = prepare_text_for_adaptation(fetched.text, source="text")
    # The fetched text opens with the author's name as a heading on many Wikisource pages, and
    # repeating it as the first line of page one just wastes a line the reader has to skip.
    if prepared.startswith(source.author):
        prepared = prepared[len(source.author):].lstrip()

    pages = split_into_pages(prepared)
    if not pages:
        return None

    # A page that extracts to almost nothing is a stub or a redirect landing, not a work. Better
    # absent from the catalogue than present and empty.
    if sum(len(page.split()) for page in pages) < MINIMUM_BOOK_WORDS:
        return None

    return {
        "id": source.book_id,
        "title": source.title,
        "author": source.author,
        "language": source.language,
        "grade": source.grade,
        "page_count": len(pages),
        "word_count": sum(len(page.split()) for page in pages),
        "source_url": fetched.source_url,
        "work_license": WORK_LICENSE,
        "edition_license": EDITION_LICENSE,
        "pages": [
            {"page_number": number, "text": text}
            for number, text in enumerate(pages, start=1)
        ],
    }


def main() -> int:
    CATALOG_DIRECTORY.mkdir(parents=True, exist_ok=True)
    index: list[dict] = []
    failures: list[str] = []

    for source in public_domain_sources():
        try:
            book = build_book(source)
        except Exception as error:  # noqa: BLE001 - one bad source must not stop the build
            book = None
            print(f"  ERROR {source.book_id}: {type(error).__name__}: {error}")

        if book is None:
            failures.append(source.book_id)
            print(f"  SKIP  {source.book_id} ({source.host}/{source.page})")
            continue

        (CATALOG_DIRECTORY / f"{source.book_id}.json").write_text(
            json.dumps(book, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        index.append(
            {
                key: book[key]
                for key in (
                    "id",
                    "title",
                    "author",
                    "language",
                    "grade",
                    "page_count",
                    "word_count",
                    "source_url",
                    "work_license",
                    "edition_license",
                )
            }
        )
        print(f"  OK    {source.book_id}: {book['page_count']} pages, {book['word_count']} words")

    index.sort(key=lambda entry: (entry["language"], entry["grade"], entry["title"]))
    INDEX_FILE.write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"\n{len(index)} books written, {len(failures)} skipped")
    if failures:
        print("skipped:", ", ".join(failures))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
