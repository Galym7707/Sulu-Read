import random
import re
from uuid import uuid4

from .syllabification import (
    ENGLISH_VOWELS,
    KAZAKH_VOWELS,
    RUSSIAN_VOWELS,
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
    "missing_syllable",
    "syllable_order",
    "word_to_syllables",
    "word_recognition",
    *MORPHOLOGY_EXERCISE_TYPES,
)
# Frequent syllables per language used only as a last-resort distractor pool.
SYLLABLE_DISTRACTORS_BY_LANGUAGE = {
    "kk": ["ба", "ла", "ма", "ры", "ға", "де", "не", "қа", "тан", "дар", "мыз", "лер"],
    "ru": ["по", "ра", "ло", "ни", "ка", "те", "ва", "ми", "со", "ре", "ду", "ста"],
    "en": ["ing", "er", "le", "re", "an", "ish", "ton", "ent", "ly", "ted"],
}
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
    if exercise_type == "word_recognition" and len(word) < 4:
        return False
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
    if exercise_type == "word_recognition":
        return build_word_recognition(features, difficulty_level, rng, language_hint)
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
        "word_recognition": {
            "en": "Find the correctly written word",
            "ru": "Найди правильно написанное слово",
            "kk": "Дұрыс жазылған сөзді тап",
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
    options = build_syllable_distractors(features, correct_answer, difficulty_level, rng, language_hint)
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
        "syllables": features.syllables,
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
        "syllables": features.syllables,
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


def build_syllable_distractors(
    features,
    correct_answer: str,
    difficulty_level: int,
    rng: random.Random,
    language_hint: str,
) -> list[str]:
    """Distractors for the missing syllable: other syllables of the same word,
    vowel-swapped variants, and visually confusable variants — all in the word's
    own language instead of a fixed foreign-syllable list."""
    options = {correct_answer}
    target_count = min(4, max(3, difficulty_level))

    # 1. Other syllables of the same word (forces attention to position).
    other_syllables = [
        syllable for syllable in features.syllables
        if syllable.lower() != correct_answer.lower()
    ]
    rng.shuffle(other_syllables)
    for syllable in other_syllables[:1]:
        options.add(syllable)

    # 2. Vowel-swapped variants of the correct syllable (а↔о, е↔и ...).
    vowels = sorted(vowel_set_for(features.original))
    positions = list(range(len(correct_answer)))
    rng.shuffle(positions)
    for index in positions:
        if len(options) >= target_count:
            break
        character = correct_answer[index]
        if character.lower() not in vowels:
            continue
        replacements = [vowel for vowel in vowels if vowel != character.lower()]
        rng.shuffle(replacements)
        for replacement in replacements[:2]:
            variant = correct_answer[:index] + match_case(character, replacement) + correct_answer[index + 1:]
            if variant.lower() != correct_answer.lower():
                options.add(variant)
            if len(options) >= target_count:
                break

    # 3. Visually confusable consonant variant.
    if len(options) < target_count:
        confusions = confusion_map_for(features.original)
        for index, character in enumerate(correct_answer):
            replacement = confusions.get(character.lower())
            if replacement is None:
                continue
            options.add(correct_answer[:index] + match_case(character, replacement) + correct_answer[index + 1:])
            if len(options) >= target_count:
                break

    # 4. Last resort: frequent syllables of the same language.
    if len(options) < target_count:
        fallback = SYLLABLE_DISTRACTORS_BY_LANGUAGE[normalize_language_hint(language_hint)][:]
        rng.shuffle(fallback)
        for distractor in fallback:
            if distractor.lower() != correct_answer.lower():
                options.add(distractor)
            if len(options) >= target_count:
                break

    option_list = dedupe_preserving_case(list(options))
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
