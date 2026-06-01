import random
import re
from uuid import uuid4

from .syllabification import (
    STANDARD_SYLLABLE_DELIMITER,
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
]
MORPHOLOGY_EXERCISE_TYPES = ("root_suffix_identification", "word_segmentation")
EXERCISE_TYPES = (
    "syllable_order",
    "missing_syllable",
    "word_to_syllables",
    "auditory_match",
    *MORPHOLOGY_EXERCISE_TYPES,
)
SYLLABLE_DISTRACTORS = ["ба", "ла", "ма", "ры", "ға", "де", "не", "қа", "тан", "дар", "по", "ра"]
LATIN_WORD_PATTERN = re.compile(r"[A-Za-z]+")
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
            selected_type = "syllable_order"
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
            if not is_word_allowed_for_difficulty(features.syllables, difficulty_level):
                continue
            seen.add(lowered)
            candidates.append(normalized)
    return candidates


def split_source_words(text: str) -> list[str]:
    cyrillic_words = split_text_to_words(text)
    latin_words = LATIN_WORD_PATTERN.findall(text)
    numeric_tokens = NUMERIC_TOKEN_PATTERN.findall(text)
    return [*cyrillic_words, *latin_words, *numeric_tokens]


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


def is_word_allowed_for_difficulty(syllables: list[str], difficulty_level: int) -> bool:
    syllable_count = len(syllables)
    if difficulty_level <= 1:
        return 1 <= syllable_count <= 2
    if difficulty_level == 2:
        return 2 <= syllable_count <= 3
    if difficulty_level == 3:
        return 3 <= syllable_count <= 4
    if difficulty_level == 4:
        return 4 <= syllable_count <= 5
    return syllable_count >= 4


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
    if exercise_type == "syllable_order":
        return is_valid_syllable_order_word(word)
    if exercise_type in MORPHOLOGY_EXERCISE_TYPES:
        return bool(split_root_suffixes(word)[1])
    features = prepare_word_features(word)
    return is_word_allowed_for_difficulty(features.syllables, difficulty_level)


def is_valid_syllable_order_word(word: str) -> bool:
    syllables = meaningful_syllables(prepare_word_features(word).syllables)
    return len(syllables) >= 2 and len({syllable.lower() for syllable in syllables}) > 1


def meaningful_syllables(syllables: list[str]) -> list[str]:
    return [
        syllable.strip()
        for syllable in syllables
        if syllable.strip() and any(character.isalpha() for character in syllable)
    ]


def build_exercise(
    word: str,
    exercise_type: str,
    difficulty_level: int,
    rng: random.Random,
    language_hint: str,
) -> dict:
    features = prepare_word_features(word)
    syllables = features.syllables
    correct_answer = features.adapted

    if exercise_type == "missing_syllable":
        return build_missing_syllable(features, difficulty_level, rng, language_hint)
    if exercise_type == "word_to_syllables":
        return build_word_to_syllables(features, difficulty_level, rng, language_hint)
    if exercise_type == "auditory_match":
        return build_auditory_match(features, difficulty_level, rng, language_hint)
    if exercise_type == "root_suffix_identification":
        return build_root_suffix_identification(features, difficulty_level, rng, language_hint)
    if exercise_type == "word_segmentation":
        return build_word_segmentation(features, difficulty_level, rng, language_hint)

    options = syllables[:]
    rng.shuffle(options)
    return {
        "exercise_id": str(uuid4()),
        "type": "syllable_order",
        "prompt": prompt_for("syllable_order", language_hint),
        "target_word": word,
        "syllables": syllables,
        "options": options,
        "correct_answer": correct_answer,
        "difficulty_level": difficulty_level,
        "language_hint": normalize_language_hint(language_hint),
    }


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
        stem = prepare_word_features(normalized).syllables[0]

    return stem, list(reversed(suffixes_reversed))


def filter_morphology_candidates(words: list[str]) -> list[str]:
    return [word for word in words if split_root_suffixes(word)[1]]


def format_morphology_answer(root: str, suffixes: list[str]) -> str:
    return " + ".join([root, *suffixes])


def prompt_for(exercise_type: str, language_hint: str, target: str | None = None) -> str:
    language = normalize_language_hint(language_hint)
    prompts = {
        "syllable_order": {
            "en": "Put the syllables in order",
            "ru": "Расставь слоги по порядку",
            "kk": "Буындарды дұрыс ретпен орналастыр",
        },
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
        "word_to_syllables": {
            "en": f"{target}: choose the correct syllable split",
            "ru": f"{target}: выбери правильное деление на слоги",
            "kk": f"{target}: дұрыс буындауды таңда",
        },
        "auditory_match": {
            "en": "Listen and choose the word",
            "ru": "Послушай и выбери слово",
            "kk": "Тыңдап, дұрыс сөзді таңда",
        },
    }
    return prompts.get(exercise_type, prompts["syllable_order"])[language]


