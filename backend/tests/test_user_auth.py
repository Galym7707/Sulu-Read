import pytest
from fastapi import HTTPException
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from backend.app import schemas
from backend.app.database import Base
from backend.app.routers.users import login_user, register_user


def test_register_and_login_user():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(bind=engine)
    Session = sessionmaker(bind=engine)
    db = Session()
    try:
        registered = register_user(
            schemas.RegisterUserRequest(
                username="ReaderOne",
                password="secret123",
                display_name="Reader One",
                language_preference="en",
            ),
            db,
        )

        assert registered.username == "readerone"
        assert registered.language_preference == "en"

        logged_in = login_user(
            schemas.LoginUserRequest(username="readerone", password="secret123"),
            db,
        )

        assert logged_in.user_id == registered.user_id
        assert logged_in.username == "readerone"
    finally:
        db.close()


def test_login_rejects_wrong_password():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(bind=engine)
    Session = sessionmaker(bind=engine)
    db = Session()
    try:
        register_user(
            schemas.RegisterUserRequest(username="reader", password="secret123"),
            db,
        )

        with pytest.raises(HTTPException) as exc:
            login_user(
                schemas.LoginUserRequest(username="reader", password="wrong123"),
                db,
            )

        assert exc.value.status_code == 401
    finally:
        db.close()
