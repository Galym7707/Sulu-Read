from pathlib import Path

import pytest

from backend.app.services import kazakh_lexicon


@pytest.fixture(scope="module")
def lexicon():
    loaded = kazakh_lexicon.get_lexicon()
    assert loaded is not None, "bundled Kazakh dictionary failed to load"
    return loaded


def test_base_words_are_known(lexicon):
    for word in ("бала", "мектеп", "қала", "кітапхана", "жақсы"):
        assert lexicon.contains(word), word


def test_inflected_forms_are_known(lexicon):
    # These are produced by the affix file's suffix rules, not listed as stems.
    for word in ("мектептің", "картасы", "телефонын", "болатын", "Республикасы"):
        assert lexicon.contains(word), word


def test_lookup_is_case_insensitive(lexicon):
    assert lexicon.contains("ҚАЛА")
    assert lexicon.contains("Қала")


def test_non_words_are_unknown(lexicon):
    # Corrupted, over-corrected, and foreign forms must all be rejected, otherwise
    # the repair step has nothing to decide with.
    for word in ("кала", "қітапхана", "Мосқва", "абракадабра", "тагы"):
        assert not lexicon.contains(word), word


def test_missing_data_files_degrade_to_no_lexicon(tmp_path):
    # A packaging mistake must not break image adaptation.
    assert kazakh_lexicon.load_lexicon(tmp_path / "absent.dic", tmp_path / "absent.aff") is None


def test_get_lexicon_is_cached():
    assert kazakh_lexicon.get_lexicon() is kazakh_lexicon.get_lexicon()
