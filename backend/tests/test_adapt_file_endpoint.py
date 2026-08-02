import io
import zipfile

import pytest
from fastapi.testclient import TestClient

import main


@pytest.fixture(scope="module")
def client():
    with TestClient(main.app) as test_client:
        yield test_client


def _docx(paragraphs: list[str]) -> bytes:
    body = "".join(f"<w:p><w:r><w:t>{p}</w:t></w:r></w:p>" for p in paragraphs)
    document = f'<?xml version="1.0"?><w:document xmlns:w="x"><w:body>{body}</w:body></w:document>'
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("word/document.xml", document)
    return buffer.getvalue()


def _text_pdf(pages: list[str]) -> bytes:
    fitz = pytest.importorskip("fitz")
    document = fitz.open()
    for text in pages:
        page = document.new_page()
        page.insert_text((72, 100), text, fontsize=14)
    data = document.tobytes()
    document.close()
    return data


def test_txt_upload_returns_one_page(client):
    response = client.post(
        "/v1/adapt-file",
        files={"file": ("kitap.txt", "Бала кітап оқыды.".encode("utf-8"), "text/plain")},
        data={"language_hint": "kk"},
    )
    body = response.json()
    assert body["status"] == "success"
    assert body["page_count"] == 1
    assert body["pages"][0]["page_number"] == 1
    assert "Бала кітап оқыды." in body["pages"][0]["original_text"]
    assert body["truncated"] is False


def test_long_txt_is_split_into_several_pages(client):
    paragraph = "Бала кітап оқыды. " * 40
    response = client.post(
        "/v1/adapt-file",
        files={"file": ("kitap.txt", ("\n\n".join([paragraph] * 6)).encode("utf-8"), "text/plain")},
        data={"language_hint": "kk"},
    )
    body = response.json()
    assert body["page_count"] > 1
    assert [page["page_number"] for page in body["pages"]] == list(range(1, body["page_count"] + 1))
    assert all(page["word_count"] > 0 for page in body["pages"])


def test_docx_upload_is_read(client):
    response = client.post(
        "/v1/adapt-file",
        files={
            "file": (
                "kitap.docx",
                _docx(["Бірінші абзац.", "Екінші абзац."]),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            )
        },
        data={"language_hint": "kk"},
    )
    body = response.json()
    assert body["status"] == "success"
    assert "Бірінші абзац." in body["pages"][0]["original_text"]


def test_pdf_keeps_its_own_page_boundaries(client):
    # A PDF already knows where its pages are, so the character pager must not re-split it.
    data = _text_pdf(["Page one text here.", "Page two text here.", "Page three text here."])
    response = client.post(
        "/v1/adapt-file",
        files={"file": ("kitap.pdf", data, "application/pdf")},
        data={"language_hint": "en"},
    )
    body = response.json()
    assert body["status"] == "success"
    assert body["page_count"] == 3
    assert "Page one" in body["pages"][0]["original_text"]
    assert "Page two" in body["pages"][1]["original_text"]
    assert body["ocr_page_numbers"] == []


def test_unsupported_extension_is_rejected_in_the_requested_language(client):
    for language, needle in (("en", "Could not read"), ("ru", "Не удалось"), ("kk", "мүмкін болмады")):
        response = client.post(
            "/v1/adapt-file",
            files={"file": ("photo.jpg", b"not a book", "image/jpeg")},
            data={"language_hint": language},
        )
        body = response.json()
        assert body["status"] == "error"
        assert needle in body["message"]


def test_empty_file_is_rejected(client):
    response = client.post(
        "/v1/adapt-file",
        files={"file": ("kitap.txt", b"", "text/plain")},
        data={"language_hint": "kk"},
    )
    assert response.json()["status"] == "error"


def test_adapt_url_still_builds_its_word_list(monkeypatch, client):
    # Regression: the word payload lost two fields when syllables were removed, and the
    # response model still required them, which broke this endpoint at runtime rather than
    # at import.
    monkeypatch.setattr(main, "extract_text_from_url", lambda url: ("Title", "Бала кітап оқыды."))
    response = client.post(
        "/v1/adapt-url",
        json={"url": "https://example.com/a", "language_hint": "kk"},
    )
    body = response.json()
    assert body["status"] == "success"
    assert body["words"]
    assert body["words"][0]["original"]
    assert "language_hint" in body["words"][0]
