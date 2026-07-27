import re
from dataclasses import dataclass


RUSSIAN_VOWELS = set("аеёиоуыэюяАЕЁИОУЫЭЮЯ")
KAZAKH_VOWELS = set("аәеёиоөұүыіуАӘЕЁИОӨҰҮЫІУ")
ENGLISH_VOWELS = set("aeiouyAEIOUY")
ALL_CYRILLIC_VOWELS = RUSSIAN_VOWELS | KAZAKH_VOWELS
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

TOKEN_PATTERN = re.compile(
    rf"[{LETTER_CLASS}]+(?:['’][{LETTER_CLASS}]+)?"
    r"|[0-9]+"
    r"|[^\w\s]+"
    r"|\s+",
    re.UNICODE,
)
WORD_PATTERN = re.compile(rf"[{LETTER_CLASS}]+", re.UNICODE)
HYPHENATED_WORD_PATTERN = re.compile(rf"[{LETTER_CLASS}]+(?:-[{LETTER_CLASS}]+)+", re.UNICODE)

SPECIAL_SYLLABLES = {
    "балаларымызға": ["ба", "ла", "ла", "ры", "мыз", "ға"],
    "қазақстан": ["қа", "зақ", "стан"],
    "қазақстанның": ["қа", "зақ", "стан", "ның"],
    "сұлтандарға": ["сұл", "тан", "дар", "ға"],
    "қарапайым": ["қа", "ра", "па", "йым"],
    "денесіне": ["де", "не", "сі", "не"],
}
ENGLISH_SPECIAL_SYLLABLES = {
    "reading": ["read", "ing"],
    "teacher": ["teach", "er"],
    "pencil": ["pen", "cil"],
    "window": ["win", "dow"],
    "simple": ["sim", "ple"],
    "garden": ["gar", "den"],
    "family": ["fam", "i", "ly"],
    "helpful": ["help", "ful"],
    "student": ["stu", "dent"],
    "library": ["li", "bra", "ry"],
}


@dataclass(frozen=True)
class WordFeatures:
    original: str
    adapted: str
    syllables: list[str]
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


def split_text_to_words(text: str) -> list[str]:
    return [
        match.group(0)
        for match in WORD_PATTERN.finditer(text)
        if any(character in CYRILLIC_LETTERS for character in match.group(0))
    ]


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


def split_kazakh_russian_syllables(word: str) -> list[str]:
    clean_word = remove_existing_syllable_markup(word)
    special = SPECIAL_SYLLABLES.get(clean_word.lower())
    if special is not None:
        return apply_word_casing(clean_word, special)

    vowel_positions = [
        index
        for index, character in enumerate(clean_word)
        if character in ALL_CYRILLIC_VOWELS
    ]
    if len(vowel_positions) <= 1:
        return [clean_word]

    split_indices: list[int] = []
    for left_vowel, right_vowel in zip(vowel_positions, vowel_positions[1:]):
        consonant_cluster_length = right_vowel - left_vowel - 1
        split_index = choose_split_index(
            left_vowel=left_vowel,
            right_vowel=right_vowel,
            consonant_cluster_length=consonant_cluster_length,
        )
        if 0 < split_index < len(clean_word):
            split_indices.append(split_index)

    syllables: list[str] = []
    start = 0
    for split_index in sorted(set(split_indices)):
        syllable = clean_word[start:split_index]
        if syllable:
            syllables.append(syllable)
        start = split_index

    tail = clean_word[start:]
    if tail:
        syllables.append(tail)

    return syllables or [clean_word]


def split_english_syllables(word: str) -> list[str]:
    clean_word = remove_existing_syllable_markup(word)
    special = ENGLISH_SPECIAL_SYLLABLES.get(clean_word.lower())
    if special is not None:
        return apply_word_casing(clean_word, special)

    vowel_groups: list[tuple[int, int]] = []
    index = 0
    while index < len(clean_word):
        if clean_word[index] not in ENGLISH_VOWELS:
            index += 1
            continue
        start = index
        while index + 1 < len(clean_word) and clean_word[index + 1] in ENGLISH_VOWELS:
            index += 1
        vowel_groups.append((start, index))
        index += 1

    if len(vowel_groups) <= 1:
        return [clean_word]

    split_indices: list[int] = []
    for (_, left_vowel_end), (right_vowel_start, _) in zip(vowel_groups, vowel_groups[1:]):
        consonant_cluster_length = right_vowel_start - left_vowel_end - 1
        if consonant_cluster_length <= 0:
            split_index = left_vowel_end + 1
        elif consonant_cluster_length == 1:
            split_index = left_vowel_end + 1
        else:
            split_index = left_vowel_end + 2
        if 0 < split_index < len(clean_word):
            split_indices.append(split_index)

    syllables: list[str] = []
    start = 0
    for split_index in sorted(set(split_indices)):
        syllable = clean_word[start:split_index]
        if syllable:
            syllables.append(syllable)
        start = split_index

    tail = clean_word[start:]
    if tail:
        syllables.append(tail)

    return syllables or [clean_word]


def choose_split_index(left_vowel: int, right_vowel: int, consonant_cluster_length: int) -> int:
    if consonant_cluster_length <= 1:
        return left_vowel + 1
    return left_vowel + 2


def apply_word_casing(word: str, lowercase_syllables: list[str]) -> list[str]:
    if not word:
        return lowercase_syllables
    if word[0].isupper():
        first = lowercase_syllables[0]
        return [first[:1].upper() + first[1:], *lowercase_syllables[1:]]
    return lowercase_syllables


def prepare_word_features(word: str) -> WordFeatures:
    language = detect_language(word)
    syllables = split_english_syllables(word) if language == "en" else split_kazakh_russian_syllables(word)
    return WordFeatures(
        original=word,
        adapted=STANDARD_SYLLABLE_DELIMITER.join(syllables),
        syllables=syllables,
        language_hint=language,
        vowel_harmony=detect_kazakh_vowel_harmony(word) if language == "kk" else None,
    )


def adapt_text(text: str) -> str:
    adapted_tokens: list[str] = []
    for token in TOKEN_PATTERN.findall(text):
        if any(character in CYRILLIC_LETTERS for character in token) or (token.isascii() and token.isalpha()):
            adapted_tokens.append(prepare_word_features(token).adapted)
        else:
            adapted_tokens.append(token)
    return "".join(adapted_tokens)


def extract_word_features(text: str) -> list[WordFeatures]:
    return [prepare_word_features(word) for word in split_text_to_words(text)]
