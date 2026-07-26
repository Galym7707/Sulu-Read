import main


def test_correction_enabled_by_default(monkeypatch):
    monkeypatch.delenv("SULU_READ_OCR_CORRECTION", raising=False)
    assert main.ocr_correction_enabled() is True


def test_correction_kill_switch(monkeypatch):
    monkeypatch.setenv("SULU_READ_OCR_CORRECTION", "false")
    assert main.ocr_correction_enabled() is False


def test_apply_ocr_correction_repairs_kazakh(monkeypatch):
    monkeypatch.delenv("SULU_READ_OCR_CORRECTION", raising=False)
    assert main.apply_ocr_correction("кала", "kk") == "қала"


def test_apply_ocr_correction_respects_kill_switch(monkeypatch):
    monkeypatch.setenv("SULU_READ_OCR_CORRECTION", "false")
    assert main.apply_ocr_correction("кала", "kk") == "кала"


def test_apply_ocr_correction_survives_a_broken_corrector(monkeypatch):
    monkeypatch.delenv("SULU_READ_OCR_CORRECTION", raising=False)

    def explode(*args, **kwargs):
        raise RuntimeError("boom")

    monkeypatch.setattr(main, "correct_ocr_text", explode)
    assert main.apply_ocr_correction("кала", "kk") == "кала"
