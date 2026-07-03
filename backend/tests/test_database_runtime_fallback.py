from backend.app import schemas
from backend.app import database
from backend.app.routers.users import login_user, register_user


def test_runtime_sqlite_fallback_supports_registration(tmp_path, monkeypatch):
    original_engine = database.engine
    original_database_url = database._active_database_url
    original_fallback_flag = database._using_runtime_sqlite_fallback
    fallback_url = f"sqlite:///{(tmp_path / 'fallback.db').as_posix()}"
    monkeypatch.setattr(database, "SQLITE_FALLBACK_DATABASE_URL", fallback_url)

    try:
        database.switch_to_runtime_sqlite_fallback()
        database.initialize_schema(database.engine)
        db = database.SessionLocal()
        try:
            registered = register_user(
                schemas.RegisterUserRequest(
                    username="FallbackUser",
                    password="test1234",
                    display_name="Fallback User",
                    language_preference="en",
                ),
                db,
            )
            logged_in = login_user(
                schemas.LoginUserRequest(username="fallbackuser", password="test1234"),
                db,
            )
        finally:
            db.close()

        assert registered.username == "fallbackuser"
        assert logged_in.user_id == registered.user_id
        assert database.using_runtime_sqlite_fallback() is True
    finally:
        database.engine = original_engine
        database.SessionLocal.configure(bind=original_engine)
        database._active_database_url = original_database_url
        database._using_runtime_sqlite_fallback = original_fallback_flag
