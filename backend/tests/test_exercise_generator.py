from backend.app.services.exercise_generator import generate_exercises, is_roman_numeral, split_root_suffixes
from backend.app.services.text_preparation import prepare_word_features


def test_generate_mixed_exercises_from_source_words():
    exercises = generate_exercises(
        source_words=["балаларымызға", "Қазақстанның", "сұлтандарға", "қарапайым"],
        exercise_type="mixed",
        count=4,
        difficulty_level=3,
    )

    assert len(exercises) == 4
    assert {exercise["type"] for exercise in exercises}
    for exercise in exercises:
        assert exercise["target_word"]
        assert exercise["correct_answer"]
        assert "·" not in exercise["correct_answer"]
        assert 1 <= exercise["difficulty_level"] <= 5

def test_generate_english_exercises_when_language_hint_is_english():
    exercises = generate_exercises(
        source_words=["reading", "teacher", "window"],
        exercise_type="mixed",
        count=3,
        difficulty_level=2,
        language_hint="en",
    )

    assert len(exercises) == 3
    assert all(exercise["target_word"].isascii() for exercise in exercises)

def test_split_root_suffixes_for_kazakh_agglutinative_word():
    root, suffixes = split_root_suffixes("балаларымызға")

    assert root == "бала"
    assert suffixes == ["лар", "ымыз", "ға"]


def test_generate_root_suffix_identification_exercise():
    exercises = generate_exercises(
        source_words=["балаларымызға"],
        exercise_type="root_suffix_identification",
        count=1,
        difficulty_level=5,
    )

    exercise = exercises[0]
    assert exercise["type"] == "root_suffix_identification"
    assert exercise["sub_exercise"] == "morphology"
    assert exercise["correct_answer"] == "бала + лар + ымыз + ға"
    assert exercise["correct_answer"] in exercise["options"]


def test_generate_word_segmentation_exercise():
    exercises = generate_exercises(
        source_words=["балаларымызға"],
        exercise_type="word_segmentation",
        count=1,
        difficulty_level=5,
    )

    exercise = exercises[0]
    assert exercise["type"] == "word_segmentation"
    assert exercise["sub_exercise"] == "morphology"
    assert exercise["target_word"] == "ларымызға"
    assert exercise["correct_answer"] == "бала"
    assert "бала" in exercise["options"]


def test_generate_word_recognition_exercise():
    exercises = generate_exercises(
        source_words=["страница"],
        exercise_type="word_recognition",
        count=1,
        difficulty_level=3,
        language_hint="ru",
    )

    exercise = exercises[0]
    assert exercise["type"] == "word_recognition"
    assert exercise["correct_answer"] == "страница"
    assert exercise["correct_answer"] in exercise["options"]
    assert len(exercise["options"]) >= 3
    lowered = [option.lower() for option in exercise["options"]]
    assert len(set(lowered)) == len(lowered)

def test_mixed_session_includes_word_recognition():
    exercises = generate_exercises(
        source_words=["страница", "внимание", "учитель", "тетрадь", "чтение", "помощь"],
        exercise_type="mixed",
        count=6,
        difficulty_level=3,
        language_hint="ru",
    )

    assert len(exercises) == 6
    assert any(exercise["type"] == "word_recognition" for exercise in exercises)


def test_roman_numerals_are_filtered_from_source_words():
    assert is_roman_numeral("xviii")
    exercises = generate_exercises(
        source_words=["xviii XVIII 123 ! i iv"],
        exercise_type="word_recognition",
        count=3,
        difficulty_level=1,
        language_hint="en",
    )

    assert len(exercises) == 3
    assert all(exercise["target_word"].lower() not in {"xviii", "i", "iv"} for exercise in exercises)
    assert all(not exercise["target_word"].isdigit() for exercise in exercises)


def test_falls_back_to_curated_words_when_source_has_no_valid_words():
    exercises = generate_exercises(
        source_words=["xviii 123 !"],
        exercise_type="word_recognition",
        count=2,
        difficulty_level=1,
        language_hint="kk",
    )

    assert len(exercises) == 2
    assert all(exercise["type"] == "word_recognition" for exercise in exercises)
    assert all(exercise["target_word"].lower() != "xviii" for exercise in exercises)
    for exercise in exercises:
        assert exercise["correct_answer"] in exercise["options"]
