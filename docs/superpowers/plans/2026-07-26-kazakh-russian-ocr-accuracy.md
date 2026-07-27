# Kazakh/Russian OCR Accuracy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recover Kazakh-specific letters and remove Latin/Cyrillic homoglyph contamination from `/v1/adapt-image` OCR output, with a measured regression gate proving it works.

**Architecture:** A pure-Python post-OCR correction layer (`backend/app/services/ocr_correction.py`) runs on raw engine output before the existing text cleanup. Tier 1 fixes script/homoglyph contamination for any language; Tier 2 restores Kazakh letters using vowel-harmony rules, which are deterministic because `қ ғ` occur only in back-vowel words and `к г` only in front-vowel words. A synthetic eval harness (`scripts/ocr_eval.py`) renders a known corpus, corrupts it with a deterministic model of the observed OCR error classes, and reports CER/WER so the improvement is measured rather than asserted.

**Tech Stack:** Python 3.11+, FastAPI, pytest, EasyOCR, Groq vision API, Pillow (dev-only, already present transitively via easyocr).

## Global Constraints

- No new entry in `requirements.txt` for the production path. The correction module imports only `re` from the standard library.
- `correct_ocr_text` is pure: no I/O, no network, no environment reads, no logging.
- Correction is conservative: when no rule fires with confidence, the word passes through unchanged. A rule may never guess.
- Correction must be idempotent: `correct_ocr_text(correct_ocr_text(x)) == correct_ocr_text(x)`.
- Kill switch `SULU_READ_OCR_CORRECTION` (default `true`); when `false`, output is byte-identical to today.
- Kazakh back-vowel (жуан) markers: `а о ұ ы` plus consonants `қ ғ`. Front-vowel (жіңішке) markers: `ә ө ү е і`. `и`, `у`, `к`, `г` carry no class evidence.
- Never tune the eval noise model to make the gate pass. If the target is missed, extend the correction rules or report the shortfall.
- Existing tests must stay green: `python -m pytest backend/tests`.
- Spec: `docs/superpowers/specs/2026-07-26-kazakh-russian-ocr-accuracy-design.md`.

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `backend/app/services/ocr_correction.py` | create | All correction rules + public `correct_ocr_text` entry point |
| `backend/tests/test_ocr_correction.py` | create | Unit tests, one per rule |
| `scripts/ocr_eval_corpus.py` | create | 40-snippet ground-truth corpus, data only |
| `scripts/ocr_eval.py` | create | Noise model, metrics, CLI, report writer |
| `scripts/ocr_eval_baseline.json` | create | Recorded pre-correction metrics |
| `backend/tests/test_ocr_eval_gate.py` | create | Offline regression gate over the corpus |
| `main.py` | modify | EasyOCR allowlist, Groq prompt, wire-in, `/health` field, dead-code removal |
| `.gitignore` | modify | Ignore eval result dumps |
| `README.md` | modify | Document the env flag and eval command |

---

### Task 1: Correction module — Tier 1 script hygiene

**Files:**
- Create: `backend/app/services/ocr_correction.py`
- Test: `backend/tests/test_ocr_correction.py`

**Interfaces:**
- Consumes: nothing.
- Produces: `correct_ocr_text(text: str, *, language_hint: str = "kk") -> str`. Also the module-level constants `WORD_PATTERN`, `CYRILLIC_LETTERS`, `LATIN_LETTERS`, `KAZAKH_SPECIFIC_LETTERS`, used by Task 2.

- [ ] **Step 1: Write the failing tests**

Create `backend/tests/test_ocr_correction.py`:

```python
from backend.app.services.ocr_correction import correct_ocr_text


def test_latin_homoglyphs_folded_into_cyrillic_majority_word():
    # "мekтeп" carries Latin e/k inside a Cyrillic-majority word.
    assert correct_ocr_text("мekтeп") == "мектеп"


def test_cyrillic_homoglyphs_folded_into_latin_majority_word():
    # s h o l are Latin (4), с and о are Cyrillic (2): Latin majority wins.
    contaminated = "sсhoоl"
    assert correct_ocr_text(contaminated) == "school"


def test_word_without_script_majority_is_left_alone():
    # Two Cyrillic (с, р), two Latin (o, a): no majority, so nothing is guessed.
    tie_word = "сoрa"
    assert correct_ocr_text(tie_word) == tie_word


def test_pure_cyrillic_word_is_unchanged():
    assert correct_ocr_text("мектеп") == "мектеп"


def test_pure_latin_word_is_unchanged():
    assert correct_ocr_text("school") == "school"


def test_digits_inside_a_cyrillic_word_are_folded():
    # language_hint="ru" keeps Task 2's Kazakh repair out of this assertion.
    assert correct_ocr_text("б0лім", language_hint="ru") == "болім"
    assert correct_ocr_text("ка3ак", language_hint="ru") == "казак"


def test_standalone_numbers_are_not_touched():
    assert correct_ocr_text("1991 жыл") == "1991 жыл"
    assert correct_ocr_text("Сабақ 5") == "Сабақ 5"


def test_punctuation_and_whitespace_are_preserved():
    source = "Бүгін — жақсы күн.\nЕртең де жақсы!\n"
    assert correct_ocr_text(source) == source


def test_empty_and_blank_input_pass_through():
    assert correct_ocr_text("") == ""
    assert correct_ocr_text("   \n  ") == "   \n  "


def test_correction_is_idempotent():
    source = "мekтeп б0лім school"
    once = correct_ocr_text(source)
    assert correct_ocr_text(once) == once
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
python -m pytest backend/tests/test_ocr_correction.py -v
```

Expected: collection error — `ModuleNotFoundError: No module named 'backend.app.services.ocr_correction'`.

- [ ] **Step 3: Write the Tier 1 implementation**

Create `backend/app/services/ocr_correction.py`:

