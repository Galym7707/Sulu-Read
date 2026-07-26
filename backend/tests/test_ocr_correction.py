from backend.app.services.ocr_correction import correct_ocr_text


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


def test_back_vowel_word_is_not_kazakhized():
    # Vowel harmony was deleted: "а" being a back vowel never proves a
    # scanned "к" is wrong, so "кала" is returned as scanned, not "қала".
    assert correct_ocr_text("кала", language_hint="kk") == "кала"


def test_back_vowel_ghayn_word_is_not_kazakhized():
    assert correct_ocr_text("тагы", language_hint="kk") == "тагы"


def test_front_vowel_word_keeps_plain_k():
    assert correct_ocr_text("мектеп", language_hint="kk") == "мектеп"


def test_front_vowel_word_is_not_kazakhized():
    assert correct_ocr_text("олке", language_hint="kk") == "олке"
    # "олкелер" has two front markers (е, е); the deleted harmony rule used
    # to turn its о into ө. It no longer does.
    assert correct_ocr_text("олкелер", language_hint="kk") == "олкелер"


def test_mixed_evidence_word_is_left_alone():
    # "кітап" genuinely mixes classes (і front, а back). Must not be touched.
    assert correct_ocr_text("кітап", language_hint="kk") == "кітап"


def test_loanword_exception_is_not_kazakhized():
    assert correct_ocr_text("класс", language_hint="kk") == "класс"
    assert correct_ocr_text("кино", language_hint="kk") == "кино"
    assert correct_ocr_text("космос", language_hint="kk") == "космос"


def test_word_with_russian_only_letter_is_skipped():
    # ь never appears in a native Kazakh stem, so this is a loanword.
    assert correct_ocr_text("компьютер", language_hint="kk") == "компьютер"


def test_word_initial_eng_is_corrected():
    assert correct_ocr_text("ңан", language_hint="kk") == "нан"


def test_back_genitive_suffix_restores_eng():
    assert correct_ocr_text("баланын", language_hint="kk") == "баланың"


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
    # Kazakh-specific letters here) can no longer override "ru"/"en".
    source = "Бүгін кала кітапханасы ашық."
    assert correct_ocr_text(source, language_hint="ru") == source


def test_kazakh_document_detected_without_hint():
    # With no hint, evidence still opens the gate (>= 2 evidence words here:
    # "кітапханасы" carries і, "ашық" carries қ) and the surviving genitive
    # repair applies.
    source = "Бүгін кітапханасы ашық, баланын кітабы үстелде."
    assert "баланың" in correct_ocr_text(source, language_hint="")


def test_single_evidence_word_does_not_open_gate():
    # A lone stray Kazakh-looking letter must not be enough: at least 2
    # evidence words are required, not just the 5% ratio. Only "қала" here
    # carries Kazakh-specific evidence; every other word is plain Russian.
    source = "Мы сегодня читаем интересную книгу дома. Там был қала."
    assert correct_ocr_text(source, language_hint="") == source


def test_russian_document_with_no_kazakh_evidence_stays_closed():
    source = "Ученики читают книгу в классе."
    assert correct_ocr_text(source, language_hint="en") == source


def test_kazakh_repair_is_idempotent():
    source = "кала тагы баланын мектептин"
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
    )
    for word in words:
        assert correct_ocr_text(word, language_hint="kk") == word


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
