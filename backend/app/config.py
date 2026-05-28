import logging
import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv
from sqlalchemy.engine import make_url


logger = logging.getLogger("sulu_read_backend.config")

PROJECT_ROOT = Path(__file__).resolve().parents[2]
load_dotenv(PROJECT_ROOT / ".env")


@dataclass(frozen=True)
class Settings:
    database_url: str
    supabase_project_url: str | None
    supabase_publishable_key: str | None
    groq_api_key: str | None
    groq_vision_model: str
    max_image_bytes: int
    max_text_chars: int
    url_extraction_timeout_seconds: float
    ocr_timeout_seconds: float
    groq_timeout_seconds: float
    use_sqlite_fallback: bool


def _read_int(name: str, default: int) -> int:
    raw_value = os.getenv(name)
    if raw_value is None:
        return default
    try:
        return int(raw_value)
    except ValueError:
        logger.warning("Invalid integer for %s=%r; using %s", name, raw_value, default)
        return default


def _read_float(name: str, default: float) -> float:
    raw_value = os.getenv(name)
    if raw_value is None:
        return default
    try:
        return float(raw_value)
    except ValueError:
        logger.warning("Invalid float for %s=%r; using %s", name, raw_value, default)
        return default


def normalize_database_url(raw_database_url: str) -> str:
    if not raw_database_url:
        return ""
    if raw_database_url.startswith("sqlite"):
        return raw_database_url

    try:
        parsed_url = make_url(raw_database_url)
    except Exception:
        return raw_database_url

    normalized_url = parsed_url
    if parsed_url.drivername == "postgresql":
        normalized_url = normalized_url.set(drivername="postgresql+psycopg")

    if parsed_url.host and parsed_url.host.startswith("@"):
        leading_at_count = len(parsed_url.host) - len(parsed_url.host.lstrip("@"))
        normalized_url = normalized_url.set(
            password=(parsed_url.password or "") + ("@" * leading_at_count),
            host=parsed_url.host.lstrip("@"),
        )

    if normalized_url.drivername.startswith("postgresql") and "sslmode" not in normalized_url.query:
        normalized_url = normalized_url.set(query={**normalized_url.query, "sslmode": "require"})

    if normalized_url is not parsed_url:
        return normalized_url.render_as_string(hide_password=False)
    return raw_database_url


def load_settings() -> Settings:
    database_url = normalize_database_url(os.getenv("SUPABASE_DIRECT_CONNECTION_STRING", "").strip())
    use_sqlite_fallback = False
    if not database_url:
        database_url = "sqlite:///./sulu_read_local.db"
        use_sqlite_fallback = True
        logger.warning(
            "SUPABASE_DIRECT_CONNECTION_STRING is missing; using local SQLite fallback at %s",
            database_url,
        )

    return Settings(
        database_url=database_url,
        supabase_project_url=os.getenv("SUPABASE_PROJECT_URL"),
        supabase_publishable_key=os.getenv("SUPABASE_PUBLISHABLE_KEY"),
        groq_api_key=(os.getenv("GROQ_API") or os.getenv("GROQ_API_KEY") or "").strip() or None,
        groq_vision_model=os.getenv(
            "SULU_READ_GROQ_VISION_MODEL",
            "meta-llama/llama-4-scout-17b-16e-instruct",
        ),
        max_image_bytes=_read_int("SULU_READ_MAX_IMAGE_BYTES", 15 * 1024 * 1024),
        max_text_chars=_read_int("SULU_READ_MAX_TEXT_CHARS", 50_000),
        url_extraction_timeout_seconds=_read_float("SULU_READ_URL_TIMEOUT_SECONDS", 20.0),
        ocr_timeout_seconds=_read_float("SULU_READ_OCR_TIMEOUT_SECONDS", 120.0),
        groq_timeout_seconds=_read_float("SULU_READ_GROQ_TIMEOUT_SECONDS", 90.0),
        use_sqlite_fallback=use_sqlite_fallback,
    )


settings = load_settings()
DATABASE_URL = settings.database_url
