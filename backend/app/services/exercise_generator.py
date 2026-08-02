import random
import re
from uuid import uuid4

from .text_preparation import (
    ENGLISH_VOWELS,
    KAZAKH_VOWELS,
    RUSSIAN_VOWELS,
    detect_language,
    prepare_word_features,
    split_text_to_words,
)


KAZAKH_PRACTICE_WORD_BANK = [
    "балаларымызға",
    "Қазақстанның",
    "сұлтандарға",
    "қарапайым",
    "денесіне",
    "мектеп",
    "кітап",
    "оқушы",
    "дәптер",
    "достар",
    "терезе",
    "мұғалім",
    "отбасы",
    "жаңбыр",
    "аспан",
    "қалам",
    "орман",
    "әдемі",
    "жолдас",
    "сурет",
]
RUSSIAN_PRACTICE_WORD_BANK = [
    "учитель",
    "тетрадь",
    "ребята",
    "чтение",
    "слово",
    "страница",
    "помощь",
    "внимание",
    "книга",
    "школа",
    "дорога",
    "молоко",
    "весна",
    "друзья",
    "солнце",
    "улица",
    "письмо",
    "рисунок",
    "праздник",
    "зима",
]
PRACTICE_WORD_BANK = [
    *KAZAKH_PRACTICE_WORD_BANK,
    *RUSSIAN_PRACTICE_WORD_BANK,
]
ENGLISH_PRACTICE_WORD_BANK = [
    "reading",
    "teacher",
    "pencil",
    "window",
    "simple",
    "garden",
    "family",
    "helpful",
    "student",
    "library",
    "animal",
    "basic",
    "music",
    "story",
    "paper",
    "lesson",
    "morning",
    "friend",
    "letter",
    "picture",
]
MORPHOLOGY_EXERCISE_TYPES = ("root_suffix_identification", "word_segmentation")
EXERCISE_TYPES = (
    "auditory_match",
    "word_recognition",
    *MORPHOLOGY_EXERCISE_TYPES,
)
# Letters that children with dyslexia most often confuse visually
# (mirrored, rotated, or graphically similar shapes).
VISUAL_CONFUSIONS_CYRILLIC = {
    "б": "д", "д": "б",
    "п": "т", "т": "п",
    "и": "н", "н": "и",
    "ш": "щ", "щ": "ш",
    "л": "м", "м": "л",
    "о": "а", "а": "о",
    "з": "э", "э": "з",
    "ц": "щ",
    "ж": "х", "х": "ж",
}
VISUAL_CONFUSIONS_KAZAKH_EXTRA = {
    "г": "ғ", "ғ": "г",
    "к": "қ", "қ": "к",
    "у": "ү", "ү": "ұ", "ұ": "у",
    "ө": "о", "ә": "а", "і": "и", "ң": "н",
}
VISUAL_CONFUSIONS_KAZAKH = {**VISUAL_CONFUSIONS_CYRILLIC, **VISUAL_CONFUSIONS_KAZAKH_EXTRA}
VISUAL_CONFUSIONS_LATIN = {
    "b": "d", "d": "b",
    "p": "q", "q": "p",
    "m": "n", "n": "u", "u": "n",
    "i": "l", "l": "i",
    "a": "o", "o": "a",
    "v": "w", "w": "v",
    "f": "t", "t": "f",
    "e": "c", "c": "e",
}
NUMERIC_TOKEN_PATTERN = re.compile(r"\d+(?:[.,]\d+)?")
WORD_TOKEN_PATTERN = re.compile(r"[\w'-]+", re.UNICODE)
ROMAN_NUMERAL_PATTERN = re.compile(
    r"^(?=[mdclxvi]+$)m{0,4}(cm|cd|d?c{0,3})(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})$",
    re.IGNORECASE,
)
KAZAKH_SUFFIXES = sorted(
    {
        "ларыңыз",
        "леріңіз",
        "дарымыз",
        "деріміз",
        "тарымыз",
        "теріміз",
        "ымыз",
        "іміз",
        "мыз",
        "міз",
        "ыңыз",
        "іңіз",
        "сыз",
        "сіз",
        "лар",
        "лер",
        "дар",
        "дер",
        "тар",
        "тер",
        "ның",
        "нің",
        "дың",
        "дің",
        "тың",
        "тің",
        "мен",
        "пен",
        "бен",
        "ға",
        "ге",
        "қа",
        "ке",
        "да",
        "де",
        "та",
        "те",
        "на",
        "не",
        "ым",
        "ім",
        "ың",
        "ің",
        "сы",
        "сі",
        "ы",
        "і",
    },
    key=len,
    reverse=True,
)


