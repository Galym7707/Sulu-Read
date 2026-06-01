import asyncio
from types import SimpleNamespace

import main


def test_health_reports_database_not_ready_when_check_fails(monkeypatch):
    state = SimpleNamespace(
        db_ready=True,
        ocr_reader=None,
        ocr_status="error",
        ocr_languages=[],
        ocr_error=None,
    )
    request = SimpleNamespace(app=SimpleNamespace(state=state))
    monkeypatch.setattr(main, "check_database_ready", lambda: False)
    monkeypatch.setattr(main, "has_groq_vision_key", lambda: False)

    response = asyncio.run(main.health(request))

    assert response["db_ready"] is False
    assert request.app.state.db_ready is False
