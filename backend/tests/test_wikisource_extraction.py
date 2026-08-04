"""Offline tests for the Wikisource HTML extractor used to build the catalogue.

No network: the markup below is the shape Wikisource actually produces, and these are the
cases that put wrong text in front of a child the first time round.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts.wikisource_fetch import WikisourceTextExtractor  # noqa: E402


def extract(markup: str) -> str:
    extractor = WikisourceTextExtractor()
    extractor.feed(markup)
    return extractor.text()


def test_verse_lines_are_kept_apart():
    # Poems are laid out with <br/>, and the line structure of a poem is the poem.
    text = extract("<p>Белеет парус одинокой<br/>В тумане моря голубом!..</p>")
    assert text.splitlines() == ["Белеет парус одинокой", "В тумане моря голубом!.."]


def test_margin_line_numbers_are_dropped():
    # Wikisource numbers every fifth line of verse in the margin. A child reading aloud would
    # otherwise read the number out as part of the poem.
    markup = (
        '<p><span class="linenumright" id="5">5</span>Играют волны — ветер свищет,<br/>'
        "И мачта гнется и скрыпит...</p>"
    )
    text = extract(markup)
    assert "5" not in text
    assert text.startswith("Играют волны")


def test_editorial_sections_are_cut_off():
    markup = (
        "<p>Как будто в бурях есть покой!</p>"
        '<div class="mw-heading mw-heading2"><h2 id="Примечания">Примечания</h2></div>'
        "<p>Здесь воспроизведено по автографу — ИРЛИ, оп. 1</p>"
    )
    text = extract(markup)
    assert "Как будто в бурях есть покой!" in text
    # Both the heading itself and everything under it must go.
    assert "Примечания" not in text
    assert "автографу" not in text


def test_kazakh_editorial_heading_is_recognised():
    markup = "<p>Таулардан өзен ағар</p><h2>Ескертпе</h2><p>Дереккөз туралы</p>"
    text = extract(markup)
    assert "Таулардан өзен ағар" in text
    assert "Ескертпе" not in text
    assert "Дереккөз туралы" not in text


def test_year_of_composition_is_dropped():
    # Printed under a poem as a bare number; it is part of the edition, not the poem.
    text = extract("<p>А он, мятежный, просит бури,</p><div><i>1832</i></div>")
    assert "1832" not in text
    assert "мятежный" in text


def test_navigation_and_licence_boxes_are_ignored():
    markup = (
        '<div class="ws-noexport navigation">Скачать</div>'
        "<p>Настоящий текст</p>"
        '<div class="licensetpl">Это произведение перешло в общественное достояние</div>'
    )
    text = extract(markup)
    assert text == "Настоящий текст"


def test_footnote_markers_are_removed():
    text = extract("<p>Слово[1] другое[23]</p>")
    assert text == "Слово другое"
