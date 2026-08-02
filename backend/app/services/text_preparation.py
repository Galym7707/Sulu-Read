import re
from dataclasses import dataclass


RUSSIAN_VOWELS = set("аеёиоуыэюяАЕЁИОУЫЭЮЯ")
KAZAKH_VOWELS = set("аәеёиоөұүыіуАӘЕЁИОӨҰҮЫІУ")
ENGLISH_VOWELS = set("aeiouyAEIOUY")
KAZAKH_FRONT_VOWELS = set("әеөүіӘЕӨҮІ")
KAZAKH_BACK_VOWELS = set("аоұыАОҰЫ")
KAZAKH_SPECIFIC_LETTERS = set("әғқңөұүһіӘҒҚҢӨҰҮҺІ")
CYRILLIC_LETTERS = set(
    "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
    "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
    "әғқңөұүһіӘҒҚҢӨҰҮҺІ"
)
LETTER_CLASS = "A-Za-zА-Яа-яЁёӘәҒғҚқҢңӨөҰұҮүҺһІі"
STANDARD_SYLLABLE_DELIMITER = "-"
SECONDARY_SYLLABLE_DIVIDERS = "·•∙⋅●"
KNOWN_PROPER_NOUNS = {
    "қазақстан": "Қазақстан",
    "астана": "Астана",
    "алматы": "Алматы",
    "казахстан": "Казахстан",
}
OCR_TEXT_REPLACEMENTS = (
    ("$", "§"),
    ("＄", "§"),
    ("﹩", "§"),
)

WORD_PATTERN = re.compile(rf"[{LETTER_CLASS}]+", re.UNICODE)
HYPHENATED_WORD_PATTERN = re.compile(rf"[{LETTER_CLASS}]+(?:-[{LETTER_CLASS}]+)+", re.UNICODE)

@dataclass(frozen=True)
class WordFeatures:
    original: str
    language_hint: str
    vowel_harmony: str | None


def normalize_text(text: str) -> str:
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"[ \t\f\v]+", " ", text)
    text = re.sub(r" *\n *", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def clean_ocr_text(raw_ocr_text: str) -> str:
    cleaned_text = raw_ocr_text

    # "$" is a misread "§" only in Cyrillic textbook text; in English text a
    # dollar sign is meaningful, so only substitute when Cyrillic dominates.
    cyrillic_count = sum(1 for character in cleaned_text if character in CYRILLIC_LETTERS)
    latin_count = sum(1 for character in cleaned_text if "a" <= character.lower() <= "z")
    if cyrillic_count >= latin_count:
        for old_value, new_value in OCR_TEXT_REPLACEMENTS:
            cleaned_text = cleaned_text.replace(old_value, new_value)
        cleaned_text = re.sub(r"§\s+(\d)", r"§\1", cleaned_text)

    cleaned_text = re.sub(r"(?<=\d)\s+([.,:;!?])", r"\1", cleaned_text)
    return normalize_text(cleaned_text)


def mostly_uppercase(text: str) -> bool:
    letters = [character for character in text if character.isalpha()]
    if not letters:
        return False
    uppercase_count = sum(1 for character in letters if character.isupper())
    return uppercase_count / len(letters) > 0.60


def prepare_text_for_adaptation(text: str, *, source: str = "text") -> str:
    prepared_text = normalize_text(text)
    if source == "image":
        prepared_text = clean_ocr_text(prepared_text)
        # Re-casing destroys names and acronyms, so only normalize when OCR
        # returned shouting caps (typical for headings-only misreads).
        if mostly_uppercase(prepared_text):
            prepared_text = sentence_case_text(prepared_text.lower())

    prepared_text = remove_existing_syllable_markup(prepared_text)
    if source == "image":
        prepared_text = restore_known_proper_nouns(prepared_text)
    return normalize_text(prepared_text)


def sentence_case_text(text: str) -> str:
    cased_characters: list[str] = []
    capitalize_next_letter = True

    for character in text:
        if character.isalpha():
            if capitalize_next_letter:
                cased_characters.append(character.upper())
                capitalize_next_letter = False
            else:
                cased_characters.append(character)
            continue

        cased_characters.append(character)
        if character in ".!?…\n":
            capitalize_next_letter = True

    return restore_known_proper_nouns("".join(cased_characters))


def restore_known_proper_nouns(text: str) -> str:
    def restore_word(match: re.Match[str]) -> str:
        word = match.group(0)
        lowered_word = word.lower()
        for root, natural_root in KNOWN_PROPER_NOUNS.items():
            if lowered_word.startswith(root):
                return natural_root + word[len(root):]
        return word

    return WORD_PATTERN.sub(restore_word, text)


def remove_existing_syllable_markup(text: str) -> str:
    """Strips syllable division that was already in the source.

    The app no longer divides words into syllables, but the pages it is pointed at still do:
    primary-school textbooks print "ба-ла-ла-ры" and OCR reads the hyphens back faithfully.
    Leaving them in would put syllable division in front of the reader through the back door,
    so this runs on every text regardless of where it came from.
    """
    cleaned_text = text
    for divider in SECONDARY_SYLLABLE_DIVIDERS:
        cleaned_text = cleaned_text.replace(divider, STANDARD_SYLLABLE_DELIMITER)

    cleaned_text = re.sub(
        rf"(?<=[{LETTER_CLASS}])\s*-\s*(?=[{LETTER_CLASS}])",
        STANDARD_SYLLABLE_DELIMITER,
        cleaned_text,
    )
    cleaned_text = re.sub(r"-{2,}", STANDARD_SYLLABLE_DELIMITER, cleaned_text)

    def remove_syllable_hyphens(match: re.Match[str]) -> str:
        hyphenated_word = match.group(0)
        parts = hyphenated_word.split(STANDARD_SYLLABLE_DELIMITER)
        if len(parts) >= 3 and all(1 <= len(part) <= 5 for part in parts):
            return "".join(parts)
        return hyphenated_word

    return HYPHENATED_WORD_PATTERN.sub(remove_syllable_hyphens, cleaned_text)


def is_word_token(token: str) -> bool:
    # Counts Latin as well as Cyrillic. An earlier version dropped Latin words entirely, so an
    # English page reported a word count of zero and callers that wanted Latin had to glob for
    # it separately.
    return any(character in CYRILLIC_LETTERS for character in token) or (
        token.isascii() and token.isalpha()
    )


def split_text_to_words(text: str) -> list[str]:
    return [match.group(0) for match in WORD_PATTERN.finditer(text) if is_word_token(match.group(0))]


def detect_language(word: str) -> str:
    if any(character in KAZAKH_SPECIFIC_LETTERS for character in word):
        return "kk"
    if any(character in CYRILLIC_LETTERS for character in word):
        return "ru"
    if word.isascii() and any(character.isalpha() for character in word):
        return "en"
    return "unknown"


def detect_kazakh_vowel_harmony(word: str) -> str:
    has_front = any(character in KAZAKH_FRONT_VOWELS for character in word)
    has_back = any(character in KAZAKH_BACK_VOWELS for character in word)

    if has_front and has_back:
        return "mixed"
    if has_front:
        return "front"
    if has_back:
        return "back"
    return "neutral"


def prepare_word_features(word: str) -> WordFeatures:
    language = detect_language(word)
    return WordFeatures(
        original=word,
        language_hint=language,
        vowel_harmony=detect_kazakh_vowel_harmony(word) if language == "kk" else None,
    )


def extract_word_features(text: str) -> list[WordFeatures]:
    return [prepare_word_features(word) for word in split_text_to_words(text)]