```python
"""Post-OCR correction for Kazakh and Russian textbook text.

Pure functions only: no I/O, no network, no environment reads. The caller
decides whether to apply correction; this module only transforms strings.
"""

import re

CYRILLIC_LETTERS = set(
    "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
    "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
    "әғқңөұүһіӘҒҚҢӨҰҮҺІ"
)
LATIN_LETTERS = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
KAZAKH_SPECIFIC_LETTERS = set("әғқңөұүһіӘҒҚҢӨҰҮҺІ")

# Runs of letters and digits. Everything else (spaces, punctuation, newlines)
# is preserved untouched by re.sub.
WORD_PATTERN = re.compile(r"[^\W_]+", re.UNICODE)

LATIN_TO_CYRILLIC = {
    "a": "а",
    "c": "с",
    "e": "е",
    "k": "к",
    "m": "м",
    "o": "о",
    "p": "р",
    "t": "т",
    "x": "х",
    "y": "у",
    "A": "А",
    "B": "В",
    "C": "С",
    "E": "Е",
    "H": "Н",
    "K": "К",
    "M": "М",
    "O": "О",
    "P": "Р",
    "T": "Т",
    "X": "Х",
    "Y": "У",
}
CYRILLIC_TO_LATIN = {value: key for key, value in LATIN_TO_CYRILLIC.items()}

DIGIT_TO_CYRILLIC = {
    "0": "о",
    "3": "з",
    "6": "б",
}


def correct_ocr_text(text: str, *, language_hint: str = "kk") -> str:
    """Return `text` with OCR letter confusions repaired.

    Never raises for string input. Unrecognized patterns pass through.
    """
    if not text or not text.strip():
        return text

    return WORD_PATTERN.sub(lambda match: _correct_word(match.group(0)), text)


def _correct_word(word: str) -> str:
    corrected = _fold_homoglyphs(word)
    corrected = _fold_digits(corrected)
    return corrected


def _fold_homoglyphs(word: str) -> str:
    cyrillic_count = sum(1 for character in word if character in CYRILLIC_LETTERS)
    latin_count = sum(1 for character in word if character in LATIN_LETTERS)
    if not cyrillic_count or not latin_count:
        return word

    if cyrillic_count > latin_count:
        return "".join(LATIN_TO_CYRILLIC.get(character, character) for character in word)
    if latin_count > cyrillic_count:
        return "".join(CYRILLIC_TO_LATIN.get(character, character) for character in word)
    return word


def _fold_digits(word: str) -> str:
    if not any(character in CYRILLIC_LETTERS for character in word):
        return word

    characters = list(word)
    for index in range(1, len(characters) - 1):
        character = characters[index]
        if character not in DIGIT_TO_CYRILLIC:
            continue
        if characters[index - 1].isalpha() and characters[index + 1].isalpha():
            characters[index] = DIGIT_TO_CYRILLIC[character]
    return "".join(characters)
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
python -m pytest backend/tests/test_ocr_correction.py -v
```

Expected: 10 passed.

- [ ] **Step 5: Commit**

```bash
git add backend/app/services/ocr_correction.py backend/tests/test_ocr_correction.py
git commit -m "Add post-OCR script hygiene correction layer"
```

---

### Task 2: Correction module — Tier 2 Kazakh harmony repair

**Files:**
- Modify: `backend/app/services/ocr_correction.py`
- Test: `backend/tests/test_ocr_correction.py`

**Interfaces:**
- Consumes: `correct_ocr_text`, `WORD_PATTERN`, `CYRILLIC_LETTERS`, `KAZAKH_SPECIFIC_LETTERS` from Task 1.
- Produces: the same `correct_ocr_text(text, *, language_hint="kk")` signature, now applying Kazakh repair when the document gate opens. Also `KK_EVIDENCE_MIN_RATIO: float` and `LOANWORD_EXCEPTIONS: frozenset[str]`, referenced by the eval harness report in Task 3.

- [ ] **Step 1: Write the failing tests**

Append to `backend/tests/test_ocr_correction.py`:

```python
def test_back_vowel_word_restores_qaf():
    # "кала" in a Kazakh document: а/а are back markers, so к must be қ.
    assert correct_ocr_text("кала", language_hint="kk") == "қала"


def test_back_vowel_word_restores_ghayn():
    assert correct_ocr_text("тагы", language_hint="kk") == "тағы"


def test_front_vowel_word_keeps_plain_k():
    assert correct_ocr_text("мектеп", language_hint="kk") == "мектеп"


def test_front_vowel_word_restores_ae_and_oe():
    # "олке" holds one back marker (о) and one front marker (е): a tie,
    # so nothing is changed rather than guessed.
    assert correct_ocr_text("олке", language_hint="kk") == "олке"
    # "олкелер" is front-dominant (е, е, е) so о becomes ө.
    assert correct_ocr_text("олкелер", language_hint="kk") == "өлкелер"


def test_mixed_evidence_word_is_left_alone():
    # "кітап" genuinely mixes classes (і front, а back). Must not be touched.
    assert correct_ocr_text("кітап", language_hint="kk") == "кітап"


def test_loanword_exception_is_not_kazakhized():
    assert correct_ocr_text("класс", language_hint="kk") == "класс"
    assert correct_ocr_text("кино", language_hint="kk") == "кино"
    assert correct_ocr_text("космос", language_hint="kk") == "космос"


def test_word_with_russian_only_letter_is_skipped():
    # ь never appears in a native Kazakh stem, so this is a loanword.
    assert correct_ocr_text("компьютер", language_hint="kk") == "компьютер"


def test_word_initial_eng_is_corrected():
    assert correct_ocr_text("ңан", language_hint="kk") == "нан"


def test_back_genitive_suffix_restores_eng():
    assert correct_ocr_text("баланын", language_hint="kk") == "баланың"


def test_front_genitive_suffix_restores_eng_and_i():
    assert correct_ocr_text("мектептин", language_hint="kk") == "мектептің"


def test_h_loanword_is_restored():
    assert correct_ocr_text("гаухар", language_hint="kk") == "гауһар"


def test_russian_document_is_not_kazakhized():
    source = "Мы идём в школу каждый день. Дети играют во дворе."
    assert correct_ocr_text(source, language_hint="ru") == source


def test_kazakh_document_detected_without_hint():
    # Kazakh-specific letters in the document open the gate even when the
    # client sent the wrong hint.
    source = "Бүгін кала кітапханасы ашық."
    assert "қала" in correct_ocr_text(source, language_hint="ru")


def test_russian_document_with_no_kazakh_evidence_stays_closed():
    source = "Ученики читают книгу в классе."
    assert correct_ocr_text(source, language_hint="en") == source


def test_kazakh_repair_is_idempotent():
    source = "кала тагы баланын мектептин"
    once = correct_ocr_text(source, language_hint="kk")
    assert correct_ocr_text(once, language_hint="kk") == once


def test_tier_one_still_applies_inside_kazakh_documents():
    assert correct_ocr_text("мekтeп қала", language_hint="kk") == "мектеп қала"
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
python -m pytest backend/tests/test_ocr_correction.py -v
```

