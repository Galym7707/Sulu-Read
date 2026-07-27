import logging
import os
from collections.abc import Generator

from sqlalchemy import create_engine, inspect, text
from sqlalchemy.engine import Engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from .config import settings


logger = logging.getLogger("sulu_read_backend.database")
SQLITE_FALLBACK_DATABASE_URL = "sqlite:///./sulu_read_local.db"


class Base(DeclarativeBase):
    pass


def create_database_engine(database_url: str) -> Engine:
    if database_url.startswith("sqlite"):
        connect_args = {"check_same_thread": False}
    else:
        connect_args = {"connect_timeout": read_database_connect_timeout_seconds()}
    return create_engine(
        database_url,
        connect_args=connect_args,
        pool_pre_ping=True,
    )


def read_database_connect_timeout_seconds() -> int:
    raw_value = os.getenv("SULU_READ_DB_CONNECT_TIMEOUT_SECONDS", "5")
    try:
        return max(1, int(raw_value))
    except ValueError:
        return 5


engine = create_database_engine(settings.database_url)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
_db_ready = False
_active_database_url = settings.database_url
_using_runtime_sqlite_fallback = settings.use_sqlite_fallback


def init_database() -> bool:
    global _db_ready
    try:
        initialize_schema(engine)
        _db_ready = True
        return True
    except Exception:
        logger.exception("Database initialization failed for configured database")

    if should_use_runtime_sqlite_fallback():
        try:
            switch_to_runtime_sqlite_fallback()
            initialize_schema(engine)
            _db_ready = True
            logger.warning("Using runtime SQLite fallback database at %s", SQLITE_FALLBACK_DATABASE_URL)
            return True
        except Exception:
            logger.exception("Runtime SQLite fallback initialization failed")

    _db_ready = False
    return False


def initialize_schema(database_engine: Engine) -> None:
    from . import models  # noqa: F401

    Base.metadata.create_all(bind=database_engine)
    ensure_schema_compatibility(database_engine)


def should_use_runtime_sqlite_fallback() -> bool:
    if settings.database_url.startswith("sqlite"):
        return False
    return os.getenv("SULU_READ_RUNTIME_SQLITE_FALLBACK", "true").strip().lower() in {
        "1",
        "true",
        "yes",
        "y",
        "on",
    }


def switch_to_runtime_sqlite_fallback() -> None:
    global engine, _active_database_url, _using_runtime_sqlite_fallback
    engine = create_database_engine(SQLITE_FALLBACK_DATABASE_URL)
    SessionLocal.configure(bind=engine)
    _active_database_url = SQLITE_FALLBACK_DATABASE_URL
    _using_runtime_sqlite_fallback = True


def ensure_schema_compatibility(database_engine: Engine | None = None) -> None:
    target_engine = database_engine or engine
    inspector = inspect(target_engine)
    table_names = set(inspector.get_table_names())
    if "exercise_attempts" in table_names:
        exercise_attempt_columns = {
            column["name"] for column in inspector.get_columns("exercise_attempts")
        }
        if "sub_exercise" not in exercise_attempt_columns:
            with target_engine.begin() as connection:
                connection.execute(text("ALTER TABLE exercise_attempts ADD COLUMN sub_exercise VARCHAR(80)"))

    if "user_profiles" not in table_names:
        return

    user_profile_columns = {
        column["name"] for column in inspector.get_columns("user_profiles")
    }
    missing_user_columns = {
        "username": "ALTER TABLE user_profiles ADD COLUMN username VARCHAR(80)",
        "password_hash": "ALTER TABLE user_profiles ADD COLUMN password_hash VARCHAR(160)",
        "password_salt": "ALTER TABLE user_profiles ADD COLUMN password_salt VARCHAR(64)",
    }
    with target_engine.begin() as connection:
        for column_name, statement in missing_user_columns.items():
            if column_name not in user_profile_columns:
                connection.execute(text(statement))


def check_database_ready() -> bool:
    global _db_ready
    try:
        with engine.connect() as connection:
            connection.execute(text("SELECT 1"))
        _db_ready = True
    except Exception:
        _db_ready = False
        logger.warning("Database health check failed", exc_info=True)
    return _db_ready


def using_runtime_sqlite_fallback() -> bool:
    return _using_runtime_sqlite_fallback


def get_db() -> Generator[Session, None, None]:
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
