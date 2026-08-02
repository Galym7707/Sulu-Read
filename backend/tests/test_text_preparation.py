from backend.app.services.text_preparation import (
    extract_word_features,
    prepare_text_for_adaptation,
    remove_existing_syllable_markup,
    split_text_to_words,
)


def test_syllable_markup_in_the_source_is_stripped():
    # The app no longer divides words into syllables, but textbooks do, and OCR reads their
    # hyphens back. If these survived, the reader would still be shown syllable division.
    assert remove_existing_syllable_markup("ба-ла-ла-ры-мыз-ға") == "балаларымызға"
    assert remove_existing_syllable_markup("Қа-зақ-стан-ның") == "Қазақстанның"


def test_secondary_dividers_are_stripped_too():
    assert remove_existing_syllable_markup("ба·ла·ла·ры") == "балалары"


def test_real_hyphenated_words_survive():
    # A genuine hyphen joins two whole words; only short repeated fragments are syllables.
    assert remove_existing_syllable_markup("қара-торы") == "қара-торы"


def test_ocr_cleanup_removes_double_delimiters_and_all_caps():
    prepared = prepare_text_for_adaptation("Бі-·рін-·ші. ҚА-·ЗАҚ-·С-·ТАН-НЫҢ.", source="image")

    assert "·" not in prepared
    assert "ҚА" not in prepared
    assert "Бірінші" in prepared
    assert "Қазақстанның" in prepared


def test_ocr_dollar_section_cleanup():
    prepared = prepare_text_for_adaptation("$1. ӘЛЕУМЕТТІК МӘСЕЛЕ.", source="image")

    assert prepared.startswith("§1.")
    assert "$" not in prepared


def test_latin_words_are_counted_like_cyrillic_ones():
    # A Latin-only page used to extract zero words, so the reader was told the text it was
    # looking at contained "words: 0".
    assert split_text_to_words("Dyslexia is a learning difficulty") == [
        "Dyslexia",
        "is",
        "a",
        "learning",
        "difficulty",
    ]


def test_mixed_script_text_keeps_document_order():
    assert split_text_to_words("Оқушы reads кітап") == ["Оқушы", "reads", "кітап"]


def test_word_features_cover_latin_words():
    features = extract_word_features("Reading оқу")
    assert [feature.original for feature in features] == ["Reading", "оқу"]
    assert features[0].language_hint == "en"
    assert features[1].language_hint == "kk"


def test_digits_alone_are_not_words():
    assert split_text_to_words("2026 жыл") == ["жыл"]
