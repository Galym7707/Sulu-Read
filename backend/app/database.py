import logging
from collections.abc import Generator

from sqlalchemy import create_engine, text
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
        _db_ready = True
        return True
    except Exception:
        _db_ready = False
        logger.exception("Database initialization failed")
        return False


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
