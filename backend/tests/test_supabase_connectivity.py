import os

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.exc import SQLAlchemyError


RUN_INTEGRATION_TEST = os.getenv("RUN_SUPABASE_INTEGRATION_TESTS") == "1"
SUPABASE_URL = os.getenv("SUPABASE_DIRECT_CONNECTION_STRING", "").strip()

pytestmark = pytest.mark.skipif(
    not RUN_INTEGRATION_TEST or not SUPABASE_URL,
    reason=(
        "Set RUN_SUPABASE_INTEGRATION_TESTS=1 and export "
        "SUPABASE_DIRECT_CONNECTION_STRING to run the live Supabase integration check."
    ),
)


def test_supabase_connection_and_auto_table_creation():
    from backend.app.config import normalize_database_url
    from backend.app.database import Base

    database_url = normalize_database_url(SUPABASE_URL)
    if database_url.startswith("sqlite"):
        pytest.skip("SUPABASE_DIRECT_CONNECTION_STRING must point to Supabase PostgreSQL")

    engine = create_engine(database_url, pool_pre_ping=True)
    try:
        from backend.app import models  # noqa: F401

        Base.metadata.create_all(bind=engine)
        with engine.connect() as connection:
            assert connection.execute(text("SELECT 1")).scalar_one() == 1
    except SQLAlchemyError as exc:
        pytest.fail(f"Supabase connectivity check failed: {exc.__class__.__name__}")
    finally:
        engine.dispose()
