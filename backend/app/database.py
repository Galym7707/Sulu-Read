import logging
from collections.abc import Generator

from sqlalchemy import create_engine, inspect, text
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from .config import settings


logger = logging.getLogger("sulu_read_backend.database")


class Base(DeclarativeBase):
    pass


connect_args = {"check_same_thread": False} if settings.database_url.startswith("sqlite") else {}
engine = create_engine(
    settings.database_url,
    connect_args=connect_args,
    pool_pre_ping=True,
)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
_db_ready = False


def init_database() -> bool:
    global _db_ready
    try:
        from . import models  # noqa: F401

        Base.metadata.create_all(bind=engine)
        ensure_schema_compatibility()
        _db_ready = True
        return True
    except Exception:
        _db_ready = False
        logger.exception("Database initialization failed")
        return False


def ensure_schema_compatibility() -> None:
    inspector = inspect(engine)
    table_names = set(inspector.get_table_names())
    if "exercise_attempts" in table_names:
        exercise_attempt_columns = {
            column["name"] for column in inspector.get_columns("exercise_attempts")
        }
        if "sub_exercise" not in exercise_attempt_columns:
            with engine.begin() as connection:
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
    with engine.begin() as connection:
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


def get_db() -> Generator[Session, None, None]:
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
