from backend.app.services.syllabification import (
    adapt_text,
    prepare_text_for_adaptation,
    split_kazakh_russian_syllables,
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