def generate_exercises(
    *,
    source_words: list[str],
    exercise_type: str,
    count: int,
    difficulty_level: int,
    language_hint: str = "kk",
) -> list[dict]:
    rng = random.SystemRandom()
    requested_language = normalize_language_hint(language_hint)
    candidates = select_candidate_words(source_words, difficulty_level, language_hint)
    if not candidates:
        candidates = select_candidate_words(practice_bank_for_language(language_hint), difficulty_level, language_hint)

    if not candidates:
        candidates = [
            word
            for word in practice_bank_for_language(language_hint)
            if is_valid_source_token(word) and is_language_match(word, language_hint)
        ]

    rng.shuffle(candidates)
    exercises: list[dict] = []

    for index in range(count):
        selected_type = choose_exercise_type(exercise_type, index)
        pool = exercise_candidate_pool(
            candidates,
            selected_type,
            difficulty_level,
            requested_language,
        )

        if (
            exercise_type == "mixed"
            and selected_type in MORPHOLOGY_EXERCISE_TYPES
            and not pool
        ):
            selected_type = "word_recognition"
            pool = exercise_candidate_pool(
                candidates,
                selected_type,
                difficulty_level,
                requested_language,
            )

        if not pool:
            selected_type = "auditory_match"
            pool = exercise_candidate_pool(
                candidates,
                selected_type,
                difficulty_level,
                requested_language,
            )

        if not pool:
            pool = practice_bank_for_language(language_hint)[:]

        word = pool[index % len(pool)]
        exercises.append(build_exercise(word, selected_type, difficulty_level, rng, requested_language))

    return exercises


def select_candidate_words(source_words: list[str], difficulty_level: int, language_hint: str = "kk") -> list[str]:
    seen: set[str] = set()
    candidates: list[str] = []
    for raw_word in source_words:
        for word in split_source_words(raw_word):
            normalized = word.strip()
            lowered = normalized.lower()
            if lowered in seen or not is_valid_source_token(normalized):
                continue
            if not is_language_match(normalized, language_hint):
                continue
            features = prepare_word_features(normalized)
            if not is_word_allowed_for_difficulty(features.original, difficulty_level):
                continue
            seen.add(lowered)
            candidates.append(normalized)
    return candidates


def split_source_words(text: str) -> list[str]:
    # split_text_to_words covers Latin as well as Cyrillic now, so the separate Latin pass
    # this used to make would return the same words a second time.
    return [*split_text_to_words(text), *NUMERIC_TOKEN_PATTERN.findall(text)]


def practice_bank_for_language(language_hint: str) -> list[str]:
    normalized = normalize_language_hint(language_hint)
    if normalized == "en":
        return ENGLISH_PRACTICE_WORD_BANK
    if normalized == "ru":
        return RUSSIAN_PRACTICE_WORD_BANK
    return KAZAKH_PRACTICE_WORD_BANK


def normalize_language_hint(language_hint: str) -> str:
    if language_hint.startswith("en"):
        return "en"
    if language_hint.startswith("ru"):
        return "ru"
    return "kk"


