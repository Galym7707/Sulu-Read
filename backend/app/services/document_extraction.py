"""Turns an uploaded book file into pages of readable text.

Format handling is deliberately split in two. Extracting the raw text of each format needs a
parser per format and is only exercised with real files; deciding where one page ends and the
next begins is pure string work and is unit tested. Keeping them apart means the interesting
half does not need a PDF on disk to test.

Parser imports are function-local. easyocr and PyMuPDF together already make this image a slow
start, and a reader who only ever opens .txt files should not wait for a PDF library to load.
"""

from __future__ import annotations

import io
import re
import zipfile
from dataclasses import dataclass


# A page of a children's book, roughly. Long enough that turning the page is not constant, short
# enough that the reader can see where the end is. Only used for formats that have no pages of
# their own — a PDF already knows where its pages are and is never re-split.
PAGE_CHARACTER_BUDGET = 1400

# A page will overrun the budget rather than split a paragraph, but only this far. Past it the
# paragraph is split at a sentence end instead, so one runaway paragraph cannot become a page
# the reader has to scroll through.
PAGE_OVERRUN_ALLOWANCE = 600

SUPPORTED_EXTENSIONS = (".pdf", ".docx", ".txt", ".epub", ".fb2", ".htm", ".html")

# Below this, a PDF page is worth *offering* to OCR: a scanned page is not reliably empty, since
# the layer often holds a stray header or a page number. It is only a hint. The extracted text is
# never discarded on the strength of it — a page that genuinely holds one short line is common in
# a children's book, and replacing that line with an OCR guess would be strictly worse.
SPARSE_TEXT_LAYER_CHARACTERS = 40


@dataclass(frozen=True)
class DocumentPage:
    page_number: int
    text: str


def extension_of(filename: str | None) -> str:
    if not filename or "." not in filename:
        return ""
    return "." + filename.rsplit(".", 1)[1].lower()


def is_supported_document(filename: str | None) -> bool:
    return extension_of(filename) in SUPPORTED_EXTENSIONS


