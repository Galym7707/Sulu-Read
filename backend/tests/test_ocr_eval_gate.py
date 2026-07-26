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

# Re-derived after a second review round (2026-07-26) deleted the last
# suffix-repair rules (нын/дын -> ның/дың): they collided with the
# productive -ын/-ін possessive-accusative pattern (e.g. "телефонын",
# "орнын") and rewrote correctly-spelled words into different, wrong ones.
# The eval corpus also grew four snippets that exercise that exact pattern
# (kk-21..kk-24), which is why this number moved from the prior ~29.5%.
#
# What remains in the Kazakh path (homoglyph folding, word-initial ң, the
# closed h-loanword set) measures ~25.6% CER reduction on the corpus.
# Almost all of that reduction is homoglyph folding, not Kazakh-letter
# repair: see test_homoglyph_folding_reduces_character_error_rate below and
# its docstring. The threshold is a round number just below the measured
# value.
MINIMUM_CER_REDUCTION = 0.20


@pytest.fixture(scope="module")
def report():
    return evaluate("synthetic-noise")


def test_corpus_is_actually_corrupted(report):
    # Guards against a broken noise model silently making the gate trivial.
    assert report["cer_raw"] > 0.02


def test_homoglyph_folding_reduces_character_error_rate(report):
    # Despite the name of the fields involved (cer_raw/cer_corrected cover
    # the whole corpus, both corruption channels combined), this gate is
    # driven almost entirely by homoglyph folding, not Kazakh-letter repair:
    # the isolated-channel measurement shows the Kazakh-letter-drop channel
    # gets ZERO CER improvement from correction (raw == corrected), while
    # the homoglyph channel goes from noisy to perfect. Homoglyph folding
    # (Latin/Cyrillic lookalike repair, an exact bijection) is real,
    # useful production behavior and is worth a regression gate on its
    # own merits -- it must simply not be read as evidence about Kazakh
    # letter correction, which this rule set barely touches anymore.
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