def is_valid_source_token(token: str) -> bool:
    normalized = token.strip()
    if len(normalized) <= 1:
        return False
    if NUMERIC_TOKEN_PATTERN.fullmatch(normalized):
        return False
    if not WORD_TOKEN_PATTERN.fullmatch(normalized):
        return False
    letters_only = normalized.replace("'", "").replace("’", "").replace("-", "")
    if not letters_only.isalpha():
        return False
    if is_roman_numeral(normalized):
        return False
    return True


def is_roman_numeral(token: str) -> bool:
    return bool(ROMAN_NUMERAL_PATTERN.fullmatch(token))


def is_language_match(word: str, language_hint: str) -> bool:
    if language_hint.startswith("en"):
        return bool(re.fullmatch(r"[A-Za-z]+", word))
    if language_hint.startswith("ru"):
        return detect_language(word) == "ru"
    return detect_language(word) in {"kk", "ru"}


def is_word_allowed_for_difficulty(word: str, difficulty_level: int) -> bool:
    """Grades a word by how long it is.

    This used to count syllables, which is no longer computed anywhere. Letter count is the
    closest stand-in that needs no linguistic model, and the bands below are the old syllable
    bands scaled by roughly three letters per syllable.
    """
    letter_count = sum(1 for character in word if character.isalpha())
    if difficulty_level <= 1:
        return 1 <= letter_count <= 6
    if difficulty_level == 2:
        return 4 <= letter_count <= 9
    if difficulty_level == 3:
        return 7 <= letter_count <= 12
    if difficulty_level == 4:
        return 10 <= letter_count <= 15
    return letter_count >= 12


def choose_exercise_type(exercise_type: str, index: int) -> str:
    if exercise_type != "mixed" and exercise_type in EXERCISE_TYPES:
        return exercise_type
    return EXERCISE_TYPES[index % len(EXERCISE_TYPES)]


def exercise_candidate_pool(
    candidates: list[str],
    exercise_type: str,
    difficulty_level: int,
    language_hint: str,
) -> list[str]:
    pool = [
        word
        for word in candidates
        if is_valid_word_for_exercise_type(word, exercise_type, difficulty_level)
    ]
    if pool:
        return pool

    fallback_words = select_candidate_words(
        practice_bank_for_language(language_hint),
        difficulty_level,
        language_hint,
    )
    if not fallback_words:
        fallback_words = [
            word
            for word in practice_bank_for_language(language_hint)
            if is_valid_source_token(word)
        ]
    return [
        word
        for word in fallback_words
        if is_valid_word_for_exercise_type(word, exercise_type, difficulty_level)
    ]


def is_valid_word_for_exercise_type(word: str, exercise_type: str, difficulty_level: int) -> bool:
    if not is_valid_source_token(word):
        return False
    if exercise_type in MORPHOLOGY_EXERCISE_TYPES:
        return bool(split_root_suffixes(word)[1])
    if exercise_type == "word_recognition" and len(word) < 4:
        return False
    return is_word_allowed_for_difficulty(word, difficulty_level)

def build_exercise(
    word: str,
    exercise_type: str,
    difficulty_level: int,
    rng: random.Random,
    language_hint: str,
) -> dict:
    features = prepare_word_features(word)

    if exercise_type == "auditory_match":
        return build_auditory_match(features, difficulty_level, rng, language_hint)
    if exercise_type == "word_recognition":
        return build_word_recognition(features, difficulty_level, rng, language_hint)
    if exercise_type == "root_suffix_identification":
        return build_root_suffix_identification(features, difficulty_level, rng, language_hint)
    if exercise_type == "word_segmentation":
        return build_word_segmentation(features, difficulty_level, rng, language_hint)

    # Unknown type. Word recognition is the safe default: it asks only that the reader tell a
    # correctly spelled word from near-misses, which every word in the pool can support.
    return build_word_recognition(features, difficulty_level, rng, language_hint)