def normalize_whitespace(text: str) -> str:
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"[ \t\f\v]+", " ", text)
    text = re.sub(r" *\n *", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def split_into_pages(text: str, budget: int = PAGE_CHARACTER_BUDGET) -> list[str]:
    """Splits continuous text into pages at the nearest paragraph, then sentence, boundary.

    Never splits mid-word. A reader who loses the second half of a word at a page turn has to
    hold it in memory across the turn, which is the thing this app exists to avoid.
    """
    normalized = normalize_whitespace(text)
    if not normalized:
        return []

    pages: list[str] = []
    current: list[str] = []
    current_length = 0

    for paragraph in normalized.split("\n\n"):
        paragraph = paragraph.strip()
        if not paragraph:
            continue

        for chunk in _fit_paragraph(paragraph, budget):
            chunk_length = len(chunk)
            if current and current_length + chunk_length > budget:
                pages.append("\n\n".join(current))
                current = [chunk]
                current_length = chunk_length
                continue
            current.append(chunk)
            current_length += chunk_length + 2

    if current:
        pages.append("\n\n".join(current))
    return pages


def _fit_paragraph(paragraph: str, budget: int) -> list[str]:
    """Breaks a paragraph too long to sit on one page into sentence-aligned pieces."""
    if len(paragraph) <= budget + PAGE_OVERRUN_ALLOWANCE:
        return [paragraph]

    sentences = re.split(r"(?<=[.!?…])\s+", paragraph)
    pieces: list[str] = []
    current = ""
    for sentence in sentences:
        if current and len(current) + len(sentence) + 1 > budget:
            pieces.append(current.strip())
            current = sentence
            continue
        current = f"{current} {sentence}".strip() if current else sentence

    if current.strip():
        pieces.append(current.strip())

    # A single sentence longer than a page still has to go somewhere. Split it on whitespace so
    # the break lands between words rather than inside one.
    fitted: list[str] = []
    for piece in pieces:
        while len(piece) > budget + PAGE_OVERRUN_ALLOWANCE:
            cut = piece.rfind(" ", 0, budget)
            if cut <= 0:
                break
            fitted.append(piece[:cut].strip())
            piece = piece[cut:].strip()
        if piece:
            fitted.append(piece)
    return fitted


def extract_plain_text(data: bytes) -> str:
    for encoding in ("utf-8", "utf-8-sig", "cp1251", "latin-1"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


def strip_html(markup: str) -> str:
    from bs4 import BeautifulSoup

    soup = BeautifulSoup(markup, "html.parser")
    for tag in soup(["script", "style"]):
        tag.decompose()
    return soup.get_text("\n")


def extract_docx_text(data: bytes) -> str:
    """Reads paragraph text straight from the document part.

    python-docx is not used here on purpose: it is another dependency for one XML file, and a
    .docx is a zip whose word/document.xml already holds the paragraphs in order.
    """
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        with archive.open("word/document.xml") as document:
            markup = document.read().decode("utf-8", errors="replace")

    # w:p is a paragraph and w:tab/w:br are breaks inside one; turning them into newlines before
    # dropping the remaining tags is what keeps the paragraph structure the pager needs.
    markup = re.sub(r"</w:p>", "\n\n", markup)
    markup = re.sub(r"<w:(?:tab|br)\b[^>]*/?>", " ", markup)
    text = re.sub(r"<[^>]+>", "", markup)
    return normalize_whitespace(text)


def extract_epub_text(data: bytes) -> str:
    sections: list[str] = []
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = [
            name
            for name in archive.namelist()
            if name.lower().endswith((".xhtml", ".html", ".htm"))
        ]
        # Reading order is defined by the spine in the OPF, but sorted filenames match it in
        # practice for the generated EPUBs this sees, and a wrong order is recoverable by the
        # reader while a hard failure is not.
        for name in sorted(names):
            with archive.open(name) as section:
                sections.append(strip_html(section.read().decode("utf-8", errors="replace")))
    return normalize_whitespace("\n\n".join(sections))


def extract_fb2_text(data: bytes) -> str:
    markup = extract_plain_text(data)
    markup = re.sub(r"</(?:p|section|title)>", "\n\n", markup)
    return normalize_whitespace(re.sub(r"<[^>]+>", "", markup))


def is_sparse_text_layer(text: str) -> bool:
    """Whether a page holds so little text that it is probably a scan."""
    return len(text.strip()) < SPARSE_TEXT_LAYER_CHARACTERS


def extract_pdf_pages(data: bytes) -> list[str]:
    """Text layer of each PDF page, in order, exactly as the file gives it."""
    import fitz

    pages: list[str] = []
    with fitz.open(stream=data, filetype="pdf") as document:
        for page in document:
            pages.append(normalize_whitespace(page.get_text()))
    return pages


def render_pdf_page_to_png(data: bytes, page_index: int, zoom: float = 2.0) -> bytes:
    """Rasterises one page so a scanned PDF can go through the same OCR path as a photo.

    The zoom is what makes it OCR-able: rendering at native resolution gives roughly 72 dpi,
    which is well below what the recognisers need for small print.
    """
    import fitz

    with fitz.open(stream=data, filetype="pdf") as document:
        page = document.load_page(page_index)
        pixmap = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom))
        return pixmap.tobytes("png")


def pages_from_document(filename: str | None, data: bytes) -> tuple[list[str], bool]:
    """Page texts for any supported non-PDF format, plus whether pagination was applied.

    PDFs are handled separately by the caller because their pages may need OCR, which needs the
    request's OCR reader.
    """
    extension = extension_of(filename)
    if extension == ".txt":
        text = extract_plain_text(data)
    elif extension == ".docx":
        text = extract_docx_text(data)
    elif extension == ".epub":
        text = extract_epub_text(data)
    elif extension == ".fb2":
        text = extract_fb2_text(data)
    elif extension in (".htm", ".html"):
        text = strip_html(extract_plain_text(data))
    else:
        return [], False

    return split_into_pages(text), True
