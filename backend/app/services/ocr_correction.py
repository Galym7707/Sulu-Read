"""Post-OCR correction for Kazakh and Russian textbook text.

Pure functions only: no I/O, no network, no environment reads. The caller
decides whether to apply correction; this module only transforms strings.
"""

import re

CYRILLIC_LETTERS = set(
    "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
    "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
    "әғқңөұүһіӘҒҚҢӨҰҮҺІ"
)
LATIN_LETTERS = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
KAZAKH_SPECIFIC_LETTERS = set("әғқңөұүһіӘҒҚҢӨҰҮҺІ")

# Runs of letters and digits. Everything else (spaces, punctuation, newlines)
# is preserved untouched by re.sub.
WORD_PATTERN = re.compile(r"[^\W_]+", re.UNICODE)

LATIN_TO_CYRILLIC = {
    "a": "а",
    "c": "с",
    "e": "е",
    "k": "к",
    "m": "м",
    "o": "о",
    "p": "р",
    "t": "т",
    "x": "х",
    "y": "у",
    "A": "А",
    "B": "В",
    "C": "С",
    "E": "Е",
    "H": "Н",
    "K": "К",
    "M": "М",
    "O": "О",
    "P": "Р",
    "T": "Т",
    "X": "Х",
    "Y": "У",
}
CYRILLIC_TO_LATIN = {value: key for key, value in LATIN_TO_CYRILLIC.items()}

DIGIT_TO_CYRILLIC = {
    "0": "о",
    "3": "з",
    "6": "б",
}


def correct_ocr_text(text: str, *, language_hint: str = "kk") -> str:
    """Return `text` with OCR letter confusions repaired.

    Never raises for string input. Unrecognized patterns pass through.
    """
    if not text or not text.strip():
        return text

    return WORD_PATTERN.sub(lambda match: _correct_word(match.group(0)), text)


def _correct_word(word: str) -> str:
    corrected = _fold_homoglyphs(word)
    corrected = _fold_digits(corrected)
    return corrected


def _fold_homoglyphs(word: str) -> str:
    cyrillic_count = sum(1 for character in word if character in CYRILLIC_LETTERS)
    latin_count = sum(1 for character in word if character in LATIN_LETTERS)
    if not cyrillic_count or not latin_count:
        return word

    if cyrillic_count > latin_count:
        return "".join(LATIN_TO_CYRILLIC.get(character, character) for character in word)
    if latin_count > cyrillic_count:
        return "".join(CYRILLIC_TO_LATIN.get(character, character) for character in word)
    return word


def _fold_digits(word: str) -> str:
    if not any(character in CYRILLIC_LETTERS for character in word):
        return word

    characters = list(word)
    for index in range(1, len(characters) - 1):
        character = characters[index]
        if character not in DIGIT_TO_CYRILLIC:
            continue
        if characters[index - 1].isalpha() and characters[index + 1].isalpha():
            characters[index] = DIGIT_TO_CYRILLIC[character]
    return "".join(characters)
