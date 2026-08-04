"""Fetches public-domain texts from Wikisource for the reading catalogue.

Only the extraction lives here. What gets fetched is declared in catalog_sources.py, and the
pagination is the app's own, so a catalogue book is divided exactly like a book a reader
uploads themselves.

Wikisource marks up verse with tables and divs for layout, so the HTML cannot simply have its
tags dropped — the line structure of a poem is the poem. This keeps line and stanza breaks and
discards only navigation, footnote markers and editorial apparatus.
"""

from __future__ import annotations

import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from html.parser import HTMLParser

USER_AGENT = "SuluRead/1.0 (dyslexia reading app; educational use)"
REQUEST_TIMEOUT_SECONDS = 30
REQUEST_INTERVAL_SECONDS = 1.5
RETRY_BACKOFF_SECONDS = 8.0
MAX_ATTEMPTS = 4

# Wrappers Wikisource uses for things that are not the text: header boxes, licence footers,
# navigation between pages, and the small print of the edition.
SKIPPED_CLASS_MARKERS = (
    "ws-noexport",
    "navigation",
    "navbox",
    "header_notes",
    "headertemplate",
    "licensetpl",
    "catlinks",
    "printfooter",
    "mw-editsection",
    "reflist",
    "references",
    # Wikisource numbers every fifth line of verse in the margin. It is markup, not the poem,
    # and a child reading aloud would read the number out.
    "linenum",
)

# Everything from one of these headings on is editorial apparatus about the text rather than the
# text: variant readings, manuscript notes, sources. The reader wants the poem, not the edition.
EDITORIAL_HEADINGS = {
    "примечания", "примечание", "комментарии", "комментарий", "варианты", "источник",
    "источники", "литература", "ссылки", "сноски", "см. также", "библиография",
    "ескертпе", "ескертпелер", "дереккөз", "дереккөздер", "әдебиет",
    "notes", "references", "sources", "see also",
}
HEADING_TAGS = {"h1", "h2", "h3", "h4"}

BLOCK_TAGS = {"p", "div", "tr", "li", "h1", "h2", "h3", "h4", "blockquote", "dd", "dt"}
LINE_BREAK_TAGS = {"br"}
DROPPED_TAGS = {"script", "style", "sup", "sub", "table_header"}


@dataclass(frozen=True)
class FetchedText:
    title: str
    text: str
    source_url: str


class WikisourceTextExtractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._parts: list[str] = []
        self._skip_depth = 0
        self._skip_stack: list[bool] = []
        self._in_heading = False
        self._heading_text: list[str] = []
        self._stopped = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if self._stopped:
            return
        if tag in HEADING_TAGS:
            self._in_heading = True
            self._heading_text = []
        attributes = {key: (value or "") for key, value in attrs}
        marker = f"{attributes.get('class', '')} {attributes.get('id', '')}".lower()
        should_skip = tag in DROPPED_TAGS or any(m in marker for m in SKIPPED_CLASS_MARKERS)

        if tag not in LINE_BREAK_TAGS:
            self._skip_stack.append(should_skip)
        if should_skip:
            self._skip_depth += 1

        if self._skip_depth:
            return
        if tag in LINE_BREAK_TAGS:
            self._parts.append("\n")
        elif tag in BLOCK_TAGS:
            self._parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if self._stopped:
            return
        if tag in HEADING_TAGS and self._in_heading:
            self._in_heading = False
            heading = "".join(self._heading_text).strip().lower().rstrip(":")
            if heading in EDITORIAL_HEADINGS:
                self._stopped = True
                # The heading's own words were emitted before it could be recognised — it is
                # only identifiable once its closing tag arrives — so take them back off.
                self._drop_trailing(self._heading_text)
                return
        if tag in LINE_BREAK_TAGS:
            return
        was_skipping = self._skip_stack.pop() if self._skip_stack else False
        if was_skipping and self._skip_depth:
            self._skip_depth -= 1
        if not self._skip_depth and tag in BLOCK_TAGS:
            self._parts.append("\n")

    def handle_data(self, data: str) -> None:
        if self._stopped:
            return
        if self._in_heading:
            self._heading_text.append(data)
        if not self._skip_depth:
            self._parts.append(data)

    def _drop_trailing(self, emitted: list[str]) -> None:
        """Removes text already appended to the output, newest first."""
        for chunk in reversed(emitted):
            if self._parts and self._parts[-1] == chunk:
                self._parts.pop()

    def text(self) -> str:
        raw = "".join(self._parts)
        raw = raw.replace("\xa0", " ")
        # Editorial leftovers: bracketed page numbers and footnote anchors.
        raw = re.sub(r"\[\d+\]", "", raw)
        raw = re.sub(r"[ \t]+", " ", raw)
        raw = re.sub(r" *\n *", "\n", raw)

        # A line that is only a number is apparatus, not text: a verse line number that escaped
        # the class filter, or the year of composition printed under a poem. Left in, a child
        # reading aloud reads the number out as part of the poem.
        kept = [line for line in raw.split("\n") if not re.fullmatch(r"\s*\d{1,4}\s*", line)]
        return re.sub(r"\n{3,}", "\n\n", "\n".join(kept)).strip()


