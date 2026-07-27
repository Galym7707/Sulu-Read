"""Post-OCR correction for Kazakh and Russian textbook text.

No network, no environment reads. The Kazakh-letter repair reads the bundled
hunspell dictionaries from disk on first use (see ``hunspell_lexicon``),
lazily and cached, so this module is no longer I/O-free. The caller decides
whether to apply correction; this module only transforms strings.
"""

import itertools
import re

from .hunspell_lexicon import get_kazakh_lexicon, get_russian_lexicon

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

# Small closed set of Arabic/Persian loans where х is a misread һ.
H_LOANWORD_REPAIRS = {
    "гаухар": "гауһар",
    "жихаз": "жиһаз",
    "кахарман": "қаһарман",
    "шахар": "шаһар",
    "жахан": "жаһан",
}

KK_EVIDENCE_MIN_RATIO = 0.05

# OCR flattens Kazakh letters onto their Russian lookalikes; these are the
# restorations to try. The dictionary decides which, if any, is correct.
KAZAKH_RESTORATIONS = {
    "к": "қ",
    "г": "ғ",
    "а": "ә",
    "о": "ө",
    "у": "ұү",
    "и": "і",
    "н": "ң",
    "х": "һ",
}
MIN_LEXICON_REPAIR_LENGTH = 4
MAX_AMBIGUOUS_POSITIONS = 6
MAX_CANDIDATES = 256


def correct_ocr_text(text: str, *, language_hint: str = "kk") -> str:
    """Return `text` with OCR letter confusions repaired.

    Unrecognized patterns pass through unchanged. This does not catch
    exceptions raised while reading or parsing the bundled dictionaries: a
    genuine parser bug propagates to the caller rather than vanishing
    silently. ``main.apply_ocr_correction`` is the layer that catches and
    logs around this call so a request never fails because of it.
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
    repaired = _repair_h_loanword(word)
    if repaired != word:
        return repaired

    return _repair_with_lexicon(_fix_initial_eng(word))


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


def _repair_with_lexicon(word: str) -> str:
    lexicon = get_kazakh_lexicon()
    if lexicon is None:
        return word

    lowered = word.lower()
    if len(lowered) < MIN_LEXICON_REPAIR_LENGTH or not lowered.isalpha():
        return word
    if lexicon.contains(lowered):
        return word

    # A word already valid in Russian must never be treated as damaged
    # Kazakh: the default language_hint is "kk", so Russian pages run through
    # this path too, and a real Russian word can also be a real Kazakh word
    # under exactly one restoration (e.g. "доска" -> "досқа"). Missing
    # Russian data degrades to "no guard", not "no repairs" -- the Kazakh
    # guard above already covers the safety-critical case.
    russian_lexicon = get_russian_lexicon()
    if russian_lexicon is not None and russian_lexicon.contains(lowered):
        return word

    positions = _ambiguous_positions(lowered)
    if not positions or len(positions) > MAX_AMBIGUOUS_POSITIONS:
        return word

    choices = [
        [None] + [(index, letter) for letter in KAZAKH_RESTORATIONS[lowered[index]]]
        for index in positions
    ]

    match: str | None = None
    evaluated = 0
    for combination in itertools.product(*choices):
        evaluated += 1
        if evaluated > MAX_CANDIDATES:
            return word

        characters = list(lowered)
        for replacement in combination:
            if replacement is not None:
                characters[replacement[0]] = replacement[1]

        candidate = "".join(characters)
        if candidate == lowered or not lexicon.contains(candidate):
            continue
        if match is not None and candidate != match:
            # Two readings are both real words; the dictionary cannot settle it.
            return word
        match = candidate

    if match is None:
        return word
    return _apply_original_case(word, match)


def _ambiguous_positions(word: str) -> list[int]:
    last_index = len(word) - 1
    return [
        index
        for index, character in enumerate(word)
        if character in KAZAKH_RESTORATIONS
        # A word-final н is usually the -ын/-ін possessive-accusative, whose forms
        # are under-represented in the dictionary, so restoring ң there finds a
        # spurious unique match and rewrites correct text.
        and not (character == "н" and index == last_index)
        # A word-initial н is never a real word-initial ң (Kazakh forbids
        # word-initial ң; that is exactly why _fix_initial_eng runs just
        # before this and turns a scanned leading ң into н). Without this
        # exclusion the lexicon repair undoes that fix immediately after:
        # "неле" -> "ңеле".
        and not (character == "н" and index == 0)
    ]


def _apply_original_case(original: str, repaired: str) -> str:
    if original.isupper():
        return repaired.upper()
    if original[:1].isupper():
        return repaired[:1].upper() + repaired[1:]
    return repaired


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
