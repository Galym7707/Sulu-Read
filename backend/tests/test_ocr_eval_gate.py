"""Offline regression gate for OCR correction quality.

Runs the synthetic corpus in-process. No network, no images, deterministic.
"""

import sys
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from scripts.ocr_eval import evaluate

MINIMUM_CER_REDUCTION = 0.40

REPORT = evaluate("synthetic-noise")


def test_corpus_is_actually_corrupted():
    # Guards against a broken noise model silently making the gate trivial.
    assert REPORT["cer_raw"] > 0.02


def test_correction_reduces_character_error_rate():
    reduction = 1 - REPORT["cer_corrected"] / REPORT["cer_raw"]
    assert reduction >= MINIMUM_CER_REDUCTION, (
        f"CER reduction {reduction:.3f} is below the {MINIMUM_CER_REDUCTION} target "
        f"(raw {REPORT['cer_raw']:.4f} -> corrected {REPORT['cer_corrected']:.4f})"
    )


def test_correction_does_not_hurt_word_error_rate():
    assert REPORT["wer_corrected"] <= REPORT["wer_raw"]


def test_russian_snippets_do_not_regress():
    assert REPORT["russian_cer_corrected"] <= REPORT["russian_cer_raw"] + 1e-9


def test_kazakh_qaf_and_ghayn_are_recovered():
    for letter in ("қ", "ғ"):
        raw = REPORT["kazakh_letters_raw"][letter]
        corrected = REPORT["kazakh_letters_corrected"][letter]
        assert corrected["present"] > raw["present"], f"no recovery for {letter}"
