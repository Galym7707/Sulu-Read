from backend.app.services.syllabification import (
    adapt_text,
    extract_word_features,
    prepare_text_for_adaptation,
    split_kazakh_russian_syllables,
    split_text_to_words,
)


def test_required_kazakh_examples():
    examples = {
        "балаларымызға": "ба-ла-ла-ры-мыз-ға",
        "Қазақстанның": "Қа-зақ-стан-ның",
        "сұлтандарға": "сұл-тан-дар-ға",
        "қарапайым": "қа-ра-па-йым",
        "денесіне": "де-не-сі-не",
    }

    for word, expected in examples.items():
        assert "-".join(split_kazakh_russian_syllables(word)) == expected


def test_twenty_kazakh_russian_words_have_clean_hyphen_syllables():
    words = [
        "балаларымызға",
        "Қазақстанның",
        "сұлтандарға",
        "қарапайым",
        "денесіне",
        "мектеп",
        "кітап",
        "оқушы",
        "дәптер",
        "достар",
        "Астана",
        "Алматы",
        "учитель",
        "тетрадь",
        "ребята",
        "чтение",
        "слово",
        "страница",
        "помощь",
        "внимание",
    ]

    for word in words:
        adapted = "-".join(split_kazakh_russian_syllables(word))
        assert adapted
        assert "·" not in adapted
        assert "--" not in adapted


def test_ocr_cleanup_removes_double_delimiters_and_all_caps():
    prepared = prepare_text_for_adaptation("Бі-·рін-·ші. ҚА-·ЗАҚ-·С-·ТАН-НЫҢ.", source="image")
    adapted = adapt_text(prepared)

    assert "·" not in prepared
    assert "·" not in adapted
    assert "ҚА" not in adapted
    assert "Бі-рін-ші" in adapted
    assert "Қа-зақ-стан-ның" in adapted


def test_ocr_dollar_section_cleanup():
    prepared = prepare_text_for_adaptation("$1. ӘЛЕУМЕТТІК МӘСЕЛЕ.", source="image")
    adapted = adapt_text(prepared)

    assert prepared.startswith("§1.")
    assert "$" not in prepared
    assert "Ә-ле-у-мет-тік" in adapted


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
    assert features[0].syllables == ["Read", "ing"]
    assert features[1].language_hint == "kk"


def test_digits_alone_are_not_words():
    assert split_text_to_words("2026 жыл") == ["жыл"]