def build_root_suffix_identification(features, difficulty_level: int, rng: random.Random, language_hint: str) -> dict:
    root, suffixes = split_root_suffixes(features.original)
    correct_answer = format_morphology_answer(root, suffixes)
    options = {correct_answer}

    if suffixes:
        options.add(format_morphology_answer(root, list(reversed(suffixes))))
        if len(suffixes) > 1:
            options.add(format_morphology_answer(root + suffixes[0], suffixes[1:]))
    options.add(format_morphology_answer(features.syllables[0], suffixes))

    option_list = list(options)
    rng.shuffle(option_list)
    return {
        "exercise_id": str(uuid4()),
        "type": "root_suffix_identification",
        "sub_exercise": "morphology",
        "prompt": prompt_for("root_suffix_identification", language_hint, features.original),
        "target_word": features.original,
        "syllables": [root, *suffixes],
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
        "syllables": suffixes,
        "options": option_list[: max(3, min(4, difficulty_level + 1))],
        "correct_answer": correct_answer,
        "difficulty_level": difficulty_level,
        "language_hint": normalize_language_hint(language_hint),
    }


def build_missing_syllable(features, difficulty_level: int, rng: random.Random, language_hint: str) -> dict:
    syllables = features.syllables
    missing_index = len(syllables) // 2 if len(syllables) > 1 else 0
    prompt_syllables = syllables[:]
    prompt_syllables[missing_index] = "__"
    correct_answer = syllables[missing_index]
    options = build_unique_options(correct_answer, difficulty_level, rng)
    return {
        "exercise_id": str(uuid4()),
        "type": "missing_syllable",
        "prompt": STANDARD_SYLLABLE_DELIMITER.join(prompt_syllables),
        "target_word": features.original,
        "syllables": syllables,
        "options": options,
        "correct_answer": correct_answer,
        "difficulty_level": difficulty_level,
        "language_hint": normalize_language_hint(language_hint),
    }


def build_word_to_syllables(features, difficulty_level: int, rng: random.Random, language_hint: str) -> dict:
    correct_answer = features.adapted
    options = {correct_answer}
    joined = "".join(features.syllables)
    if len(features.syllables) > 2:
        options.add(STANDARD_SYLLABLE_DELIMITER.join([features.syllables[0], "".join(features.syllables[1:])]))
        options.add(STANDARD_SYLLABLE_DELIMITER.join(["".join(features.syllables[:2]), *features.syllables[2:]]))
    options.add(joined)
    for wrong_option in make_wrong_syllabification_options(joined, correct_answer):
        options.add(wrong_option)
        if len(options) >= 4:
            break
    option_list = list(options)
    rng.shuffle(option_list)
    return {
        "exercise_id": str(uuid4()),
        "type": "word_to_syllables",
        "prompt": prompt_for("word_to_syllables", language_hint, features.original),
        "target_word": features.original,
        "syllables": features.syllables,
        "options": option_list[:4],
        "correct_answer": correct_answer,
        "difficulty_level": difficulty_level,
        "language_hint": normalize_language_hint(language_hint),
    }


def build_auditory_match(features, difficulty_level: int, rng: random.Random, language_hint: str) -> dict:
    same_language_words = [
        word
        for word in practice_bank_for_language(language_hint)
        if word.lower() != features.original.lower() and detect_language(word) == features.language_hint
    ]
    if not same_language_words:
        same_language_words = [
            word
            for word in practice_bank_for_language(language_hint)
            if word.lower() != features.original.lower()
        ]
    rng.shuffle(same_language_words)
    options = [features.original, *same_language_words[:3]]
    rng.shuffle(options)
    return {
        "exercise_id": str(uuid4()),
        "type": "auditory_match",
        "prompt": prompt_for("auditory_match", language_hint),
        "target_word": features.original,
        "syllables": features.syllables,
        "options": options,
        "correct_answer": features.original,
        "difficulty_level": difficulty_level,
        "language_hint": normalize_language_hint(language_hint),
    }


def build_unique_options(correct_answer: str, difficulty_level: int, rng: random.Random) -> list[str]:
    options = {correct_answer}
    distractors = SYLLABLE_DISTRACTORS[:]
    rng.shuffle(distractors)
    for distractor in distractors:
        if distractor != correct_answer:
            options.add(distractor)
        if len(options) >= min(4, max(3, difficulty_level)):
            break
    option_list = list(options)
    rng.shuffle(option_list)
    return option_list


def make_wrong_syllabification_options(word: str, correct_answer: str) -> list[str]:
    if len(word) <= 1:
        return [word]

    candidates: list[str] = []
    for split_at in range(1, len(word)):
        candidates.append(word[:split_at] + STANDARD_SYLLABLE_DELIMITER + word[split_at:])

    for first_split in range(1, max(1, len(word) - 1)):
        for second_split in range(first_split + 1, len(word)):
            candidates.append(
                STANDARD_SYLLABLE_DELIMITER.join(
                    [word[:first_split], word[first_split:second_split], word[second_split:]]
                )
            )

    unique_candidates: list[str] = []
    seen: set[str] = {correct_answer}
    for candidate in candidates:
        if candidate in seen:
            continue
        seen.add(candidate)
        unique_candidates.append(candidate)
    return unique_candidates
