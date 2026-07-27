# Kazakh Lexicon Letter Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore Kazakh-specific letters lost by OCR using a bundled Kazakh dictionary as the decision procedure, so that no word is ever changed unless the dictionary proves exactly one restoration is a real Kazakh word.

**Architecture:** A vendored hunspell Kazakh dictionary (`kk_KZ.dic` + `kk_KZ.aff`) is read by a new `kazakh_lexicon` service exposing a single `contains(word)` predicate implemented as a hunspell single-suffix match. `ocr_correction` uses it: a scanned word already in the lexicon is never touched; otherwise candidates over the confusable set are generated and the word changes only on a unique lexicon hit.

**Tech Stack:** Python 3.11+, FastAPI, pytest. Standard library only — `re`, `pathlib`, `threading`, `itertools`.

## Global Constraints

- No new entry in `requirements.txt`. The lexicon module imports only the standard library.
- The two data files are vendored **byte-for-byte** and never edited. `backend/app/data/kk_KZ.dic` sha256 `80090f69c0d098425020ab378084d05ec7a4a90155750faf73742cdde7088012`; `backend/app/data/kk_KZ.aff` sha256 `254293c1c6ae893b87ec5c1fea3b72f696fe7821a3d87740ebad86b780d6e33a`. They contain a UTF-8 BOM and CRLF line endings — git must not normalize them.
- Data files are third-party under **MPL 1.1**; the rest of the repo is MIT. The license text ships beside them.
- `is_kazakh_word` / `contains` must be **generous**: over-accepting only prevents a repair (safe); under-accepting causes damage.
- A word already in the lexicon is **never** modified.
- A repair requires **exactly one** candidate in the lexicon. Zero or ≥2 → leave the word unchanged.
- Word-final `н→ң` is excluded from candidate generation. Repairs require a word of ≥4 characters, fully alphabetic, with at most 6 ambiguous positions and at most 256 candidates evaluated.
- Missing or unreadable data files degrade to "no lexicon, no repairs" — never an exception.
- The clean-input no-op gate must stay at exactly 0. It is the primary safety net.
- Existing behavior stays: the Kazakh document gate, `SULU_READ_OCR_CORRECTION`, homoglyph folding, word-initial `ң`, and the `һ` table are unchanged.
- Baseline before this plan: `73 passed, 1 skipped`; eval CER 0.0749 → 0.0558; Kazakh-drop channel 0% recovery.
- Spec: `docs/superpowers/specs/2026-07-27-kazakh-lexicon-letter-recovery-design.md`.

## Environment

- Work from `C:\Users\Alyhan\Claude\Projects\Sulu-Read`, branch `ocr-lexicon-recovery` (already created; do not switch branches).
- Use `.venv/Scripts/python.exe` for every command. `PYTHONIOENCODING=utf-8` is required to print Kazakh on this console.
- Commit with `git -c user.name="fitness-trener" -c user.email="browskii93@gmail.com" commit -m "..."`.
- The dictionary files are already copied into `backend/app/data/` but are **untracked**. Task 1 commits them.

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `backend/app/data/kk_KZ.dic`, `kk_KZ.aff` | add | Vendored dictionary data, never edited |
| `backend/app/data/LICENSE-kk_KZ.txt` | create | Upstream license + attribution |
| `.gitattributes` | create | Stop git normalizing the data files |
| `backend/app/services/kazakh_lexicon.py` | create | Dictionary parsing + `contains` predicate |
| `backend/tests/test_kazakh_lexicon.py` | create | Lexicon unit tests |
| `backend/app/services/ocr_correction.py` | modify | Lexicon-gated letter repair |
| `backend/tests/test_ocr_correction.py` | modify | Repair + regression tests |
| `scripts/ocr_eval.py` | modify | Per-channel recovery reporting |
| `backend/tests/test_ocr_eval_gate.py` | modify | Kazakh-channel threshold |
| `README.md` | modify | Attribution + behavior description |

---

### Task 1: Vendor the dictionary with its license

**Files:**
- Add: `backend/app/data/kk_KZ.dic`, `backend/app/data/kk_KZ.aff` (already present, untracked)
- Create: `backend/app/data/LICENSE-kk_KZ.txt`
- Create: `.gitattributes`
- Test: `backend/tests/test_kazakh_dictionary_data.py`