def split_root_suffixes(word: str) -> tuple[str, list[str]]:
    normalized = word.strip().lower()
    suffixes_reversed: list[str] = []
    stem = normalized

    while stem:
        suffix = next(
            (
                candidate
                for candidate in KAZAKH_SUFFIXES
                if stem.endswith(candidate) and len(stem) - len(candidate) >= 2
            ),
            None,
        )
        if suffix is None:
            break
        suffixes_reversed.append(suffix)
        stem = stem[: -len(suffix)]

    if not stem:
        stem = normalized

    return stem, list(reversed(suffixes_reversed))


def filter_morphology_candidates(words: list[str]) -> list[str]:
    return [word for word in words if split_root_suffixes(word)[1]]


def format_morphology_answer(root: str, suffixes: list[str]) -> str:
    return " + ".join([root, *suffixes])


def prompt_for(exercise_type: str, language_hint: str, target: str | None = None) -> str:
    language = normalize_language_hint(language_hint)
    prompts = {
        "root_suffix_identification": {
            "en": f"{target}: choose the root and suffixes",
            "ru": f"{target}: выбери корень и суффиксы",
            "kk": f"{target}: түбір мен жұрнақтарды таңда",
        },
        "word_segmentation": {
            "en": f"{target}: choose the base word",
            "ru": f"{target}: выбери основу",
            "kk": f"{target}: негізді таңда",
        },
        "auditory_match": {
            "en": "Listen and choose the word",
            "ru": "Послушай и выбери слово",
            "kk": "Тыңдап, дұрыс сөзді таңда",
        },
        "word_recognition": {
            "en": "Find the correctly written word",
            "ru": "Найди правильно написанное слово",
            "kk": "Дұрыс жазылған сөзді тап",
        },
    }
    return prompts.get(exercise_type, prompts["word_recognition"])[language]


def build_root_suffix_identification(features, difficulty_level: int, rng: random.Random, language_hint: str) -> dict:
    root, suffixes = split_root_suffixes(features.original)
    correct_answer = format_morphology_answer(root, suffixes)
    options = {correct_answer}

    if suffixes:
        options.add(format_morphology_answer(root, list(reversed(suffixes))))
        if len(suffixes) > 1:
            options.add(format_morphology_answer(root + suffixes[0], suffixes[1:]))
    options.add(format_morphology_answer(features.original, suffixes))

    option_list = list(options)
    rng.shuffle(option_list)
    return {
        "exercise_id": str(uuid4()),
        "type": "root_suffix_identification",
        "sub_exercise": "morphology",
        "prompt": prompt_for("root_suffix_identification", language_hint, features.original),
        "target_word": features.original,
        "options": option_list[: max(3, min(4, difficulty_level + 1))],
        "correct_answer": correct_answer,
        "difficulty_level": difficulty_level,
        "language_hint": normalize_language_hint(language_hint),
    }


def build_word_segmentation(features, difficulty_level: int, rng: random.Random, language_hint: str) -> dict:
    root, suffixes = split_root_suffixes(features.original)
    suffix_bundle = "".join(suffixes)
    correct_answer = root
    options = {correct_answer}

    for word in practice_bank_for_language(language_hint):
        candidate_root, _ = split_root_suffixes(word)
        if candidate_root != correct_answer:
            options.add(candidate_root)
        if len(options) >= 4:
            break

    option_list = list(options)
    rng.shuffle(option_list)
    return {
        "exercise_id": str(uuid4()),
        "type": "word_segmentation",
        "sub_exercise": "morphology",
        "prompt": prompt_for("word_segmentation", language_hint, suffix_bundle),
        "target_word": suffix_bundle,
        "options": option_list[: max(3, min(4, difficulty_level + 1))],
        "correct_answer": correct_answer,
        "difficulty_level": difficulty_level,
        "language_hint": normalize_language_hint(language_hint),
    }

