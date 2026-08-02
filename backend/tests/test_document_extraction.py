import io
import zipfile

from backend.app.services.document_extraction import (
    PAGE_CHARACTER_BUDGET,
    extract_docx_text,
    extract_epub_text,
    extract_plain_text,
    is_supported_document,
    pages_from_document,
    split_into_pages,
)


def test_supported_extensions():
    assert is_supported_document("kitap.pdf")
    assert is_supported_document("KITAP.PDF")
    assert is_supported_document("book.epub")
    assert not is_supported_document("photo.jpg")
    assert not is_supported_document(None)
    assert not is_supported_document("noextension")


def test_short_text_is_a_single_page():
    assert split_into_pages("Кот спит. Пёс бежит.") == ["Кот спит. Пёс бежит."]


def test_blank_text_has_no_pages():
    assert split_into_pages("   \n\n  ") == []


def test_pages_break_between_paragraphs():
    paragraph = "А" * 800
    pages = split_into_pages(f"{paragraph}\n\n{paragraph}\n\n{paragraph}")
    assert len(pages) == 3
    assert all(page == paragraph for page in pages)


def test_small_paragraphs_share_a_page():
    pages = split_into_pages("\n\n".join(["Бір сөйлем."] * 5))
    assert len(pages) == 1
    assert pages[0].count("Бір сөйлем.") == 5


def test_long_paragraph_splits_on_sentence_boundaries():
    sentence = "Бала кітап оқыды. "
    pages = split_into_pages(sentence * 200)
    assert len(pages) > 1
    # Every page must end on a sentence, never mid-sentence.
    assert all(page.rstrip().endswith(".") for page in pages)


def test_no_page_ever_splits_a_word():
    text = " ".join(f"сөз{index}" for index in range(2000))
    pages = split_into_pages(text)
    rejoined = " ".join(pages).split()
    assert rejoined == text.split()


def test_a_single_enormous_sentence_still_paginates():
    # No sentence end anywhere, so the sentence splitter cannot help and the word-level
    # fallback has to carry it.
    text = "сөз " * 3000
    pages = split_into_pages(text)
    assert len(pages) > 1
    assert all(len(page) <= PAGE_CHARACTER_BUDGET * 2 for page in pages)


def test_plain_text_decodes_cyrillic_in_legacy_encoding():
    assert extract_plain_text("Кітап".encode("cp1251")) == "Кітап"
    assert extract_plain_text("Кітап".encode("utf-8")) == "Кітап"


def _docx_bytes(paragraphs: list[str]) -> bytes:
    body = "".join(f"<w:p><w:r><w:t>{p}</w:t></w:r></w:p>" for p in paragraphs)
    document = f'<?xml version="1.0"?><w:document xmlns:w="x"><w:body>{body}</w:body></w:document>'
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("word/document.xml", document)
    return buffer.getvalue()


def test_docx_paragraphs_are_extracted_in_order():
    data = _docx_bytes(["Бірінші абзац.", "Екінші абзац."])
    assert extract_docx_text(data) == "Бірінші абзац.\n\nЕкінші абзац."


def test_docx_goes_through_the_pager():
    pages, paginated = pages_from_document("kitap.docx", _docx_bytes(["Қысқа мәтін."]))
    assert paginated
    assert pages == ["Қысқа мәтін."]


def _epub_bytes(sections: list[str]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for index, section in enumerate(sections):
            archive.writestr(
                f"OEBPS/chapter{index:03d}.xhtml",
                f"<html><body><p>{section}</p></body></html>",
            )
    return buffer.getvalue()


def test_epub_sections_are_concatenated_in_filename_order():
    text = extract_epub_text(_epub_bytes(["Бірінші тарау.", "Екінші тарау."]))
    assert "Бірінші тарау." in text
    assert text.index("Бірінші тарау.") < text.index("Екінші тарау.")


def test_unsupported_extension_yields_nothing():
    assert pages_from_document("photo.jpg", b"") == ([], False)
