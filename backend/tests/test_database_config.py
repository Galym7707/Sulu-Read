from sqlalchemy.engine import make_url

from backend.app.config import normalize_database_url


def test_normalize_database_url_adds_psycopg_driver_and_sslmode():
    normalized = normalize_database_url(
        "postgresql://postgres:plain-password@db.example.supabase.co:5432/postgres"
    )

    parsed = make_url(normalized)

    assert parsed.drivername == "postgresql+psycopg"
    assert parsed.host == "db.example.supabase.co"
    assert parsed.password == "plain-password"
    assert parsed.query["sslmode"] == "require"


def test_normalize_database_url_preserves_special_characters_in_password():
    normalized = normalize_database_url(
        "postgresql://postgres:pa@ss:word#1@db.example.supabase.co:5432/postgres"
    )

    parsed = make_url(normalized)

    assert parsed.drivername == "postgresql+psycopg"
    assert parsed.host == "db.example.supabase.co"
    assert parsed.password == "pa@ss:word#1"
    assert parsed.query["sslmode"] == "require"


def test_normalize_database_url_preserves_existing_sslmode():
    normalized = normalize_database_url(
        "postgres://postgres:password@db.example.supabase.co/postgres?sslmode=verify-full"
    )

    parsed = make_url(normalized)

    assert parsed.drivername == "postgresql+psycopg"
    assert parsed.query["sslmode"] == "verify-full"
