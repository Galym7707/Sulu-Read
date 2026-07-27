from pathlib import Path

import pytest

from backend.app.services import hunspell_lexicon


@pytest.fixture(scope="module")
def lexicon():
    loaded = hunspell_lexicon.get_kazakh_lexicon()
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
    assert (
        hunspell_lexicon.load_lexicon(tmp_path / "absent.dic", tmp_path / "absent.aff")
        is None
    )


def test_parse_failures_are_not_swallowed(tmp_path, monkeypatch):
    # A genuine bug in the parser must be distinguishable from a missing
    # data file, otherwise it silently degrades to "no lexicon" with nothing
    # logged. Only OSError (missing/unreadable files) should degrade quietly.
    def _raise(path):
        raise ValueError("boom")

    monkeypatch.setattr(hunspell_lexicon, "_parse_dictionary", _raise)
    dic_path = tmp_path / "present.dic"
    aff_path = tmp_path / "present.aff"
    dic_path.write_text("1\nбала\n", encoding="utf-8")
    aff_path.write_text("", encoding="utf-8")
    with pytest.raises(ValueError):
        hunspell_lexicon.load_lexicon(dic_path, aff_path)


def test_get_kazakh_lexicon_is_cached():
    assert hunspell_lexicon.get_kazakh_lexicon() is hunspell_lexicon.get_kazakh_lexicon()


def test_russian_lexicon_loads_and_recognizes_real_words():
    russian_lexicon = hunspell_lexicon.get_russian_lexicon()
    assert russian_lexicon is not None, "bundled Russian dictionary failed to load"
    for word in ("доска", "кошка", "карман"):
        assert russian_lexicon.contains(word), word


def test_get_russian_lexicon_is_cached():
    assert hunspell_lexicon.get_russian_lexicon() is hunspell_lexicon.get_russian_lexicon()


def test_kazakh_and_russian_lexicons_are_independent():
    # "қала" is Kazakh-only, "доска" is Russian-only; each cache must hold
    # its own dictionary rather than sharing one loaded value.
    kazakh_lexicon = hunspell_lexicon.get_kazakh_lexicon()
    russian_lexicon = hunspell_lexicon.get_russian_lexicon()
    assert kazakh_lexicon.contains("қала")
    assert not russian_lexicon.contains("қала")
    assert russian_lexicon.contains("доска")
    assert not kazakh_lexicon.contains("доска")
