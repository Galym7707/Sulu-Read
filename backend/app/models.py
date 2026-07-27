from datetime import datetime, timezone
from uuid import uuid4

from sqlalchemy import Boolean, DateTime, Float, ForeignKey, Integer, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .database import Base


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def new_uuid() -> str:
    return str(uuid4())


class UserProfile(Base):
    __tablename__ = "user_profiles"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    display_name: Mapped[str] = mapped_column(String(120), nullable=False)
    username: Mapped[str | None] = mapped_column(String(80), nullable=True, index=True)
    password_hash: Mapped[str | None] = mapped_column(String(160), nullable=True)
    password_salt: Mapped[str | None] = mapped_column(String(64), nullable=True)
    age: Mapped[int | None] = mapped_column(Integer, nullable=True)
    language_preference: Mapped[str] = mapped_column(String(20), nullable=False, default="kk-ru")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, nullable=False)

    skill_profile: Mapped["UserSkillProfile"] = relationship(
        back_populates="user",
        cascade="all, delete-orphan",
        uselist=False,
    )
    reading_sessions: Mapped[list["ReadingSession"]] = relationship(back_populates="user")
    exercise_attempts: Mapped[list["ExerciseAttempt"]] = relationship(back_populates="user")
    screening_results: Mapped[list["ScreeningResult"]] = relationship(back_populates="user")


class UserSkillProfile(Base):
    __tablename__ = "user_skill_profiles"

    user_id: Mapped[str] = mapped_column(
        String(36),
        ForeignKey("user_profiles.id", ondelete="CASCADE"),
        primary_key=True,
    )
    phonological_skill: Mapped[float] = mapped_column(Float, default=0.50, nullable=False)
    decoding_fluency: Mapped[float] = mapped_column(Float, default=0.50, nullable=False)
    visual_tracking: Mapped[float] = mapped_column(Float, default=0.50, nullable=False)
    current_difficulty: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, nullable=False)

    user: Mapped[UserProfile] = relationship(back_populates="skill_profile")


class ReadingSession(Base):
    __tablename__ = "reading_sessions"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("user_profiles.id"), nullable=False, index=True)
    source_type: Mapped[str] = mapped_column(String(40), nullable=False)
    original_text_hash: Mapped[str | None] = mapped_column(String(128), nullable=True)
    adapted_word_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, nullable=False)
    ended_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    duration_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    words_read: Mapped[int | None] = mapped_column(Integer, nullable=True)
    self_report_difficulty: Mapped[int | None] = mapped_column(Integer, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, nullable=False)

    user: Mapped[UserProfile] = relationship(back_populates="reading_sessions")


class ExerciseAttempt(Base):
    __tablename__ = "exercise_attempts"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("user_profiles.id"), nullable=False, index=True)
    exercise_type: Mapped[str] = mapped_column(String(60), nullable=False)
    sub_exercise: Mapped[str | None] = mapped_column(String(80), nullable=True)
    target_word: Mapped[str] = mapped_column(String(240), nullable=False)
    correct_answer: Mapped[str] = mapped_column(String(500), nullable=False)
    user_answer: Mapped[str] = mapped_column(String(500), nullable=False)
    is_correct: Mapped[bool] = mapped_column(Boolean, nullable=False)
    response_time_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    difficulty_level: Mapped[int] = mapped_column(Integer, nullable=False)
    language_hint: Mapped[str] = mapped_column(String(20), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, nullable=False)

    user: Mapped[UserProfile] = relationship(back_populates="exercise_attempts")


class ScreeningResult(Base):
    __tablename__ = "screening_results"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("user_profiles.id"), nullable=False, index=True)
    test_type: Mapped[str] = mapped_column(String(60), nullable=False)
    words_total: Mapped[int] = mapped_column(Integer, nullable=False)
    words_read_correctly: Mapped[int] = mapped_column(Integer, nullable=False)
    errors_count: Mapped[int] = mapped_column(Integer, nullable=False)
    duration_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    wpm: Mapped[float] = mapped_column(Float, nullable=False)
    accuracy: Mapped[float] = mapped_column(Float, nullable=False)
    support_level: Mapped[str] = mapped_column(String(20), nullable=False)
    disclaimer: Mapped[str] = mapped_column(String(500), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, nullable=False)

    user: Mapped[UserProfile] = relationship(back_populates="screening_results")
