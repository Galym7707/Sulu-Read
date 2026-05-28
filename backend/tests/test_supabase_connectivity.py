import os
import socket
from pathlib import Path

import pytest
from dotenv import load_dotenv
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import SQLAlchemyError

from backend.app.config import normalize_database_url, read_env


load_dotenv(Path(__file__).resolve().parents[2] / ".env")
SUPABASE_URL = read_env("SUPABASE_DIRECT_CONNECTION_STRING", "SUPABASE_direct_connection_string")

pytestmark = pytest.mark.skipif(
    not SUPABASE_URL,
    reason=(
        "Set SUPABASE_DIRECT_CONNECTION_STRING to run the live Supabase integration check."
    ),
)


def test_supabase_connection_and_auto_table_creation():
    from backend.app.database import Base

    database_url = normalize_database_url(SUPABASE_URL)
    if database_url.startswith("sqlite"):
        pytest.skip("SUPABASE_DIRECT_CONNECTION_STRING must point to Supabase PostgreSQL")

    parsed_url = make_url(database_url)
    if parsed_url.host:
        try:
            socket.getaddrinfo(parsed_url.host, parsed_url.port or 5432)
        except OSError:
            pytest.skip("Supabase host could not be resolved from this environment.")

    engine = create_engine(database_url, pool_pre_ping=True, hide_parameters=True)
    try:
        from backend.app import models  # noqa: F401

        Base.metadata.create_all(bind=engine)
        with engine.connect() as connection:
            assert connection.execute(text("SELECT 1")).scalar_one() == 1
    except SQLAlchemyError as exc:
        pytest.fail(f"Supabase connectivity check failed: {exc.__class__.__name__}", pytrace=False)
    finally:
        engine.dispose()