Expected: the 16 new tests fail (for example `assert 'кала' == 'қала'`); the 10 Task 1 tests still pass.

- [ ] **Step 3: Write the Tier 2 implementation**

Add to `backend/app/services/ocr_correction.py`, below the Task 1 constants:

```python
RUSSIAN_ONLY_SIGNALS = set("ёщъьэцчЁЩЪЬЭЦЧ")

BACK_MARKERS = set("аоұыАОҰЫқғҚҒ")
FRONT_MARKERS = set("әөүеіӘӨҮЕІ")

TO_BACK = {
    "к": "қ",
    "г": "ғ",
    "ә": "а",
    "ө": "о",
    "ү": "ұ",
    "К": "Қ",
    "Г": "Ғ",
    "Ә": "А",
    "Ө": "О",
    "Ү": "Ұ",
}
TO_FRONT = {
    "қ": "к",
    "ғ": "г",
    "а": "ә",
    "о": "ө",
    "ұ": "ү",
    "Қ": "К",
    "Ғ": "Г",
    "А": "Ә",
    "О": "Ө",
    "Ұ": "Ү",
}

# Genitive/possessive endings whose ң (and і) are routinely flattened by OCR.
BACK_SUFFIX_REPAIRS = (
    ("нын", "ның"),
    ("дын", "дың"),
    ("тын", "тың"),
)
FRONT_SUFFIX_REPAIRS = (
    ("нин", "нің"),
    ("дин", "дің"),
    ("тин", "тің"),
)
MIN_SUFFIX_REPAIR_LENGTH = 5

# Kazakh suffixes used as evidence that a document is Kazakh.
KAZAKH_SUFFIX_EVIDENCE = (
    "ның",
    "нің",
    "дың",
    "дің",
    "тың",
    "тің",
    "ларға",
    "лерге",
    "дарға",
    "дерге",
    "тарға",
    "терге",
    "ымыз",
    "іміз",
)

# Words that legitimately break vowel harmony: Russian and international
# loanwords in common school vocabulary. Lowercase, no suffixes.
LOANWORD_EXCEPTIONS = frozenset(
    {
        "автобус",
        "аптека",
        "банк",
        "велосипед",
        "газ",
        "газет",
        "газета",
        "гектар",
        "географ",
        "география",
        "геометрия",
        "гимн",
        "глобус",
        "грамм",
        "грамматика",
        "группа",
        "директор",
        "доктор",
        "журнал",
        "институт",
        "информатика",
        "калькулятор",
        "карта",
        "картина",
        "касса",
        "кино",
        "километр",
        "класс",
        "клуб",
        "код",
        "команда",
        "компас",
        "компьютер",
        "конверт",
        "конкурс",
        "концерт",
        "космос",
        "лагерь",
        "лампа",
        "литр",
        "магазин",
        "математика",
        "машина",
        "метр",
        "микрофон",
        "минут",
        "музыка",
        "музей",
        "оператор",
        "парк",
        "план",
        "планета",
        "поезд",
        "программа",
        "радио",
        "ракета",
        "секунд",
        "спорт",
        "стадион",
        "телефон",
        "тонна",
        "трактор",
        "троллейбус",
        "фабрика",
        "физика",
        "фильм",
        "футбол",
        "химия",
        "цифра",
        "экран",
    }
)

# Small closed set of Arabic/Persian loans where х is a misread һ.
H_LOANWORD_REPAIRS = {
    "гаухар": "гауһар",
    "жихаз": "жиһаз",
    "кахарман": "қаһарман",
    "шахар": "шаһар",
    "жахан": "жаһан",
}

KK_EVIDENCE_MIN_RATIO = 0.05
```

Replace `correct_ocr_text` and `_correct_word` with:

```python
def correct_ocr_text(text: str, *, language_hint: str = "kk") -> str:
    """Return `text` with OCR letter confusions repaired.

    Never raises for string input. Unrecognized patterns pass through.
    """
    if not text or not text.strip():
        return text

    kazakh_document = _is_kazakh_document(text, language_hint)
    return WORD_PATTERN.sub(
        lambda match: _correct_word(match.group(0), kazakh=kazakh_document),
        text,
    )


def _correct_word(word: str, *, kazakh: bool = False) -> str:
    corrected = _fold_homoglyphs(word)
    corrected = _fold_digits(corrected)
    if kazakh:
        corrected = _repair_kazakh(corrected)
    return corrected


def _is_kazakh_document(text: str, language_hint: str) -> bool:
    if (language_hint or "").strip().lower() == "kk":
        return True

    words = WORD_PATTERN.findall(text)
    if not words:
        return False

    evidence = sum(1 for word in words if _has_kazakh_evidence(word))
    return evidence / len(words) >= KK_EVIDENCE_MIN_RATIO


def _has_kazakh_evidence(word: str) -> bool:
    if any(character in KAZAKH_SPECIFIC_LETTERS for character in word):
        return True
    lowered = word.lower()
    return any(lowered.endswith(suffix) for suffix in KAZAKH_SUFFIX_EVIDENCE)


def _repair_kazakh(word: str) -> str:
    lowered = word.lower()
    if lowered in LOANWORD_EXCEPTIONS:
        return word
    if any(character in RUSSIAN_ONLY_SIGNALS for character in word):
        return word

    repaired = _repair_h_loanword(word)
    if repaired != word:
        return repaired

    repaired = _fix_initial_eng(word)

    back_count = sum(1 for character in repaired if character in BACK_MARKERS)
    front_count = sum(1 for character in repaired if character in FRONT_MARKERS)
    if back_count == front_count:
        return repaired

    if back_count > front_count:
        repaired = "".join(TO_BACK.get(character, character) for character in repaired)
        return _repair_suffix(repaired, BACK_SUFFIX_REPAIRS)

    repaired = "".join(TO_FRONT.get(character, character) for character in repaired)
    return _repair_suffix(repaired, FRONT_SUFFIX_REPAIRS)


def _repair_h_loanword(word: str) -> str:
    replacement = H_LOANWORD_REPAIRS.get(word.lower())
    if replacement is None:
        return word
    if word[:1].isupper():
        return replacement[:1].upper() + replacement[1:]
    return replacement


def _fix_initial_eng(word: str) -> str:
    if word.startswith("ң"):
        return "н" + word[1:]
    if word.startswith("Ң"):
        return "Н" + word[1:]
    return word


def _repair_suffix(word: str, repairs: tuple[tuple[str, str], ...]) -> str:
    if len(word) < MIN_SUFFIX_REPAIR_LENGTH:
        return word

    lowered = word.lower()
    for wrong, right in repairs:
        if lowered.endswith(wrong):
            return word[: -len(wrong)] + right
    return word
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
python -m pytest backend/tests/test_ocr_correction.py -v
```

Expected: 26 passed. If `test_back_genitive_suffix_restores_eng` fails, check ordering — harmony substitution runs before `_repair_suffix`, so `баланын` is already class-back when the suffix rule sees it.

- [ ] **Step 5: Verify nothing else broke**

```bash
python -m pytest backend/tests -q
```

Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add backend/app/services/ocr_correction.py backend/tests/test_ocr_correction.py
git commit -m "Add Kazakh vowel-harmony repair to OCR correction"
```

---

### Task 3: Synthetic eval corpus and harness

**Files:**
- Create: `scripts/ocr_eval_corpus.py`
- Create: `scripts/ocr_eval.py`
- Create: `scripts/ocr_eval_baseline.json` (generated in Step 5)
- Modify: `.gitignore`

**Interfaces:**
- Consumes: `correct_ocr_text(text, *, language_hint)` from Task 2.
- Produces: `CORPUS: list[dict]` with keys `id`, `language_hint`, `text`; and from `ocr_eval.py`: `corrupt(text, seed) -> str`, `character_error_rate(reference, hypothesis) -> float`, `word_error_rate(reference, hypothesis) -> float`, `evaluate() -> dict`. Task 4 imports `evaluate`, `CORPUS`.

- [ ] **Step 1: Create the corpus**

Create `scripts/ocr_eval_corpus.py`:

```python
"""Ground-truth snippets for OCR accuracy evaluation.

Short school-textbook style sentences written for this repository. Each entry
is its own ground truth; nothing here is copied from a published textbook.
"""

CORPUS = [
    {"id": "kk-01", "language_hint": "kk", "text": "Мен мектепке барамын."},
    {"id": "kk-02", "language_hint": "kk", "text": "Қазақстан — біздің Отанымыз."},
    {"id": "kk-03", "language_hint": "kk", "text": "Бүгін ауа райы жақсы."},
    {"id": "kk-04", "language_hint": "kk", "text": "Оқушылар кітап оқиды."},
    {"id": "kk-05", "language_hint": "kk", "text": "Ана тілі — халықтың жаны."},
    {"id": "kk-06", "language_hint": "kk", "text": "Біздің сыныпта отыз оқушы бар."},
    {"id": "kk-07", "language_hint": "kk", "text": "Мұғалім тақтаға жазды."},
    {"id": "kk-08", "language_hint": "kk", "text": "Күн шығып, таң атты."},
    {"id": "kk-09", "language_hint": "kk", "text": "Ағаштың жапырақтары сарғайды."},
    {"id": "kk-10", "language_hint": "kk", "text": "Балалар аулада ойнап жүр."},
    {"id": "kk-11", "language_hint": "kk", "text": "Әжем маған ертегі айтты."},
    {"id": "kk-12", "language_hint": "kk", "text": "Өзен жағасында құстар ұшады."},
    {"id": "kk-13", "language_hint": "kk", "text": "Ұлы дала — байлығымыз."},
    {"id": "kk-14", "language_hint": "kk", "text": "Түлкі қуып, қоян қашты."},
    {"id": "kk-15", "language_hint": "kk", "text": "Жаңбыр жауып, жер көгерді."},
    {"id": "kk-16", "language_hint": "kk", "text": "Дәптерге тапсырманы жаздым."},
    {"id": "kk-17", "language_hint": "kk", "text": "Қыстың күні қысқа болады."},
    {"id": "kk-18", "language_hint": "kk", "text": "Үйде әкем кітап оқып отыр."},
    {"id": "kk-19", "language_hint": "kk", "text": "Астана — еліміздің астанасы."},
    {"id": "kk-20", "language_hint": "kk", "text": "Алматы қаласында тау бар."},
    {"id": "ru-01", "language_hint": "ru", "text": "Мы идём в школу каждый день."},
    {"id": "ru-02", "language_hint": "ru", "text": "Ученики читают новую книгу."},
    {"id": "ru-03", "language_hint": "ru", "text": "Учитель написал задание на доске."},
    {"id": "ru-04", "language_hint": "ru", "text": "Сегодня хорошая погода."},
    {"id": "ru-05", "language_hint": "ru", "text": "В нашем классе тридцать учеников."},
    {"id": "ru-06", "language_hint": "ru", "text": "Осенью листья становятся жёлтыми."},
    {"id": "ru-07", "language_hint": "ru", "text": "Дети играют во дворе."},
    {"id": "ru-08", "language_hint": "ru", "text": "Бабушка рассказала мне сказку."},
    {"id": "ru-09", "language_hint": "ru", "text": "Река течёт через город."},
    {"id": "ru-10", "language_hint": "ru", "text": "Зимой дни становятся короче."},
    {"id": "mx-01", "language_hint": "kk", "text": "Қазақстан Республикасы, 1991 жыл."},
    {"id": "mx-02", "language_hint": "kk", "text": "Сабақ 5. Менің отбасым."},
    {"id": "mx-03", "language_hint": "kk", "text": "§ 12. Табиғат және адам."},
    {"id": "mx-04", "language_hint": "kk", "text": "Мұғалім: «Кітапты ашыңдар», — деді."},
    {"id": "mx-05", "language_hint": "kk", "text": "Алматы — Astana аралығы 1200 км."},
    {"id": "mx-06", "language_hint": "kk", "text": "Оқулық: Ана тілі, 3-сынып."},
    {"id": "mx-07", "language_hint": "ru", "text": "Домашнее задание: упражнение 7."},
    {"id": "mx-08", "language_hint": "kk", "text": "Тест: A, B, C, D нұсқалары."},
    {"id": "mx-09", "language_hint": "kk", "text": "Жаттығу 15. Сөздерді буынға бөл."},
    {"id": "mx-10", "language_hint": "kk", "text": "Физика пәні бойынша сабақ кестесі."},
]
```

- [ ] **Step 2: Create the harness**

Create `scripts/ocr_eval.py`:

```python
"""Synthetic OCR accuracy evaluation for Kazakh/Russian text.

Development and CI tool. Not imported by the FastAPI application.

Usage:
    python scripts/ocr_eval.py
    python scripts/ocr_eval.py --save-baseline
    python scripts/ocr_eval.py --engine easyocr      # requires rendered images
"""

