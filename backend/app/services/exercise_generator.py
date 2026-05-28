import random
import re
from uuid import uuid4

from .syllabification import (
    STANDARD_SYLLABLE_DELIMITER,
    detect_language,
    prepare_word_features,
    split_text_to_words,
)


PRACTICE_WORD_BANK = [
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
    "учитель",
    "тетрадь",
    "ребята",
    "чтение",
    "слово",
    "страница",
    "помощь",
    "внимание",
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
EXERCISE_TYPES = ("syllable_order", "missing_syllable", "word_to_syllables", "auditory_match")
SYLLABLE_DISTRACTORS = ["ба", "ла", "ма", "ры", "ға", "де", "не", "қа", "тан", "дар", "по", "ра"]


def generate_exercises(
    *,
    source_words: list[str],
    exercise_type: str,
    count: int,
    difficulty_level: int,
    language_hint: str = "kk",
) -> list[dict]:
    rng = random.SystemRandom()
    candidates = select_candidate_words(source_words, difficulty_level, language_hint)
    if not candidates:
        candidates = select_candidate_words(practice_bank_for_language(language_hint), difficulty_level, language_hint)

    if not candidates:
        candidates = practice_bank_for_language(language_hint)[:]

    rng.shuffle(candidates)
    selected_words = (candidates * ((count // len(candidates)) + 1))[:count]
    exercises: list[dict] = []

    for index, word in enumerate(selected_words):
        selected_type = choose_exercise_type(exercise_type, index)
        exercises.append(build_exercise(word, selected_type, difficulty_level, rng))

    return exercises


def select_candidate_words(source_words: list[str], difficulty_level: int, language_hint: str = "kk") -> list[str]:
    seen: set[str] = set()
    candidates: list[str] = []
    for raw_word in source_words:
        for word in split_source_words(raw_word):
            normalized = word.strip()
            lowered = normalized.lower()
            if len(lowered) < 4 or lowered in seen:
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
    if cyrillic_words:
        return cyrillic_words
    return re.findall(r"[A-Za-z]+", text)


def practice_bank_for_language(language_hint: str) -> list[str]:
    if language_hint.startswith("en"):
        return ENGLISH_PRACTICE_WORD_BANK
    if language_hint.startswith("ru"):
        return [word for word in PRACTICE_WORD_BANK if detect_language(word) == "ru"]
    return [word for word in PRACTICE_WORD_BANK if detect_language(word) in {"kk", "ru"}]


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


def build_exercise(word: str, exercise_type: str, difficulty_level: int, rng: random.Random) -> dict:
    features = prepare_word_features(word)
    syllables = features.syllables
    correct_answer = features.adapted

    if exercise_type == "missing_syllable":
        return build_missing_syllable(features, difficulty_level, rng)
    if exercise_type == "word_to_syllables":
        return build_word_to_syllables(features, difficulty_level, rng)
    if exercise_type == "auditory_match":
        return build_auditory_match(features, difficulty_level, rng)

    options = syllables[:]
    rng.shuffle(options)
    return {
        "exercise_id": str(uuid4()),
        "type": "syllable_order",
        "prompt": "Буындарды дұрыс ретпен орналастыр / Расставь слоги по порядку",
        "target_word": word,
        "syllables": syllables,
        "options": options,
        "correct_answer": correct_answer,
        "difficulty_level": difficulty_level,
        "language_hint": features.language_hint,
    }


def build_missing_syllable(features, difficulty_level: int, rng: random.Random) -> dict:
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
        "language_hint": features.language_hint,
    }


def build_word_to_syllables(features, difficulty_level: int, rng: random.Random) -> dict:
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
        "prompt": f"{features.original}: дұрыс буындауды таңда / выбери правильное деление",
        "target_word": features.original,
        "syllables": features.syllables,
        "options": option_list[:4],
        "correct_answer": correct_answer,
        "difficulty_level": difficulty_level,
        "language_hint": features.language_hint,
    }


def build_auditory_match(features, difficulty_level: int, rng: random.Random) -> dict:
    same_language_words = [
        word
        for word in PRACTICE_WORD_BANK
        if word.lower() != features.original.lower() and detect_language(word) == features.language_hint
    ]
    rng.shuffle(same_language_words)
    options = [features.original, *same_language_words[:3]]
    rng.shuffle(options)
    return {
        "exercise_id": str(uuid4()),
        "type": "auditory_match",
        "prompt": "Тыңдап, дұрыс сөзді таңда / Послушай и выбери слово",
        "target_word": features.original,
        "syllables": features.syllables,
        "options": options,
        "correct_answer": features.original,
        "difficulty_level": difficulty_level,
        "language_hint": features.language_hint,
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