_last_request_at = 0.0


def _request_json(url: str) -> dict:
    """One request, spaced out and retried on a rate limit.

    Wikimedia answers 429 readily to a burst from one client, and a build that fetches thirty
    pages is exactly such a burst. Spacing requests and backing off is the price of using their
    API politely; the build is not in anyone's critical path.
    """
    global _last_request_at

    for attempt in range(MAX_ATTEMPTS):
        wait = REQUEST_INTERVAL_SECONDS - (time.monotonic() - _last_request_at)
        if wait > 0:
            time.sleep(wait)

        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
                _last_request_at = time.monotonic()
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            _last_request_at = time.monotonic()
            if error.code != 429 or attempt == MAX_ATTEMPTS - 1:
                raise
            time.sleep(RETRY_BACKOFF_SECONDS * (attempt + 1))

    raise RuntimeError("unreachable")


def is_disambiguation(host: str, title: str) -> bool:
    """Whether a title points at a list of works rather than at a work.

    Worth a separate request: a disambiguation page extracts perfectly happily into a couple of
    lines of prose, so nothing downstream would notice it is not the story. Chekhov's "Ванька"
    is one of these, and it produced a 24-word "book".
    """
    url = (
        f"https://{host}/w/api.php?action=query&prop=pageprops"
        f"&titles={urllib.parse.quote(title)}&redirects=1&format=json&formatversion=2"
    )
    pages = _request_json(url).get("query", {}).get("pages", [])
    if not pages:
        return False
    return "disambiguation" in (pages[0].get("pageprops") or {})


def fetch_text(host: str, title: str) -> FetchedText | None:
    if is_disambiguation(host, title):
        return None

    url = (
        f"https://{host}/w/api.php?action=parse&page={urllib.parse.quote(title)}"
        "&prop=text&formatversion=2&format=json&redirects=1"
    )
    payload = _request_json(url)
    if "error" in payload:
        return None

    extractor = WikisourceTextExtractor()
    extractor.feed(payload["parse"]["text"])
    text = extractor.text()
    if not text:
        return None

    return FetchedText(
        title=payload["parse"]["title"],
        text=text,
        source_url=f"https://{host}/wiki/{urllib.parse.quote(title.replace(' ', '_'))}",
    )


def search(host: str, query: str, limit: int = 8) -> list[str]:
    url = (
        f"https://{host}/w/api.php?action=query&list=search"
        f"&srsearch={urllib.parse.quote(query)}&srlimit={limit}&format=json&formatversion=2"
    )
    payload = _request_json(url)
    return [hit["title"] for hit in payload.get("query", {}).get("search", [])]