import argparse
import json
import random
import sys
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from backend.app.services.ocr_correction import correct_ocr_text  # noqa: E402
from scripts.ocr_eval_corpus import CORPUS  # noqa: E402

KAZAKH_TO_LOOKALIKE_BASE = {
    "қ": "к",
    "ғ": "г",
    "ә": "а",
    "ө": "о",
    "ұ": "у",
    "ү": "у",
    "і": "и",
    "ң": "н",
    "һ": "х",
}
KAZAKH_TO_LOOKALIKE = dict(KAZAKH_TO_LOOKALIKE_BASE)
KAZAKH_TO_LOOKALIKE.update(
    {key.upper(): value.upper() for key, value in KAZAKH_TO_LOOKALIKE_BASE.items()}
)

HOMOGLYPH_INJECTION_BASE = {
    "а": "a",
    "с": "c",
    "е": "e",
    "к": "k",
    "м": "m",
    "о": "o",
    "р": "p",
    "т": "t",
    "х": "x",
    "у": "y",
}
HOMOGLYPH_INJECTION = dict(HOMOGLYPH_INJECTION_BASE)
HOMOGLYPH_INJECTION.update(
    {key.upper(): value.upper() for key, value in HOMOGLYPH_INJECTION_BASE.items()}
)

KAZAKH_LETTERS_TRACKED = "әғқңөұүһі"

DEFAULT_KAZAKH_DROP_RATE = 0.6
DEFAULT_HOMOGLYPH_RATE = 0.05
BASELINE_PATH = REPOSITORY_ROOT / "scripts" / "ocr_eval_baseline.json"
RESULTS_DIRECTORY = REPOSITORY_ROOT / "scripts" / "ocr_eval_results"


def corrupt(
    text: str,
    seed: int,
    kazakh_drop_rate: float = DEFAULT_KAZAKH_DROP_RATE,
    homoglyph_rate: float = DEFAULT_HOMOGLYPH_RATE,
) -> str:
    """Apply a deterministic model of the observed OCR error classes."""
    generator = random.Random(seed)
    characters = []
    for character in text:
        if character in KAZAKH_TO_LOOKALIKE and generator.random() < kazakh_drop_rate:
            characters.append(KAZAKH_TO_LOOKALIKE[character])
            continue
        if character in HOMOGLYPH_INJECTION and generator.random() < homoglyph_rate:
            characters.append(HOMOGLYPH_INJECTION[character])
            continue
        characters.append(character)
    return "".join(characters)


def levenshtein(source, target) -> int:
    if source == target:
        return 0

    previous_row = list(range(len(target) + 1))
    for source_index, source_item in enumerate(source, start=1):
        current_row = [source_index]
        for target_index, target_item in enumerate(target, start=1):
            current_row.append(
                min(
                    previous_row[target_index] + 1,
                    current_row[target_index - 1] + 1,
                    previous_row[target_index - 1] + (source_item != target_item),
                )
            )
        previous_row = current_row
    return previous_row[-1]


def character_error_rate(reference: str, hypothesis: str) -> float:
    return levenshtein(reference, hypothesis) / max(1, len(reference))


def word_error_rate(reference: str, hypothesis: str) -> float:
    reference_words = reference.split()
    return levenshtein(reference_words, hypothesis.split()) / max(1, len(reference_words))


def kazakh_letter_recovery(reference: str, hypothesis: str) -> dict:
    """Per-letter survival counts for the Kazakh-specific alphabet."""
    table = {}
    for letter in KAZAKH_LETTERS_TRACKED:
        expected = reference.lower().count(letter)
        if not expected:
            continue
        table[letter] = {
            "expected": expected,
            "present": hypothesis.lower().count(letter),
        }
    return table


def merge_recovery(total: dict, addition: dict) -> None:
    for letter, counts in addition.items():
        bucket = total.setdefault(letter, {"expected": 0, "present": 0})
        bucket["expected"] += counts["expected"]
        bucket["present"] += counts["present"]