**Interfaces:**
- Consumes: nothing.
- Produces: the two data files at those exact paths, and their sha256 values, relied on by Task 2.

- [ ] **Step 1: Stop git from normalizing the data files**

The files carry a UTF-8 BOM and CRLF line endings. With git's default `core.autocrlf` on Windows, committing them would rewrite the line endings and change their checksums, breaking byte-for-byte vendoring.

Create `.gitattributes` at the repository root:

```
backend/app/data/kk_KZ.dic -text
backend/app/data/kk_KZ.aff -text
```

- [ ] **Step 2: Write the license and attribution file**

Create `backend/app/data/LICENSE-kk_KZ.txt`:

```
Kazakh spelling dictionary for hunspell (kk_KZ.dic, kk_KZ.aff)
==============================================================

Source: https://github.com/taem/hunspell-kk
Derived from aspell-kk_KZ 0.60.

Authors and contributors:
  Alexey Lipchansky      - orthographical dictionary for Aspell
  Akmaral Mussayeva      - affix file
  Laszlo Nemeth          - affix file development
  Rail Aliev             - affix file development
  Kaldybai Bektaiuly     - dictionary appendixes used as the basis

Upstream license, verbatim:
  "GNU GPL version 2.0 or above, GNU LGPL version 2.1 or above and
   Mozilla MPL version 1.1 or above."

Sulu-Read elects the Mozilla Public License version 1.1 for these two files.
The full MPL 1.1 text is available at https://www.mozilla.org/MPL/1.1/

These files are third-party data, vendored byte-for-byte and never modified.
The rest of this repository is MIT licensed; MPL 1.1 is file-level copyleft and
applies only to the two dictionary files in this directory.
```

- [ ] **Step 3: Write the failing test**

Create `backend/tests/test_kazakh_dictionary_data.py`:

```python
import hashlib
from pathlib import Path

DATA_DIRECTORY = Path(__file__).resolve().parents[1] / "app" / "data"
DICTIONARY_SHA256 = "80090f69c0d098425020ab378084d05ec7a4a90155750faf73742cdde7088012"
AFFIX_SHA256 = "254293c1c6ae893b87ec5c1fea3b72f696fe7821a3d87740ebad86b780d6e33a"


def file_digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def test_dictionary_files_are_present():
    assert (DATA_DIRECTORY / "kk_KZ.dic").is_file()
    assert (DATA_DIRECTORY / "kk_KZ.aff").is_file()


def test_dictionary_files_are_unmodified():
    # The data is vendored byte-for-byte so its provenance stays checkable.
    # A changed digest means the file was edited or line endings were normalized.
    assert file_digest(DATA_DIRECTORY / "kk_KZ.dic") == DICTIONARY_SHA256
    assert file_digest(DATA_DIRECTORY / "kk_KZ.aff") == AFFIX_SHA256


def test_license_file_ships_with_the_data():
    license_text = (DATA_DIRECTORY / "LICENSE-kk_KZ.txt").read_text(encoding="utf-8")
    assert "Mozilla Public License version 1.1" in license_text
    assert "taem/hunspell-kk" in license_text
```

- [ ] **Step 4: Run the test to verify it fails**

```bash
.venv/Scripts/python.exe -m pytest backend/tests/test_kazakh_dictionary_data.py -v
```

Expected: `test_license_file_ships_with_the_data` fails with `FileNotFoundError` if you have not yet written the license file; the digest tests should already pass because the data files were copied in before this plan started. If a digest test fails, the file was altered — re-download it from `https://raw.githubusercontent.com/taem/hunspell-kk/master/kk_KZ.dic` (and `.aff`) rather than editing anything.

- [ ] **Step 5: Run the test to verify it passes**

```bash
.venv/Scripts/python.exe -m pytest backend/tests/test_kazakh_dictionary_data.py -v
```

Expected: 3 passed.

- [ ] **Step 6: Verify git will store the files unchanged**

```bash
git add .gitattributes backend/app/data/
git status --porcelain backend/app/data/
```

Expected: both data files plus the license listed as added (`A`), with no warning about CRLF replacement. If git prints `LF will be replaced by CRLF` for the data files, `.gitattributes` is not taking effect — fix it before committing.

- [ ] **Step 7: Commit**

```bash
git add .gitattributes backend/app/data/ backend/tests/test_kazakh_dictionary_data.py
git commit -m "Vendor the hunspell Kazakh dictionary under MPL 1.1"
```

