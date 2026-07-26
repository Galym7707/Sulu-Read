"""Offline regression gate for OCR correction quality.

Runs the synthetic corpus in-process. No network, no images, deterministic.
"""

import sys
from pathlib import Path

import pytest

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from scripts.ocr_eval import evaluate

# Re-derived after the vowel-harmony rules were removed (2026-07-26 review):
# they were the source of most of the old reduction, and post-hoc harmony
# rewriting damaged correct text (see ocr_correction.py). The surviving
# rules (homoglyph folding, word-initial ң, the нын/дын genitive repair, the
# closed h-loanword set) measure ~29.5% CER reduction on the corpus. The
# threshold is set to a round number just below that measured value.
MINIMUM_CER_REDUCTION = 0.25


@pytest.fixture(scope="module")
def report():
    return evaluate("synthetic-noise")


def test_corpus_is_actually_corrupted(report):
    # Guards against a broken noise model silently making the gate trivial.
    assert report["cer_raw"] > 0.02


def test_correction_reduces_character_error_rate(report):
    reduction = 1 - report["cer_corrected"] / report["cer_raw"]
    assert reduction >= MINIMUM_CER_REDUCTION, (
        f"CER reduction {reduction:.3f} is below the {MINIMUM_CER_REDUCTION} target "
        f"(raw {report['cer_raw']:.4f} -> corrected {report['cer_corrected']:.4f})"
    )


def test_correction_does_not_hurt_word_error_rate(report):
    assert report["wer_corrected"] <= report["wer_raw"]


def test_russian_snippets_do_not_regress(report):
    assert report["russian_cer_corrected"] <= report["russian_cer_raw"] + 1e-9


def test_clean_input_is_never_altered(report):
    # The single most important gate on this branch: the correction layer
    # must never change text that already had no OCR noise in it.
    assert report["clean_input_no_op_count"] == 0