def evaluate(engine: str = "synthetic-noise") -> dict:
    """Run the corpus and return raw vs corrected metrics."""
    rows = []
    raw_recovery: dict = {}
    corrected_recovery: dict = {}

    for index, item in enumerate(CORPUS):
        reference = item["text"]
        if engine == "synthetic-noise":
            raw = corrupt(reference, seed=1000 + index)
        else:
            raw = recognize_with_engine(reference, engine)

        corrected = correct_ocr_text(raw, language_hint=item["language_hint"])

        merge_recovery(raw_recovery, kazakh_letter_recovery(reference, raw))
        merge_recovery(corrected_recovery, kazakh_letter_recovery(reference, corrected))

        rows.append(
            {
                "id": item["id"],
                "language_hint": item["language_hint"],
                "reference": reference,
                "raw": raw,
                "corrected": corrected,
                "cer_raw": character_error_rate(reference, raw),
                "cer_corrected": character_error_rate(reference, corrected),
                "wer_raw": word_error_rate(reference, raw),
                "wer_corrected": word_error_rate(reference, corrected),
            }
        )

    russian_rows = [row for row in rows if row["language_hint"] == "ru"]
    return {
        "engine": engine,
        "snippets": len(rows),
        "cer_raw": _mean(row["cer_raw"] for row in rows),
        "cer_corrected": _mean(row["cer_corrected"] for row in rows),
        "wer_raw": _mean(row["wer_raw"] for row in rows),
        "wer_corrected": _mean(row["wer_corrected"] for row in rows),
        "russian_cer_raw": _mean(row["cer_raw"] for row in russian_rows),
        "russian_cer_corrected": _mean(row["cer_corrected"] for row in russian_rows),
        "kazakh_letters_raw": raw_recovery,
        "kazakh_letters_corrected": corrected_recovery,
        "rows": rows,
    }


def _mean(values) -> float:
    collected = list(values)
    if not collected:
        return 0.0
    return sum(collected) / len(collected)


def recognize_with_engine(text: str, engine: str) -> str:
    """Render `text` to an image and run a real OCR engine over it."""
    image_path = render_snippet(text)
    try:
        if engine == "easyocr":
            import easyocr

            reader = easyocr.Reader(["ru", "mn", "en"], gpu=False)
            result = reader.readtext(str(image_path), detail=0, paragraph=True)
            return " ".join(result)
        if engine == "groq":
            from main import read_text_with_groq_vision

            return read_text_with_groq_vision(image_path.read_bytes(), image_path.name, "kk")
        raise SystemExit(f"Unknown engine: {engine}")
    finally:
        image_path.unlink(missing_ok=True)


FONT_CANDIDATES = (
    "C:/Windows/Fonts/arial.ttf",
    "C:/Windows/Fonts/times.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/Library/Fonts/Arial.ttf",
)


def render_snippet(text: str) -> Path:
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        raise SystemExit("Pillow is required for --engine easyocr/groq. Install it first.")

    font_path = next((path for path in FONT_CANDIDATES if Path(path).exists()), None)
    if font_path is None:
        raise SystemExit(
            "No usable font found. Add a TTF path with Kazakh Cyrillic glyphs "
            "to FONT_CANDIDATES in scripts/ocr_eval.py."
        )

    font = ImageFont.truetype(font_path, 42)
    image = Image.new("RGB", (1400, 160), (250, 249, 245))
    draw = ImageDraw.Draw(image)
    draw.text((30, 50), text, font=font, fill=(25, 25, 25))

    RESULTS_DIRECTORY.mkdir(parents=True, exist_ok=True)
    output_path = RESULTS_DIRECTORY / "render.jpg"
    image.save(output_path, quality=80)
    return output_path