- [ ] **Step 8: Confirm the committed bytes still match**

```bash
git stash list && git show HEAD --stat && .venv/Scripts/python.exe -m pytest backend/tests/test_kazakh_dictionary_data.py -q
```

Expected: the commit lists three added files and the digest tests still pass against the working tree.

---

### Task 2: Lexicon service

**Files:**
- Create: `backend/app/services/kazakh_lexicon.py`
- Test: `backend/tests/test_kazakh_lexicon.py`

**Interfaces:**
- Consumes: the data files from Task 1.
- Produces: `get_lexicon() -> KazakhLexicon | None` and `KazakhLexicon.contains(word: str) -> bool`. Task 3 calls both. Also `DICTIONARY_PATH`, `AFFIX_PATH`, and `load_lexicon(dictionary_path, affix_path) -> KazakhLexicon` for tests.

- [ ] **Step 1: Write the failing tests**

Create `backend/tests/test_kazakh_lexicon.py`:

```python
from pathlib import Path

import pytest

from backend.app.services import kazakh_lexicon


@pytest.fixture(scope="module")
def lexicon():
    loaded = kazakh_lexicon.get_lexicon()
    assert loaded is not None, "bundled Kazakh dictionary failed to load"
    return loaded


def test_base_words_are_known(lexicon):
    for word in ("бала", "мектеп", "қала", "кітапхана", "жақсы"):
        assert lexicon.contains(word), word


def test_inflected_forms_are_known(lexicon):
    # These are produced by the affix file's suffix rules, not listed as stems.
    for word in ("мектептің", "картасы", "телефонын", "болатын", "Республикасы"):
        assert lexicon.contains(word), word


def test_lookup_is_case_insensitive(lexicon):
    assert lexicon.contains("ҚАЛА")
    assert lexicon.contains("Қала")


def test_non_words_are_unknown(lexicon):
    # Corrupted, over-corrected, and foreign forms must all be rejected, otherwise
    # the repair step has nothing to decide with.
    for word in ("кала", "қітапхана", "Мосқва", "абракадабра", "тагы"):
        assert not lexicon.contains(word), word


def test_missing_data_files_degrade_to_no_lexicon(tmp_path):
    # A packaging mistake must not break image adaptation.
    assert kazakh_lexicon.load_lexicon(tmp_path / "absent.dic", tmp_path / "absent.aff") is None


def test_get_lexicon_is_cached():
    assert kazakh_lexicon.get_lexicon() is kazakh_lexicon.get_lexicon()
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
.venv/Scripts/python.exe -m pytest backend/tests/test_kazakh_lexicon.py -v
```

Expected: collection error, `ModuleNotFoundError: No module named 'backend.app.services.kazakh_lexicon'`.

- [ ] **Step 3: Write the implementation**

Create `backend/app/services/kazakh_lexicon.py`:

```python
"""Lookup against the bundled hunspell Kazakh dictionary.

The dictionary files in ``backend/app/data`` are third-party data under MPL 1.1;
see ``backend/app/data/LICENSE-kk_KZ.txt``. This module is the only reader of them.

The lookup is deliberately generous. It is used to decide whether an OCR word is
already a real Kazakh word, and accepting too much only means a repair is skipped,
while accepting too little means correct text gets rewritten.
"""

import re
from pathlib import Path
from threading import Lock

DATA_DIRECTORY = Path(__file__).resolve().parents[1] / "data"
DICTIONARY_PATH = DATA_DIRECTORY / "kk_KZ.dic"
AFFIX_PATH = DATA_DIRECTORY / "kk_KZ.aff"

_LEXICON_LOCK = Lock()
_LEXICON_LOADED = False
_LEXICON: "KazakhLexicon | None" = None


class KazakhLexicon:
    """Stems plus suffix rules, answering "is this a Kazakh word?"."""

    def __init__(self, stems: dict[str, str], suffix_rules: dict[str, list]) -> None:
        self._stems = stems
        self._suffix_rules = suffix_rules
        self._suffixes = sorted(suffix_rules, key=len, reverse=True)

    @property
    def stem_count(self) -> int:
        return len(self._stems)

    def contains(self, word: str) -> bool:
        lowered = word.lower()
        if not lowered:
            return False
        if lowered in self._stems:
            return True

        for suffix in self._suffixes:
            if len(suffix) >= len(lowered) or not lowered.endswith(suffix):
                continue
            base = lowered[: -len(suffix)]
            for flag, strip, condition in self._suffix_rules[suffix]:
                stem = base + strip
                flags = self._stems.get(stem)
                if flags is not None and flag in flags and condition.search(stem):
                    return True
        return False


def load_lexicon(dictionary_path: Path, affix_path: Path) -> KazakhLexicon | None:
    try:
        stems = _parse_dictionary(dictionary_path)
        suffix_rules = _parse_affix_rules(affix_path)
    except OSError:
        return None
    except Exception:
        return None

    if not stems or not suffix_rules:
        return None
    return KazakhLexicon(stems, suffix_rules)


def get_lexicon() -> KazakhLexicon | None:
    """Return the bundled lexicon, loading it on first use.

    Returns None when the data files are missing or unreadable, so callers can
    simply make no repairs instead of failing the request.
    """
    global _LEXICON, _LEXICON_LOADED

    if _LEXICON_LOADED:
        return _LEXICON

    with _LEXICON_LOCK:
        if not _LEXICON_LOADED:
            _LEXICON = load_lexicon(DICTIONARY_PATH, AFFIX_PATH)
            _LEXICON_LOADED = True
    return _LEXICON


def _parse_dictionary(path: Path) -> dict[str, str]:
    stems: dict[str, str] = {}
    text = path.read_text(encoding="utf-8", errors="replace").lstrip("\ufeff")
    for line in text.splitlines()[1:]:
        entry = line.strip()
        if not entry:
            continue
        word, _, flags = entry.partition("/")
        word = word.strip().lower()
        if word:
            stems[word] = stems.get(word, "") + flags.strip()
    return stems


def _parse_affix_rules(path: Path) -> dict[str, list]:
    suffix_rules: dict[str, list] = {}
    text = path.read_text(encoding="utf-8", errors="replace").lstrip("\ufeff")
    for line in text.splitlines():
        parts = line.split()
        if len(parts) < 5 or parts[0] != "SFX":
            continue

        flag, strip, addition, condition = parts[1], parts[2], parts[3], parts[4]
        if addition == "0":
            continue

        stripped = "" if strip == "0" else strip
        try:
            compiled = re.compile(condition + "$")
        except re.error:
            continue
        suffix_rules.setdefault(addition, []).append((flag, stripped, compiled))
    return suffix_rules
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
.venv/Scripts/python.exe -m pytest backend/tests/test_kazakh_lexicon.py -v
```

Expected: 6 passed. The first test that touches `get_lexicon()` pays the one-time load (~1 s).

- [ ] **Step 5: Check the load cost against the design's numbers**

```bash
PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe -c "import time, tracemalloc; tracemalloc.start(); from backend.app.services.kazakh_lexicon import get_lexicon; t=time.perf_counter(); lex=get_lexicon(); print('load %.0f ms' % ((time.perf_counter()-t)*1000)); print('stems', lex.stem_count); print('heap %.1f MB' % (tracemalloc.get_traced_memory()[0]/1e6))"
```

Expected: roughly 1000 ms, 53971 stems, ~11 MB heap. If the heap is an order of magnitude larger, the flags are being stored as a `set` rather than a `str` — fix it, the design measured 119 MB that way.

- [ ] **Step 6: Run the full suite**

```bash
.venv/Scripts/python.exe -m pytest backend/tests -q
```

Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add backend/app/services/kazakh_lexicon.py backend/tests/test_kazakh_lexicon.py
git commit -m "Add Kazakh lexicon lookup over the bundled hunspell dictionary"
```

---

### Task 3: Lexicon-gated letter repair

**Files:**
- Modify: `backend/app/services/ocr_correction.py`
- Test: `backend/tests/test_ocr_correction.py`

**Interfaces:**
- Consumes: `get_lexicon()` and `KazakhLexicon.contains` from Task 2.
- Produces: no new public API. `correct_ocr_text(text, *, language_hint="kk")` keeps its signature; the repair is internal.

- [ ] **Step 1: Write the failing tests**

Append to `backend/tests/test_ocr_correction.py`:

```python
def test_flattened_letters_are_restored_from_the_lexicon():
    assert correct_ocr_text("кала", language_hint="kk") == "қала"
    assert correct_ocr_text("тагы", language_hint="kk") == "тағы"


