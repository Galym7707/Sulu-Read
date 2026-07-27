from backend.app.services.ocr_correction import correct_ocr_text
from backend.app.services import ocr_correction


def test_latin_homoglyphs_folded_into_cyrillic_majority_word():
    # "мekтеп" carries Latin e/k inside a Cyrillic-majority word.
    assert correct_ocr_text("мekтеп") == "мектеп"


def test_cyrillic_homoglyphs_folded_into_latin_majority_word():
    # s h o l are Latin (4), с and о are Cyrillic (2): Latin majority wins.
    contaminated = "sсhoоl"
    assert correct_ocr_text(contaminated) == "school"


def test_word_without_script_majority_is_left_alone():
    # Two Cyrillic (с, р), two Latin (o, a): no majority, so nothing is guessed.
    tie_word = "сoрa"
    assert correct_ocr_text(tie_word) == tie_word


def test_pure_cyrillic_word_is_unchanged():
    assert correct_ocr_text("мектеп") == "мектеп"


def test_pure_latin_word_is_unchanged():
    assert correct_ocr_text("school") == "school"


def test_digits_inside_a_cyrillic_word_are_not_touched():
    # Digit folding was removed: it corrupted chemical formulas and page
    # numbers (e.g. "С3Н8" -> "СзН8") for a speculative, unmeasured benefit.
    assert correct_ocr_text("б0лім", language_hint="ru") == "б0лім"
    assert correct_ocr_text("ка3ак", language_hint="ru") == "ка3ак"


def test_standalone_numbers_are_not_touched():
    assert correct_ocr_text("1991 жыл") == "1991 жыл"
    assert correct_ocr_text("Сабақ 5") == "Сабақ 5"


def test_punctuation_and_whitespace_are_preserved():
    source = "Бүгін — жақсы күн.\nЕртең де жақсы!\n"
    assert correct_ocr_text(source) == source


def test_empty_and_blank_input_pass_through():
    assert correct_ocr_text("") == ""
    assert correct_ocr_text("   \n  ") == "   \n  "


def test_correction_is_idempotent():
    source = "мekтeп б0лім school"
    once = correct_ocr_text(source)
    assert correct_ocr_text(once) == once


def test_back_vowel_word_is_not_kazakhized_by_harmony():
    # Vowel harmony was deleted: "а" being a back vowel never proves a scanned
    # "к" is wrong or right. "касса" has two back vowels around a "к" -- a
    # harmony rule could be tempted to leave it alone "because" the vowels
    # agree. It is in fact left alone, but only because "касса" is already a
    # real word (a loanword present in both the Kazakh and Russian
    # dictionaries), never because of vowel phonology. This is not a
    # duplicate of the lexicon-gated tests below: those assert a *change*
    # happens; this asserts a *plausible-looking* word is correctly left as
    # a no-op, which a reintroduced harmony rule would not guarantee.
    assert correct_ocr_text("касса", language_hint="kk") == "касса"


def test_front_vowel_word_is_not_kazakhized_by_harmony():
    # Restored via the lexicon (both "өлке" and "өлкелер" are real dictionary
    # words), not because "е" is a front vowel -- the harmony rule that used
    # to reason that way was deleted.
    assert correct_ocr_text("олке", language_hint="kk") == "өлке"
    assert correct_ocr_text("олкелер", language_hint="kk") == "өлкелер"


def test_word_initial_eng_is_corrected():
    assert correct_ocr_text("ңан", language_hint="kk") == "нан"


def test_front_genitive_suffix_is_no_longer_repaired():
    # "тин"/"нин"/"дин" repairs were deleted: they guessed two letters at
    # once and collided with real Russian words like "господин".
    assert correct_ocr_text("мектептин", language_hint="kk") == "мектептин"


def test_h_loanword_is_restored():
    assert correct_ocr_text("гаухар", language_hint="kk") == "гауһар"


def test_russian_document_is_not_kazakhized():
    source = "Мы идём в школу каждый день. Дети играют во дворе."
    assert correct_ocr_text(source, language_hint="ru") == source