def build_auditory_match(features, difficulty_level: int, rng: random.Random, language_hint: str) -> dict:
    word = features.original
    # Near-miss words force the reader to check every letter instead of
    # recognising the word by its first letters or overall shape.
    near_words = make_near_word_distractors(word, rng, limit=2)
    same_language_words = [
        bank_word
        for bank_word in practice_bank_for_language(language_hint)
        if bank_word.lower() != word.lower()
        and bank_word.lower() not in {near.lower() for near in near_words}
    ]
    rng.shuffle(same_language_words)
    options = [word, *near_words, *same_language_words]
    options = dedupe_preserving_case(options)[:4]
    rng.shuffle(options)
    return {
        "exercise_id": str(uuid4()),
        "type": "auditory_match",
        "prompt": prompt_for("auditory_match", language_hint),
        "target_word": word,
        "options": options,
        "correct_answer": word,
        "difficulty_level": difficulty_level,
        "language_hint": normalize_language_hint(language_hint),
    }


def build_word_recognition(features, difficulty_level: int, rng: random.Random, language_hint: str) -> dict:
    word = features.original
    distractors = make_near_word_distractors(word, rng, limit=3)
    options = dedupe_preserving_case([word, *distractors])[:4]
    rng.shuffle(options)
    return {
        "exercise_id": str(uuid4()),
        "type": "word_recognition",
        "prompt": prompt_for("word_recognition", language_hint),
        "target_word": word,
        "options": options,
        "correct_answer": word,
        "difficulty_level": difficulty_level,
        "language_hint": normalize_language_hint(language_hint),
    }


def vowel_set_for(word: str) -> set[str]:
    language = detect_language(word)
    if language == "en":
        return {vowel.lower() for vowel in ENGLISH_VOWELS}
    if language == "ru":
        return {vowel.lower() for vowel in RUSSIAN_VOWELS}
    return {vowel.lower() for vowel in KAZAKH_VOWELS}


def confusion_map_for(word: str) -> dict[str, str]:
    language = detect_language(word)
    if language == "en":
        return VISUAL_CONFUSIONS_LATIN
    if language == "kk":
        return VISUAL_CONFUSIONS_KAZAKH
    return VISUAL_CONFUSIONS_CYRILLIC


def match_case(template: str, replacement: str) -> str:
    return replacement.upper() if template.isupper() else replacement


def dedupe_preserving_case(values: list[str]) -> list[str]:
    seen: set[str] = set()
    unique: list[str] = []
    for value in values:
        key = value.lower()
        if key in seen or not value:
            continue
        seen.add(key)
        unique.append(value)
    return unique


def make_near_word_distractors(word: str, rng: random.Random, limit: int = 3) -> list[str]:
    """Build plausible misspellings that mirror typical dyslexic reading errors:
    swapped adjacent letters, visually confusable letters, and dropped letters."""
    confusions = confusion_map_for(word)
    candidates: list[str] = []

    # 1. Adjacent-letter transpositions (sequencing errors).
    transpositions = list(range(len(word) - 1))
    rng.shuffle(transpositions)
    for index in transpositions:
        if word[index].lower() == word[index + 1].lower():
            continue
        candidates.append(word[:index] + word[index + 1] + word[index] + word[index + 2:])

    # 2. Visually confusable letter substitutions (mirror/shape errors).
    positions = list(range(len(word)))
    rng.shuffle(positions)
    for index in positions:
        replacement = confusions.get(word[index].lower())
        if replacement is None:
            continue
        candidates.append(word[:index] + match_case(word[index], replacement) + word[index + 1:])

    # 3. A dropped letter (omission errors) for longer words.
    if len(word) >= 5:
        drop_index = rng.randrange(1, len(word) - 1)
        candidates.append(word[:drop_index] + word[drop_index + 1:])

    unique: list[str] = []
    seen = {word.lower()}
    for candidate in candidates:
        key = candidate.lower()
        if key in seen:
            continue
        seen.add(key)
        unique.append(candidate)
        if len(unique) >= limit:
            break
    return unique