def test_restoration_keeps_original_capitalization():
    assert correct_ocr_text("Кала", language_hint="kk") == "Қала"


def test_correct_words_are_never_rewritten():
    # Every one of these was damaged by the rule-based attempt this design replaces.
    unchanged = (
        "кітапхана",
        "болатын",
        "оқитын",
        "телефонын",
        "орнын",
        "кітабын",
        "заводын",
        "картасы",
        "музыкалық",
        "Республикасы",
        "Москва",
    )
    for word in unchanged:
        assert correct_ocr_text(word, language_hint="kk") == word, word


def test_short_words_are_left_alone():
    # Without the length guard this becomes "қм".
    assert correct_ocr_text("км", language_hint="kk") == "км"


def test_word_final_n_is_never_restored():
    # -ын/-ін possessive-accusative forms are under-represented in the dictionary,
    # so a final н→ң finds a spurious unique match.
    assert correct_ocr_text("стадионын", language_hint="kk") == "стадионын"
    assert correct_ocr_text("жанын", language_hint="kk") == "жанын"


def test_unknown_word_with_no_lexicon_candidate_is_left_alone():
    assert correct_ocr_text("абракадабра", language_hint="kk") == "абракадабра"


def test_russian_document_is_not_repaired():
    source = "Книга лежит на столе. Город большой."
    assert correct_ocr_text(source, language_hint="ru") == source


def test_lexicon_repair_is_idempotent():
    once = correct_ocr_text("кала тагы", language_hint="kk")
    assert correct_ocr_text(once, language_hint="kk") == once


def test_repair_is_skipped_when_the_lexicon_is_unavailable(monkeypatch):
    monkeypatch.setattr(ocr_correction, "get_lexicon", lambda: None)
    assert correct_ocr_text("кала", language_hint="kk") == "кала"
```

Add these imports at the top of the test file, next to the existing import:

```python
from backend.app.services import ocr_correction
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
.venv/Scripts/python.exe -m pytest backend/tests/test_ocr_correction.py -v
```

Expected: `test_flattened_letters_are_restored_from_the_lexicon` fails with `assert 'кала' == 'қала'`. The `test_correct_words_are_never_rewritten` test passes already — it is a regression guard, and it must still pass after Step 3.

- [ ] **Step 3: Write the implementation**

In `backend/app/services/ocr_correction.py`, add to the imports:

```python
import itertools

from .kazakh_lexicon import get_lexicon
```

Add the constants next to the existing Kazakh constants:

```python
# OCR flattens Kazakh letters onto their Russian lookalikes; these are the
# restorations to try. The dictionary decides which, if any, is correct.
KAZAKH_RESTORATIONS = {
    "к": "қ",
    "г": "ғ",
    "а": "ә",
    "о": "ө",
    "у": "ұү",
    "и": "і",
    "н": "ң",
    "х": "һ",
}
MIN_LEXICON_REPAIR_LENGTH = 4
MAX_AMBIGUOUS_POSITIONS = 6
MAX_CANDIDATES = 256
```

Add the repair function:

```python
def _repair_with_lexicon(word: str) -> str:
    lexicon = get_lexicon()
    if lexicon is None:
        return word

    lowered = word.lower()
    if len(lowered) < MIN_LEXICON_REPAIR_LENGTH or not lowered.isalpha():
        return word
    if lexicon.contains(lowered):
        return word

    positions = _ambiguous_positions(lowered)
    if not positions or len(positions) > MAX_AMBIGUOUS_POSITIONS:
        return word

    choices = [
        [None] + [(index, letter) for letter in KAZAKH_RESTORATIONS[lowered[index]]]
        for index in positions
    ]

    match: str | None = None
    evaluated = 0
    for combination in itertools.product(*choices):
        evaluated += 1
        if evaluated > MAX_CANDIDATES:
            return word

        characters = list(lowered)
        for replacement in combination:
            if replacement is not None:
                characters[replacement[0]] = replacement[1]

        candidate = "".join(characters)
        if candidate == lowered or not lexicon.contains(candidate):
            continue
        if match is not None and candidate != match:
            # Two readings are both real words; the dictionary cannot settle it.
            return word
        match = candidate

    if match is None:
        return word
    return _apply_original_case(word, match)


