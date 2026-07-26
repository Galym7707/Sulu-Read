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
