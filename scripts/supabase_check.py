from pathlib import Path
import sys

from dotenv import load_dotenv
from sqlalchemy import create_engine, text


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT))

load_dotenv(PROJECT_ROOT / ".env")

from backend.app.config import normalize_database_url, read_env  # noqa: E402
from backend.app.database import Base  # noqa: E402


def main() -> int:
    raw_database_url = read_env(
        "SUPABASE_DIRECT_CONNECTION_STRING",
        "SUPABASE_direct_connection_string",
    )
    if not raw_database_url:
        print("Supabase check failed: SUPABASE_DIRECT_CONNECTION_STRING is not set.")
        return 2

    database_url = normalize_database_url(raw_database_url)
    if database_url.startswith("sqlite"):
        print("Supabase check failed: SUPABASE_DIRECT_CONNECTION_STRING must point to PostgreSQL.")
        return 2

    engine = create_engine(database_url, pool_pre_ping=True, hide_parameters=True)
    try:
        from backend.app import models  # noqa: F401

        Base.metadata.create_all(bind=engine)
        with engine.connect() as connection:
            result = connection.execute(text("SELECT 1")).scalar_one()

        print(
            "Supabase check succeeded: connected to PostgreSQL, ensured tables, "
            f"and SELECT 1 returned {result}."
        )
        return 0
    except Exception as exc:
        print(f"Supabase check failed: {exc.__class__.__name__}: {exc}")
        return 1
    finally:
        engine.dispose()


if __name__ == "__main__":
    raise SystemExit(main())