def print_report(report: dict) -> None:
    print(f"engine: {report['engine']}   snippets: {report['snippets']}")
    print(f"CER  raw {report['cer_raw']:.4f}  ->  corrected {report['cer_corrected']:.4f}")
    print(f"WER  raw {report['wer_raw']:.4f}  ->  corrected {report['wer_corrected']:.4f}")
    print(
        f"CER (Russian only)  raw {report['russian_cer_raw']:.4f}"
        f"  ->  corrected {report['russian_cer_corrected']:.4f}"
    )
    if report["cer_raw"]:
        reduction = 1 - report["cer_corrected"] / report["cer_raw"]
        print(f"CER reduction: {reduction * 100:.1f}%")

    print("\nKazakh letter survival (present / expected):")
    for letter in KAZAKH_LETTERS_TRACKED:
        raw = report["kazakh_letters_raw"].get(letter)
        corrected = report["kazakh_letters_corrected"].get(letter)
        if not raw:
            continue
        print(
            f"  {letter}:  raw {raw['present']}/{raw['expected']}"
            f"   corrected {corrected['present']}/{corrected['expected']}"
        )


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate OCR text correction.")
    parser.add_argument(
        "--engine",
        default="synthetic-noise",
        choices=("synthetic-noise", "easyocr", "groq"),
    )
    parser.add_argument("--save-baseline", action="store_true")
    parser.add_argument("--json", action="store_true", help="Print the full report as JSON.")
    arguments = parser.parse_args()

    report = evaluate(arguments.engine)
    if arguments.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print_report(report)

    if arguments.save_baseline:
        summary = {key: value for key, value in report.items() if key != "rows"}
        BASELINE_PATH.write_text(
            json.dumps(summary, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        print(f"\nBaseline written to {BASELINE_PATH}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 3: Add the `scripts` package marker**

`scripts/ocr_eval.py` imports `scripts.ocr_eval_corpus`, which needs the directory to be importable as a package.

```bash
python -c "open('scripts/__init__.py','w').close()"
```

- [ ] **Step 4: Run the harness**

```bash
python scripts/ocr_eval.py
```

Expected: a report showing a non-zero `CER raw`, a lower `CER corrected`, a printed reduction percentage, and the Kazakh letter survival table. `қ` and `ғ` should recover substantially; `ұ ү і ң һ` recover little or none — that is the documented limit of harmony-only rules, not a bug.

- [ ] **Step 5: Record the baseline**

```bash
python scripts/ocr_eval.py --save-baseline
```

Expected: `scripts/ocr_eval_baseline.json` created.

- [ ] **Step 6: Ignore the result dumps**

Append to `.gitignore`:

```
scripts/ocr_eval_results/
```

- [ ] **Step 7: Commit**

```bash
git add scripts/__init__.py scripts/ocr_eval_corpus.py scripts/ocr_eval.py scripts/ocr_eval_baseline.json .gitignore
git commit -m "Add synthetic OCR accuracy eval harness and baseline"
```

---

### Task 4: Regression gate test

**Files:**
- Create: `backend/tests/test_ocr_eval_gate.py`

**Interfaces:**
- Consumes: `evaluate()` from `scripts/ocr_eval.py` (Task 3).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing test**

Create `backend/tests/test_ocr_eval_gate.py`:

```python
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
```

- [ ] **Step 2: Run the test**

```bash
python -m pytest backend/tests/test_ocr_eval_gate.py -v
```

Expected: all 5 pass.

If `test_correction_reduces_character_error_rate` fails, do **not** weaken the noise model or lower `MINIMUM_CER_REDUCTION`. Extend the correction rules instead — the highest-value additions, in order: (1) more entries in `LOANWORD_EXCEPTIONS` if over-correction is the cause, (2) more suffix pairs in `BACK_SUFFIX_REPAIRS`/`FRONT_SUFFIX_REPAIRS`, (3) `і` restoration for front-class words ending in `-ди/-ти/-ни`. Re-run `python scripts/ocr_eval.py` after each change and read the per-letter table to see which class is still leaking. If the target still cannot be reached, stop and report the measured number with the per-letter breakdown.

- [ ] **Step 3: Commit**

```bash
git add backend/tests/test_ocr_eval_gate.py
git commit -m "Add OCR correction regression gate"
```

---

### Task 5: Engine-level tuning

**Files:**
- Modify: `main.py` (function `read_text_from_image`, around lines 545–587)
- Modify: `main.py` (function `build_groq_ocr_prompt`, around lines 858–879)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: module-level constant `OCR_ALLOWLIST: str` in `main.py`.

- [ ] **Step 1: Add the allowlist constant**

In `main.py`, directly after the `LATIN_LETTERS` definition (around line 100), add:

```python
OCR_ALLOWLIST = (
    "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
    "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
    "әғқңөұүһіӘҒҚҢӨҰҮҺІ"
    "abcdefghijklmnopqrstuvwxyz"
    "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    "0123456789"
    " .,:;!?()[]«»\"'-–—/%№§+=*"
)
```

- [ ] **Step 2: Pass the allowlist to EasyOCR**

In `read_text_from_image`, add `allowlist=OCR_ALLOWLIST,` to the keyword arguments of the first `reader.readtext(...)` call, immediately after `link_threshold=0.25,`:

```python
                ocr_result = reader.readtext(
                    candidate_path,
                    detail=1,
                    paragraph=False,
                    decoder="beamsearch",
                    batch_size=8,
                    rotation_info=[0],
                    canvas_size=2560,
                    mag_ratio=1.5,
                    contrast_ths=0.03,
                    adjust_contrast=0.8,
                    text_threshold=0.45,
                    low_text=0.25,
                    link_threshold=0.25,
                    allowlist=OCR_ALLOWLIST,
                )
```

The existing `except TypeError:` branch already retries without advanced kwargs, so an EasyOCR version that does not accept `allowlist` degrades instead of failing.

- [ ] **Step 3: Harden the Groq prompt**

In `build_groq_ocr_prompt`, replace the sentence beginning `"Kazakh uses these extra Cyrillic letters:"` through `"Never swap Latin letters for Cyrillic lookalikes or vice versa. "` with:

```python
        "Kazakh uses these extra Cyrillic letters: Әә Ғғ Ққ Ңң Өө Ұұ Үү Һһ Іі — reproduce "
        "them exactly. These pairs are DIFFERENT letters and must never be substituted "
        "for one another: қ≠к, ғ≠г, ә≠а, ө≠о, ұ≠у, ү≠у, і≠и, ң≠н, һ≠х. "
        "When a Kazakh word contains қ or ғ its vowels are а, о, ұ, ы; when it contains "
        "к or г its vowels are ә, ө, ү, е, і. Use this to disambiguate blurred letters. "
        "A Cyrillic word must contain no Latin letters, and a Latin word no Cyrillic "
        "letters — never mix lookalikes such as a/а, c/с, e/е, o/о, p/р, x/х, y/у. "
```

- [ ] **Step 4: Verify the module still imports and tests pass**

```bash
python -c "import main; print(len(main.OCR_ALLOWLIST))"
python -m pytest backend/tests -q
```

Expected: a printed integer, then all tests pass.

- [ ] **Step 5: Commit**

```bash
git add main.py
git commit -m "Restrict EasyOCR charset and harden Groq OCR prompt"
```

---

### Task 6: Wire correction into the image endpoint

**Files:**
- Modify: `main.py` (imports around line 25; constants around line 77; `adapt_image` around line 388; `health` around line 300)
- Modify: `README.md`
- Test: `backend/tests/test_adapt_image_correction.py` (create)

**Interfaces:**
- Consumes: `correct_ocr_text` from Task 2.
- Produces: `apply_ocr_correction(text: str, language_hint: str) -> str` in `main.py`; `/health` key `ocr_correction_enabled: bool`.

- [ ] **Step 1: Write the failing test**

Create `backend/tests/test_adapt_image_correction.py`:

```python
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
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
python -m pytest backend/tests/test_adapt_image_correction.py -v
```

Expected: FAIL with `AttributeError: module 'main' has no attribute 'ocr_correction_enabled'`.

- [ ] **Step 3: Add the import**

In `main.py`, after the existing line 25 import, add:

```python
from backend.app.services.ocr_correction import correct_ocr_text
```

- [ ] **Step 4: Add the flag helper and wrapper**

In `main.py`, directly after the `should_use_gpu()` function (around line 424), add:

```python
def ocr_correction_enabled() -> bool:
    return os.getenv("SULU_READ_OCR_CORRECTION", "true").strip().lower() not in {
        "0",
        "false",
        "no",
        "n",
        "off",
    }


def apply_ocr_correction(text: str, language_hint: str) -> str:
    if not text or not ocr_correction_enabled():
        return text
    try:
        return correct_ocr_text(text, language_hint=language_hint)
    except Exception:
        logger.exception("OCR correction failed; using uncorrected text")
        return text
```

- [ ] **Step 5: Call it in `adapt_image`**

In `adapt_image`, replace the line

```python
        extracted_text = service_clean_ocr_text(extracted_text)
```

with

```python
        extracted_text = apply_ocr_correction(extracted_text, language_hint)
        extracted_text = service_clean_ocr_text(extracted_text)
```

- [ ] **Step 6: Surface the flag on `/health`**

In `health`, add to the returned dict, after the `"ocr_error"` entry:

```python
        "ocr_correction_enabled": ocr_correction_enabled(),
```

- [ ] **Step 7: Run the tests**

```bash
python -m pytest backend/tests -q
```

Expected: all pass, including the 5 new ones.

- [ ] **Step 8: Document the flag**

In `README.md`, in the `## Environment` `.env` block, add after `SULU_READ_RUNTIME_SQLITE_FALLBACK=true`:

```env
SULU_READ_OCR_CORRECTION=true
```

Then add this prose immediately after that `.env` code block in `README.md` — plain
markdown paragraphs, followed by a fenced `bash` block containing exactly
`python scripts/ocr_eval.py`:

> `SULU_READ_OCR_CORRECTION` controls the post-OCR correction layer that restores
> Kazakh-specific letters (`қ ғ ә ө`) and strips Latin/Cyrillic homoglyphs from
> `/v1/adapt-image` output. It is on by default; set it to `false` to return the raw
> engine text. `/health` reports the active state as `ocr_correction_enabled`.
>
> To measure recognition quality after changing anything in the OCR path, run the
> synthetic evaluation harness (`python scripts/ocr_eval.py`). It reports CER, WER,
> and per-letter recovery for the Kazakh alphabet.

- [ ] **Step 9: Commit**

```bash
git add main.py README.md backend/tests/test_adapt_image_correction.py
git commit -m "Apply post-OCR correction in the image adaptation endpoint"
```

---

### Task 7: Remove the dead duplicate text pipeline in main.py

**Files:**
- Modify: `main.py` (delete lines ~1116–1298 and the constants listed below)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing. This task must not change behavior.

Do this task last so a behavior change can never be attributed to the deletion.

- [ ] **Step 1: Confirm the code is dead**

```bash
grep -n "prepare_text_for_adaptation\|extract_adapted_words\|split_word_to_syllables\|detect_language_hint\|is_adaptable_word\|remove_existing_syllable_markup" main.py backend/ scripts/ -r
```

Expected: every hit inside `main.py` is either the definition itself or a call from another definition in the same block. The live path uses `backend/app/services/syllabification.py`, imported as `service_clean_ocr_text` and via `build_adaptation_payload`. `normalize_text` must show hits at lines ~460–1076 — those are live, so it stays.

- [ ] **Step 2: Delete the dead functions**

Delete these definitions from `main.py` (the contiguous block starting at `def prepare_text_for_adaptation(source: str, text: str) -> str:` and ending at the end of `choose_split_index`, minus `normalize_text`, which is inside that block and must be kept):

`prepare_text_for_adaptation`, `clean_ocr_text`, `sentence_case_text`, `restore_known_proper_nouns`, `remove_existing_syllable_markup`, `adapt_text`, `extract_adapted_words`, `is_adaptable_word`, `detect_language_hint`, `detect_kazakh_vowel_harmony`, `split_word_to_syllables`, `choose_split_index`.

Keep `normalize_text` exactly as it is — move it above the deleted block if that makes the file read better, but do not change its body.

- [ ] **Step 3: Delete the now-unused constants**

Delete from `main.py`: `SECONDARY_SYLLABLE_DIVIDERS`, `OCR_TEXT_REPLACEMENTS`, `KNOWN_PROPER_NOUN_ROOTS`, `HYPHENATED_WORD_PATTERN`, `TOKEN_PATTERN`, `ALL_CYRILLIC_VOWELS`, `RUSSIAN_VOWELS`, `KAZAKH_VOWELS`, `KAZAKH_FRONT_VOWELS`, `KAZAKH_BACK_VOWELS`.

Keep `CYRILLIC_LETTERS`, `LATIN_LETTERS`, `KAZAKH_SPECIFIC_LETTERS`, `LETTER_CLASS`, `STANDARD_SYLLABLE_DELIMITER`, and `OCR_ALLOWLIST`.

- [ ] **Step 4: Verify nothing dangles**

```bash
python -c "import main; print('import ok')"
python -m pyflakes main.py 2>/dev/null || python -c "import ast,sys; ast.parse(open('main.py',encoding='utf-8').read()); print('parse ok')"
```

Expected: `import ok`, then either a clean pyflakes run or `parse ok`.

If the import raises `NameError` for a deleted constant, that constant was live — restore it and note which live function used it.

- [ ] **Step 5: Run the full suite**

```bash
python -m pytest backend/tests -q
```

Expected: all pass. `backend/tests/test_syllabification.py` passing proves the service module still drives the live behavior.

- [ ] **Step 6: Run the eval one final time**

```bash
python scripts/ocr_eval.py
```

Expected: same numbers as Task 3/4 — the deletion changes nothing.

- [ ] **Step 7: Commit**

```bash
git add main.py
git commit -m "Remove dead duplicate text pipeline from main.py"
```

---

## Final Verification

- [ ] `python -m pytest backend/tests` — all green
- [ ] `python scripts/ocr_eval.py` — CER reduction ≥ 40%, Russian CER not increased
- [ ] `SULU_READ_OCR_CORRECTION=false python -c "import main; print(main.apply_ocr_correction('кала','kk'))"` prints `кала`
- [ ] `git diff --stat HEAD~7..HEAD -- requirements.txt` prints nothing (no new runtime dependency)
- [ ] `curl localhost:7860/health` shows `ocr_correction_enabled` (only if running the server locally)