def _ambiguous_positions(word: str) -> list[int]:
    last_index = len(word) - 1
    return [
        index
        for index, character in enumerate(word)
        if character in KAZAKH_RESTORATIONS
        # A word-final н is usually the -ын/-ін possessive-accusative, whose forms
        # are under-represented in the dictionary, so restoring ң there finds a
        # spurious unique match and rewrites correct text.
        and not (character == "н" and index == last_index)
    ]


def _apply_original_case(original: str, repaired: str) -> str:
    if original.isupper():
        return repaired.upper()
    if original[:1].isupper():
        return repaired[:1].upper() + repaired[1:]
    return repaired
```

Call it from `_repair_kazakh`, after the existing word-initial `ң` fix and `һ` table, as the last step. The existing early returns for those two rules stay as they are.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
.venv/Scripts/python.exe -m pytest backend/tests/test_ocr_correction.py -v
```

Expected: all pass, including the pre-existing tests.

- [ ] **Step 5: Verify latency on a realistic page**

```bash
PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe -c "import sys, time; sys.path.insert(0,'.'); from backend.app.services.ocr_correction import correct_ocr_text; from scripts.ocr_eval_corpus import CORPUS; from scripts.ocr_eval import corrupt; page=' '.join(corrupt(item['text'], seed=1000+index) for index, item in enumerate(CORPUS) for _ in range(12)); print('words', len(page.split())); correct_ocr_text('warm up', language_hint='kk'); t=time.perf_counter(); correct_ocr_text(page, language_hint='kk'); print('%.0f ms' % ((time.perf_counter()-t)*1000))"
```

Expected: roughly 2,500 words processed in well under 2000 ms (the load is warmed separately). If it is far slower, lower `MAX_AMBIGUOUS_POSITIONS` and report the change.

- [ ] **Step 6: Run the full suite**

```bash
.venv/Scripts/python.exe -m pytest backend/tests -q
```

Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add backend/app/services/ocr_correction.py backend/tests/test_ocr_correction.py
git commit -m "Restore Kazakh letters using the lexicon as the decision procedure"
```

---

### Task 4: Measure the recovery and gate it

**Files:**
- Modify: `scripts/ocr_eval.py`
- Modify: `backend/tests/test_ocr_eval_gate.py`
- Modify: `scripts/ocr_eval_baseline.json` (regenerated)

**Interfaces:**
- Consumes: `correct_ocr_text` with the Task 3 repair.
- Produces: a `kazakh_words_restored` figure in the `evaluate()` result, read by the gate.

- [ ] **Step 1: Write the failing test**

In `backend/tests/test_ocr_eval_gate.py`, add:

```python
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
```

- [ ] **Step 2: Run it to verify it fails**

```bash
.venv/Scripts/python.exe -m pytest backend/tests/test_ocr_eval_gate.py -v
```

Expected: `KeyError: 'kazakh_words_restored'`.

- [ ] **Step 3: Report word-level recovery from the harness**

`_evaluate_channels` (`scripts/ocr_eval.py:186`) already produces `kazakh_drop_raw` and `kazakh_drop_corrected` for every corpus row. Count word recovery inside that same loop rather than corrupting the text a third time.

Add at module level, near the other regex constants:

```python
WORD_PATTERN = re.compile(r"[^\W_]+", re.UNICODE)
```

Add a helper:

```python
def _count_word_recovery(reference: str, raw: str, corrected: str) -> tuple[int, int]:
    """Words this channel corrupted, and how many came back exactly right."""
    reference_words = WORD_PATTERN.findall(reference)
    raw_words = WORD_PATTERN.findall(raw)
    corrected_words = WORD_PATTERN.findall(corrected)
    if not (len(reference_words) == len(raw_words) == len(corrected_words)):
        # Tokenization drifted; skip rather than mis-align the comparison.
        return 0, 0

    corrupted = 0
    restored = 0
    for expected, scanned, repaired in zip(reference_words, raw_words, corrected_words):
        if scanned.lower() == expected.lower():
            continue
        corrupted += 1
        if repaired.lower() == expected.lower():
            restored += 1
    return corrupted, restored
```

In `_evaluate_channels`, initialize two counters before the loop:

```python
    kazakh_words_corrupted = 0
    kazakh_words_restored = 0
