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

# Re-derived after the Kazakh-lexicon letter recovery landed (2026-07-27) and
# a Critical review finding was fixed on top of it: /v1/adapt-image defaults
# language_hint="kk", so Russian pages ran through the Kazakh repair path and
# real Russian words with a Kazakh-shaped restoration got rewritten (e.g.
# "доска" -> "досқа"). The fix added a Russian-dictionary guard (a word
# already valid in Russian is never touched) and the eval corpus grew three
# rows of ordinary Russian text under the "kk" hint (rk-01..rk-03) so the
# clean-input no-op gate can catch a regression of that class.
#
# With the lexicon repair now doing real Kazakh-letter recovery (not just
# homoglyph folding and the word-initial ң fix, as when this threshold was
# last 0.20), the measured CER reduction on the full corpus is ~72.2%. The
# threshold is a round number safely below the measured value.
MINIMUM_CER_REDUCTION = 0.60


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


MINIMUM_KAZAKH_WORD_RECOVERY = 0.35


def test_kazakh_letters_are_actually_restored(report):
    # The previous branch could not restore a single Kazakh letter; this asserts
    # the lexicon layer does, on the letter-drop channel specifically.
    recovery = report["kazakh_words_restored"] / max(1, report["kazakh_words_corrupted"])
    assert recovery >= MINIMUM_KAZAKH_WORD_RECOVERY, (
        f"Kazakh word recovery {recovery:.3f} is below the "
        f"{MINIMUM_KAZAKH_WORD_RECOVERY} target "
        f"({report['kazakh_words_restored']}/{report['kazakh_words_corrupted']} words)"
    )