def test_explicit_ru_hint_closes_gate_even_with_kazakh_evidence():
    # The gate is closed by an explicit hint now: document evidence (the
    # Kazakh-specific letters here) can no longer override "ru"/"en". The
    # probe word "ңан" is the only surviving rule that could change this
    # text (word-initial ң -> н); it must survive untouched with the gate
    # closed. Contrast with test_kazakh_document_detected_without_hint,
    # where the same kind of probe IS repaired once the gate opens.
    source = "Бүгін кала кітапханасы ашық, ңан үстелде."
    assert correct_ocr_text(source, language_hint="ru") == source


def test_kazakh_document_detected_without_hint():
    # With no hint, evidence still opens the gate (>= 2 evidence words here:
    # "Бүгін" carries ү, "кітапханасы" carries і, "ашық" carries қ) and the
    # surviving word-initial-ң repair applies to the probe word "ңан".
    source = "Бүгін кітапханасы ашық, ңан үстелде."
    corrected = correct_ocr_text(source, language_hint="")
    assert "нан" in corrected
    assert "ңан" not in corrected


def test_single_evidence_word_does_not_open_gate():
    # A lone stray Kazakh-looking letter must not be enough: at least 2
    # evidence words are required, not just the 5% ratio. Only "қала" here
    # carries Kazakh-specific evidence; every other word is plain Russian.
    # "гаухар" is used as the probe (not "ңан") because ң is itself
    # Kazakh-specific evidence and would wrongly supply the second word.
    # With the gate closed, the probe must not become "гауһар".
    source = "Мы сегодня читаем интересную книгу дома. Там был қала и гаухар."
    assert correct_ocr_text(source, language_hint="") == source


def test_russian_document_with_no_kazakh_evidence_stays_closed():
    # The explicit "en" hint closes the gate outright; the probe word "ңан"
    # would be repaired to "нан" if the Kazakh path ran.
    source = "Ученики читают книгу в классе. Там лежит ңан."
    assert correct_ocr_text(source, language_hint="en") == source


def test_kazakh_repair_is_idempotent():
    source = "кала тагы ңан мектептин"
    once = correct_ocr_text(source, language_hint="kk")
    assert correct_ocr_text(once, language_hint="kk") == once


def test_tier_one_still_applies_inside_kazakh_documents():
    assert correct_ocr_text("мekтеп қала", language_hint="kk") == "мектеп қала"


# --- Regression tests for confirmed OCR-correction damage (final review) ---


def test_clean_kazakh_words_are_unchanged():
    words = (
        "кітапхана",
        "Республикасы",
        "болатын",
        "оқитын",
        "картасы",
        "Москва",
        "музыкалық",
        # -ын/-ін possessive-accusative (3rd-person possessive "-ы"/"-і" +
        # accusative "-н") on stems ending in н/д: a real, productive
        # pattern that a scanned "нын"/"дын" cannot be told apart from the
        # flattened genitive "-ның"/"-дың" by string pattern alone. The
        # SUFFIX_REPAIRS rule that rewrote these into a different word with
        # a different meaning was deleted; these guard against bringing it
        # back.
        "телефонын",
        "орнын",
        "жанын",
        "заводын",
        "стадионын",
    )
    for word in words:
        assert correct_ocr_text(word, language_hint="kk") == word


def test_possessive_accusative_sentence_is_unchanged():
    source = "Ол телефонын үстелге қойды."
    assert correct_ocr_text(source, language_hint="kk") == source


def test_russian_sentence_with_stray_letter_is_unchanged():
    source = (
        "Книга лежит на столе. Город большой. Когда мы гуляли, "
        "погода была хорошая. Стрөка"
    )
    assert correct_ocr_text(source, language_hint="ru") == source


def test_chemical_formula_is_unchanged():
    assert correct_ocr_text("С3Н8 газы", language_hint="kk") == "С3Н8 газы"


def test_russian_words_ending_in_din_nin_are_unchanged_in_kazakh_document():
    assert correct_ocr_text("господин", language_hint="kk") == "господин"
    assert correct_ocr_text("гражданин", language_hint="kk") == "гражданин"


# --- Lexicon-gated letter repair ---


