import json
import re
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import main
from backend.app.services.catalog_service import list_books, load_book
from backend.app.services.word_picture_service import looks_like_noun, normalize_word

CATALOG_DIRECTORY = Path("backend/app/data/catalog")


@pytest.fixture(scope="module")
def client():
    with TestClient(main.app) as test_client:
        yield test_client


def test_catalog_has_books_in_both_languages():
    languages = {book["language"] for book in list_books()}
    assert "kk" in languages
    assert "ru" in languages


def test_every_indexed_book_can_be_loaded():
    for summary in list_books():
        book = load_book(summary["id"])
        assert book is not None, summary["id"]
        assert book["page_count"] == len(book["pages"])
        assert all(page["text"].strip() for page in book["pages"])


def test_every_book_records_where_it_came_from():
    # The texts are public domain but the editions are CC BY-SA, so a book that cannot say
    # where it came from must not be in the catalogue.
    for summary in list_books():
        assert summary["source_url"].startswith("https://")
        assert summary["work_license"]
        assert summary["edition_license"]


def test_no_book_carries_editorial_apparatus():
    # Wikisource prints variant readings, footnotes and verse line numbers around the text. A
    # child reading aloud would read them out as if they were the poem.
    forbidden = ("Примечания", "Варианты", "Ескертпе", "Эта страница содержит")
    for path in CATALOG_DIRECTORY.glob("*.json"):
        if path.name == "index.json":
            continue
        book = json.loads(path.read_text(encoding="utf-8"))
        whole = "\n".join(page["text"] for page in book["pages"])
        for marker in forbidden:
            assert marker not in whole, f"{path.name} contains {marker}"
        assert not re.search(r"(?m)^\s*\d{1,4}\s*$", whole), f"{path.name} has a bare number line"


def test_catalog_endpoint_filters_by_language(client):
    response = client.get("/v1/catalog", params={"language_hint": "kk"})
    body = response.json()
    assert body["status"] == "success"
    assert body["count"] > 0
    assert {book["language"] for book in body["books"]} == {"kk"}


def test_catalog_book_endpoint_returns_pages(client):
    listed = client.get("/v1/catalog", params={"language_hint": "ru"}).json()["books"]
    book_id = listed[0]["id"]
    body = client.get(f"/v1/catalog/{book_id}").json()
    assert body["status"] == "success"
    assert body["pages"]
    assert [page["page_number"] for page in body["pages"]] == list(
        range(1, len(body["pages"]) + 1)
    )


def test_unknown_book_is_a_404(client):
    assert client.get("/v1/catalog/does-not-exist").status_code == 404


def test_book_id_cannot_escape_the_catalog_directory():
    # The id arrives from the network and becomes a filename.
    assert load_book("../../../etc/passwd") is None
    assert load_book("..") is None
    assert load_book("") is None


def test_noun_filter_accepts_concrete_nouns():
    for word in ("кошка", "дерево", "мальчик", "кітап", "ағаш", "school", "cat"):
        assert looks_like_noun(word), word


def test_noun_filter_rejects_other_parts_of_speech():
    # A picture beside a verb or an adjective is a wrong answer, not a missing one.
    for word in ("бежать", "красивый", "running", "beautiful", "и", "the", "я"):
        assert not looks_like_noun(word), word


def test_noun_filter_ignores_punctuation_and_case():
    assert normalize_word("«Кошка»,") == "кошка"
    assert looks_like_noun("«Кошка»,")


def test_word_picture_endpoint_reports_non_nouns_without_failing(client):
    body = client.get("/v1/word-picture", params={"word": "бежать", "language_hint": "ru"}).json()
    # Not finding a picture is an ordinary outcome; most words are not concrete nouns.
    assert body["status"] == "success"
    assert body["found"] is False
    assert body["is_noun"] is False
