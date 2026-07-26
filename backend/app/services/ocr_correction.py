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

RUSSIAN_ONLY_SIGNALS = set("ёщъьэцчЁЩЪЬЭЦЧ")

# Genitive/possessive endings whose ң is routinely flattened by OCR. Back
# vowel harmony only: these two endings are self-identifying (a scanned
# "нын"/"дын" is unambiguously the "ның"/"дың" suffix), so no vowel-class
# computation over the whole word is needed to apply them.
SUFFIX_REPAIRS = (
    ("нын", "ның"),
    ("дын", "дың"),
)
MIN_SUFFIX_REPAIR_LENGTH = 5

# Kazakh suffixes used as evidence that a document is Kazakh.
KAZAKH_SUFFIX_EVIDENCE = (
    "ның",
    "нің",
    "дың",
    "дің",
    "тың",
    "тің",
    "ларға",
    "лерге",
    "дарға",
    "дерге",
    "тарға",
    "терге",
    "ымыз",
    "іміз",
)

# Words that legitimately break vowel harmony: Russian and international
# loanwords in common school vocabulary. Lowercase, no suffixes.
LOANWORD_EXCEPTIONS = frozenset(
    {
        "автобус",
        "аптека",
        "банк",
        "велосипед",
        "газ",
        "газет",
        "газета",
        "гектар",
        "географ",
        "география",
        "геометрия",
        "гимн",
        "глобус",
        "грамм",
        "грамматика",
        "группа",
        "директор",
        "доктор",
        "журнал",
        "институт",
        "информатика",
        "калькулятор",
        "карта",
        "картина",
        "касса",
        "кино",
        "километр",
        "класс",
        "клуб",
        "код",
        "команда",
        "компас",
        "компьютер",
        "конверт",
        "конкурс",
        "концерт",
        "космос",
        "лагерь",
        "лампа",
        "литр",
        "магазин",
        "математика",
        "машина",
        "метр",
        "микрофон",
        "минут",
        "музыка",
        "музей",
        "оператор",
        "парк",
        "план",
        "планета",
        "поезд",
        "программа",
        "радио",
        "ракета",
        "секунд",
        "спорт",
        "стадион",
        "телефон",
        "тонна",
        "трактор",
        "троллейбус",
        "фабрика",
        "физика",
        "фильм",
        "футбол",
        "химия",
        "цифра",
        "экран",
    }
)

# Small closed set of Arabic/Persian loans where х is a misread һ.
H_LOANWORD_REPAIRS = {
    "гаухар": "гауһар",
    "жихаз": "жиһаз",
    "кахарман": "қаһарман",
    "шахар": "шаһар",
    "жахан": "жаһан",
}

KK_EVIDENCE_MIN_RATIO = 0.05


def correct_ocr_text(text: str, *, language_hint: str = "kk") -> str:
    """Return `text` with OCR letter confusions repaired.

    Never raises for string input. Unrecognized patterns pass through.
    """
    if not text or not text.strip():
        return text

    kazakh_document = _is_kazakh_document(text, language_hint)
    return WORD_PATTERN.sub(
        lambda match: _correct_word(match.group(0), kazakh=kazakh_document),
        text,
    )


def _correct_word(word: str, *, kazakh: bool = False) -> str:
    corrected = _fold_homoglyphs(word)
    if kazakh:
        corrected = _repair_kazakh(corrected)
    return corrected


def _is_kazakh_document(text: str, language_hint: str) -> bool:
    hint = (language_hint or "").strip().lower()
    if hint in ("ru", "en"):
        return False
    if hint == "kk":
        return True

    # No hint, or an unrecognized one: fall back to document evidence. Require
    # both a minimum count and a minimum ratio so a single stray Kazakh-looking
    # letter in a short document cannot open the gate.
    words = WORD_PATTERN.findall(text)
    if not words:
        return False

    evidence = sum(1 for word in words if _has_kazakh_evidence(word))
    return evidence >= 2 and evidence / len(words) >= KK_EVIDENCE_MIN_RATIO


def _has_kazakh_evidence(word: str) -> bool:
    if any(character in KAZAKH_SPECIFIC_LETTERS for character in word):
        return True
    lowered = word.lower()
    return any(lowered.endswith(suffix) for suffix in KAZAKH_SUFFIX_EVIDENCE)


def _repair_kazakh(word: str) -> str:
    lowered = word.lower()
    if lowered in LOANWORD_EXCEPTIONS:
        return word
    if any(character in RUSSIAN_ONLY_SIGNALS for character in word):
        return word

    repaired = _repair_h_loanword(word)
    if repaired != word:
        return repaired

    repaired = _fix_initial_eng(word)
    return _repair_suffix(repaired)


def _repair_h_loanword(word: str) -> str:
    replacement = H_LOANWORD_REPAIRS.get(word.lower())
    if replacement is None:
        return word
    if word[:1].isupper():
        return replacement[:1].upper() + replacement[1:]
    return replacement


def _fix_initial_eng(word: str) -> str:
    if word.startswith("ң"):
        return "н" + word[1:]
    if word.startswith("Ң"):
        return "Н" + word[1:]
    return word


def _repair_suffix(word: str) -> str:
    if len(word) < MIN_SUFFIX_REPAIR_LENGTH:
        return word

    lowered = word.lower()
    for wrong, right in SUFFIX_REPAIRS:
        if lowered.endswith(wrong):
            return word[: -len(wrong)] + right
    return word


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