```

Inside the loop, directly after `kazakh_drop_corrected` is computed, accumulate them for Kazakh rows only:

```python
        if item["language_hint"] == "kk":
            corrupted, restored = _count_word_recovery(
                reference, kazakh_drop_raw, kazakh_drop_corrected
            )
            kazakh_words_corrupted += corrupted
            kazakh_words_restored += restored
```

Add both to the dict `_evaluate_channels` returns, and to its non-synthetic early-return branch as `0`:

```python
        "kazakh_words_corrupted": kazakh_words_corrupted,
        "kazakh_words_restored": kazakh_words_restored,
```

`evaluate()` already merges the channel dict via `**channels` (`scripts/ocr_eval.py:180`), so both keys reach the report with no further change.

And print them in `print_report`:

```python
    print(
        f"Kazakh words restored: {report['kazakh_words_restored']}"
        f"/{report['kazakh_words_corrupted']}"
    )
```

- [ ] **Step 4: Run the harness and read the real numbers**

```bash
PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe scripts/ocr_eval.py
```

Expected: `kazakh_words_restored` around 25 of 55 (~45%), the clean-input no-op count still exactly 0, and the per-letter survival table now showing recovery for `қ ғ ә ө і ң ұ ү` rather than only the homoglyph letters.

Record the actual numbers. If the no-op count is anything but 0, stop and report — a repair is damaging clean text and no threshold change is acceptable.

- [ ] **Step 5: Run the gate**

```bash
.venv/Scripts/python.exe -m pytest backend/tests/test_ocr_eval_gate.py -v
```

Expected: all pass, including the new recovery assertion at 0.35 against a measured ~0.45.

If the measured recovery is below 0.35, do NOT lower the threshold — report the number and stop. The design measured 45% on this corpus, so a large shortfall means the repair is not wired up as specified.

- [ ] **Step 6: Regenerate the baseline and run the full suite**

```bash
PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe scripts/ocr_eval.py --save-baseline
.venv/Scripts/python.exe -m pytest backend/tests -q
```

Expected: baseline rewritten, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add scripts/ocr_eval.py scripts/ocr_eval_baseline.json backend/tests/test_ocr_eval_gate.py
git commit -m "Measure and gate Kazakh word recovery on the letter-drop channel"
```

---

### Task 5: Documentation

**Files:**
- Modify: `README.md`

**Interfaces:** none.

- [ ] **Step 1: Describe what the layer now does**

In `README.md`, the paragraph describing `SULU_READ_OCR_CORRECTION` currently lists only homoglyph folding, the word-initial `ң` fix, and the `һ` table. Add the lexicon repair to that description: Kazakh-specific letters flattened by OCR are restored when the bundled Kazakh dictionary confirms exactly one reading is a real word, and a word already in the dictionary is never altered.

- [ ] **Step 2: Add the attribution**

Add a short subsection under `## Environment` (or a new `## Third-Party Data` section, whichever reads better in context):

```markdown
## Third-Party Data

`backend/app/data/kk_KZ.dic` and `kk_KZ.aff` are the hunspell Kazakh spelling
dictionary from [taem/hunspell-kk](https://github.com/taem/hunspell-kk), derived from
aspell-kk_KZ 0.60 by Alexey Lipchansky, with the affix file by Akmaral Mussayeva.
Upstream is tri-licensed GPL-2.0-or-later / LGPL-2.1-or-later / MPL-1.1-or-later;
this project elects MPL 1.1. See `backend/app/data/LICENSE-kk_KZ.txt`. The files are
vendored byte-for-byte and are not modified.
```

- [ ] **Step 3: Verify the README claims match the code**

Re-read the paragraph you edited against `backend/app/services/ocr_correction.py`. Every behavior named must exist, and no behavior described may overstate what the code does — in particular, do not claim the genitive `-ның` is recovered, because word-final `н→ң` is deliberately excluded.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "Document the Kazakh lexicon repair and its dictionary attribution"
```

---

## Final Verification

- [ ] `.venv/Scripts/python.exe -m pytest backend/tests` — all green
- [ ] `PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe scripts/ocr_eval.py` — clean-input no-op count exactly 0, Kazakh words restored ~45%
- [ ] `git diff --stat main..HEAD -- requirements.txt` prints nothing
- [ ] `.venv/Scripts/python.exe -m pytest backend/tests/test_kazakh_dictionary_data.py` — vendored bytes unchanged after every commit
- [ ] `SULU_READ_OCR_CORRECTION=false` still reproduces uncorrected output