def test_flattened_letters_are_restored_from_the_lexicon():
    # "кала" is deliberately not used here: it is itself a real Russian word
    # ("кал", genitive), so the Russian guard (see the "Russian guard" tests
    # below) leaves it unchanged now. "кагаз" and "тагы" are not Russian
    # words, so they still demonstrate the lexicon restoring dropped letters.
    assert correct_ocr_text("кагаз", language_hint="kk") == "қағаз"
    assert correct_ocr_text("тагы", language_hint="kk") == "тағы"


def test_restoration_keeps_original_capitalization():
    assert correct_ocr_text("Тагы", language_hint="kk") == "Тағы"


def test_correct_words_are_never_rewritten():
    # Every one of these was damaged by the rule-based attempt this design replaces.
    unchanged = (
        "кітапхана",
        "болатын",
        "оқитын",
        "телефонын",
        "орнын",
        "кітабын",
        "заводын",
        "картасы",
        "музыкалық",
        "Республикасы",
        "Москва",
    )
    for word in unchanged:
        assert correct_ocr_text(word, language_hint="kk") == word, word


def test_short_words_are_left_alone():
    # Without the length guard this becomes "қм".
    assert correct_ocr_text("км", language_hint="kk") == "км"


def test_word_final_n_is_never_restored():
    # -ын/-ін possessive-accusative forms are under-represented in the dictionary,
    # so a final н→ң finds a spurious unique match.
    assert correct_ocr_text("стадионын", language_hint="kk") == "стадионын"
    assert correct_ocr_text("жанын", language_hint="kk") == "жанын"


def test_unknown_word_with_no_lexicon_candidate_is_left_alone():
    assert correct_ocr_text("абракадабра", language_hint="kk") == "абракадабра"


def test_russian_document_is_not_repaired():
    source = "Книга лежит на столе. Город большой."
    assert correct_ocr_text(source, language_hint="ru") == source


def test_lexicon_repair_is_idempotent():
    once = correct_ocr_text("кагаз тагы", language_hint="kk")
    assert correct_ocr_text(once, language_hint="kk") == once


def test_repair_is_skipped_when_the_kazakh_lexicon_is_unavailable(monkeypatch):
    monkeypatch.setattr(ocr_correction, "get_kazakh_lexicon", lambda: None)
    assert correct_ocr_text("кагаз", language_hint="kk") == "кагаз"


# --- Word-initial ң guard (Fix 4c) ---


def test_word_initial_eng_is_not_reintroduced_by_the_lexicon_repair():
    # _fix_initial_eng turns a leading ң into н (word-initial ң is invalid
    # Kazakh). Without excluding index 0 from candidate generation, the
    # lexicon repair immediately undoes that: "неле" is not a Kazakh word,
    # but "ңеле" is, so it looked like a unique restoration.
    assert correct_ocr_text("ңеле", language_hint="kk") == "неле"
    assert correct_ocr_text("неле", language_hint="kk") == "неле"


# --- Russian guard (Fix 3) ---
#
# /v1/adapt-image defaults language_hint="kk" (main.py), so Russian pages run
# through the Kazakh repair path. Each of these is a real Russian word whose
# Kazakh-restored form is also a real Kazakh word, so the lexicon's "exactly
# one candidate" rule alone would rewrite it. The Russian dictionary guard
# stops that: a word already valid in Russian is never touched.


def test_words_valid_in_russian_are_never_kazakhized():
    words = (
        "доска",
        "кошка",
        "шапка",
        "бумага",
        "карман",
        "костер",
        "каша",
        "кожа",
        "ласка",
        "катер",
        "козел",
    )
    for word in words:
        assert correct_ocr_text(word, language_hint="kk") == word, word


def test_russian_paragraph_is_unchanged_under_default_kazakh_hint():
    source = (
        "Учитель написал задание на доске. Бумага и карандаш лежат на парте. "
        "Кошка спит на окне, а рядом висит шапка. В кармане у него лежал ключ."
    )
    assert correct_ocr_text(source, language_hint="kk") == source


def test_russian_guard_is_skipped_not_bypassed_when_unavailable(monkeypatch):
    # Missing Russian data must degrade to "no Russian guard", not "no
    # repairs at all" -- the Kazakh guard above is what actually matters for
    # safety, and it stays in force regardless.
    monkeypatch.setattr(ocr_correction, "get_russian_lexicon", lambda: None)
    assert correct_ocr_text("тагы", language_hint="kk") == "тағы"
