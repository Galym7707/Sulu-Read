from datetime import datetime
from typing import Any
from typing import Literal

from pydantic import BaseModel, Field, model_validator


ExerciseType = Literal[
    "mixed",
    "syllable_order",
    "missing_syllable",
    "word_to_syllables",
    "auditory_match",
    "word_recognition",
    "root_suffix_identification",
    "word_segmentation",
]

AiLanguage = Literal["kk", "ru", "en"]
AiMode = Literal[
    "explain",
    "exercise",
    "check_answer",
    "generate_task",
    "reading_help",
    "morphology_help",
]


class SkillProfileResponse(BaseModel):
    phonological_skill: float
    decoding_fluency: float
    visual_tracking: float
    current_difficulty: int
    updated_at: datetime | None = None


DEFAULT_DISPLAY_NAMES = {"kk": "Оқушы", "ru": "Ученик", "en": "Student"}


def default_display_name_for(language_preference: str) -> str:
    for code, name in DEFAULT_DISPLAY_NAMES.items():
        if language_preference.startswith(code):
            return name
    return DEFAULT_DISPLAY_NAMES["kk"]


class CreateUserRequest(BaseModel):
    # None means "not supplied", so the language-aware default below can fill it in. A plain
    # Field default could only ever be one language, which named every English and Russian
    # reader's profile "Оқушы".
    display_name: str | None = Field(default=None, min_length=1, max_length=120)
    age: int | None = Field(default=None, ge=3, le=18)
    language_preference: str = Field(default="kk-ru", max_length=20)

    @model_validator(mode="after")
    def fill_display_name(self) -> "CreateUserRequest":
        if self.display_name is None:
            self.display_name = default_display_name_for(self.language_preference)
        return self


class RegisterUserRequest(BaseModel):
    username: str = Field(min_length=3, max_length=80)
    password: str = Field(min_length=4, max_length=128)
    display_name: str | None = Field(default=None, min_length=1, max_length=120)
    age: int | None = Field(default=None, ge=3, le=18)
    language_preference: str = Field(default="kk", max_length=20)

    @model_validator(mode="after")
    def fill_display_name(self) -> "RegisterUserRequest":
        if self.display_name is None:
            self.display_name = default_display_name_for(self.language_preference)
        return self


class LoginUserRequest(BaseModel):
    username: str = Field(min_length=3, max_length=80)
    password: str = Field(min_length=4, max_length=128)


class UserResponse(BaseModel):
    user_id: str
    display_name: str
    username: str | None = None
    age: int | None
    language_preference: str
    skill_profile: SkillProfileResponse


class UpdateUserLanguageRequest(BaseModel):
    language_preference: str = Field(default="kk", max_length=20)


class GenerateExerciseRequest(BaseModel):
    user_id: str
    source_words: list[str] = Field(default_factory=list)
    exercise_type: ExerciseType = Field(default="mixed")
    count: int = Field(default=6, ge=1, le=10)
    language_hint: str = Field(default="kk", max_length=20)


class ExerciseResponse(BaseModel):
    exercise_id: str
    type: str
    sub_exercise: str | None = None
    prompt: str
    target_word: str
    syllables: list[str]
    options: list[str]
    correct_answer: str
    difficulty_level: int
    language_hint: str


class ExerciseAttemptRequest(BaseModel):
    user_id: str
    exercise_type: str
    sub_exercise: str | None = Field(default=None, max_length=80)
    target_word: str
    correct_answer: str
    user_answer: str
    response_time_ms: int = Field(ge=0)
    difficulty_level: int = Field(ge=1, le=5)
    language_hint: str


class ExerciseAttemptResponse(BaseModel):
    is_correct: bool
    updated_difficulty: int
    skill_profile: SkillProfileResponse
    feedback: str


class ReadingTestRequest(BaseModel):
    user_id: str
    words_total: int = Field(gt=0)
    words_read_correctly: int = Field(ge=0)
    errors_count: int = Field(ge=0)
    duration_ms: int = Field(gt=0)
    test_type: str = Field(default="short_reading")
    language_hint: str = Field(default="kk", max_length=20)


class ReadingTestResponse(BaseModel):
    wpm: float
    accuracy: float
    support_level: str
    disclaimer: str


class ScreeningResultResponse(BaseModel):
    id: str
    test_type: str
    words_total: int
    words_read_correctly: int
    errors_count: int
    duration_ms: int
    wpm: float
    accuracy: float
    support_level: str
    disclaimer: str
    created_at: datetime


class DailyActivityResponse(BaseModel):
    date: str
    exercises: int
    screenings: int


class DailyWpmResponse(BaseModel):
    date: str
    wpm: float
    accuracy: float


class ProgressResponse(BaseModel):
    user_id: str
    total_exercises: int
    exercise_accuracy: float
    average_response_time_ms: int
    latest_support_level: str | None
    skill_profile: SkillProfileResponse
    recent_screenings: list[ScreeningResultResponse]
    daily_activity: list[DailyActivityResponse]
    daily_wpm: list[DailyWpmResponse]


class SimplifyRequest(BaseModel):
    text: str = Field(min_length=1, max_length=10_000)
    language_hint: str = Field(default="kk")


class SimplifyResponse(BaseModel):
    status: str
    simplified_text: str


class WordFeature(BaseModel):
    original: str
    adapted: str
    syllables: list[str]
    language_hint: str
    vowel_harmony: str | None = None


class AdaptationResponseWithWords(BaseModel):
    status: str
    source: str
    original_text: str
    adapted_text: str
    word_count: int
    unique_word_count: int
    truncated: bool
    words: list[WordFeature]
    title: str | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)


class AiGenerateRequest(BaseModel):
    task: str = Field(min_length=1, max_length=4_000)
    text: str = Field(min_length=1, max_length=20_000)
    language: AiLanguage = "kk"
    level: str | None = Field(default=None, max_length=80)
    mode: AiMode = "explain"
    extra: dict[str, Any] = Field(default_factory=dict)


class AiGenerateResponse(BaseModel):
    success: bool = True
    provider: str
    model: str
    result: str


class AiGenerateErrorResponse(BaseModel):
    success: bool = False
    error: str
