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


def test_digits_inside_a_cyrillic_word_are_folded():
    # language_hint="ru" keeps Task 2's Kazakh repair out of this assertion.
    assert correct_ocr_text("б0лім", language_hint="ru") == "болім"
    assert correct_ocr_text("ка3ак", language_hint="ru") == "казак"


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


def test_back_vowel_word_restores_qaf():
    # "кала" in a Kazakh document: а/а are back markers, so к must be қ.
    assert correct_ocr_text("кала", language_hint="kk") == "қала"


def test_back_vowel_word_restores_ghayn():
    assert correct_ocr_text("тагы", language_hint="kk") == "тағы"


def test_front_vowel_word_keeps_plain_k():
    assert correct_ocr_text("мектеп", language_hint="kk") == "мектеп"


def test_front_vowel_word_restores_ae_and_oe():
    # "олке" holds one back marker (о) and one front marker (е): a tie,
    # so nothing is changed rather than guessed.
    assert correct_ocr_text("олке", language_hint="kk") == "олке"
    # "олкелер" is front-dominant (е, е, е) so о becomes ө.
    assert correct_ocr_text("олкелер", language_hint="kk") == "өлкелер"


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


def test_front_genitive_suffix_restores_eng_and_i():
    assert correct_ocr_text("мектептин", language_hint="kk") == "мектептің"


def test_h_loanword_is_restored():
    assert correct_ocr_text("гаухар", language_hint="kk") == "гауһар"


def test_russian_document_is_not_kazakhized():
    source = "Мы идём в школу каждый день. Дети играют во дворе."
    assert correct_ocr_text(source, language_hint="ru") == source


def test_kazakh_document_detected_without_hint():
    # Kazakh-specific letters in the document open the gate even when the
    # client sent the wrong hint.
    source = "Бүгін кала кітапханасы ашық."
    assert "қала" in correct_ocr_text(source, language_hint="ru")


def test_russian_document_with_no_kazakh_evidence_stays_closed():
    source = "Ученики читают книгу в классе."
    assert correct_ocr_text(source, language_hint="en") == source


def test_kazakh_repair_is_idempotent():
    source = "кала тагы баланын мектептин"
    once = correct_ocr_text(source, language_hint="kk")
    assert correct_ocr_text(once, language_hint="kk") == once


def test_tier_one_still_applies_inside_kazakh_documents():
    assert correct_ocr_text("мekтеп қала", language_hint="kk") == "мектеп қала"
