from backend.app.services.exercise_generator import generate_exercises, is_roman_numeral, split_root_suffixes


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


def test_generate_specific_syllable_order_exercise():
    exercises = generate_exercises(
        source_words=["балаларымызға"],
        exercise_type="syllable_order",
        count=1,
        difficulty_level=5,
    )

    exercise = exercises[0]
    assert exercise["type"] == "syllable_order"
    assert exercise["correct_answer"] == "ба-ла-ла-ры-мыз-ға"
    assert sorted(exercise["options"]) == sorted(["ба", "ла", "ла", "ры", "мыз", "ға"])


def test_generate_mixed_level_one_includes_word_to_syllables_without_hanging():
    exercises = generate_exercises(
        source_words=[],
        exercise_type="mixed",
        count=4,
        difficulty_level=1,
    )

    assert len(exercises) == 4
    word_to_syllables = exercises[2]
    assert word_to_syllables["type"] == "word_to_syllables"
    assert len(word_to_syllables["options"]) == 4
    assert word_to_syllables["correct_answer"] in word_to_syllables["options"]


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


def test_roman_numerals_are_filtered_from_source_words():
    assert is_roman_numeral("xviii")
    exercises = generate_exercises(
        source_words=["xviii XVIII 123 ! i iv"],
        exercise_type="syllable_order",
        count=3,
        difficulty_level=1,
        language_hint="en",
    )

    assert len(exercises) == 3
    assert all(exercise["target_word"].lower() not in {"xviii", "i", "iv"} for exercise in exercises)
    assert all(not exercise["target_word"].isdigit() for exercise in exercises)


def test_syllable_order_falls_back_to_curated_words_when_source_has_no_valid_words():
    exercises = generate_exercises(
        source_words=["xviii 123 !"],
        exercise_type="syllable_order",
        count=2,
        difficulty_level=1,
        language_hint="kk",
    )

    assert len(exercises) == 2
    assert all(exercise["type"] == "syllable_order" for exercise in exercises)
    assert all(exercise["target_word"].lower() != "xviii" for exercise in exercises)
    for exercise in exercises:
        syllables = exercise["syllables"]
        assert len(syllables) >= 2
        assert len({syllable.lower() for syllable in syllables}) > 1
