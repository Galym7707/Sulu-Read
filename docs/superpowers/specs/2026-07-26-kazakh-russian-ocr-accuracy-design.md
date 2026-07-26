# Kazakh/Russian OCR Accuracy — Design Spec

Date: 2026-07-26
Status: approved for planning
Scope: `POST /v1/adapt-image` text recognition quality

## Problem

Textbook photos processed by `/v1/adapt-image` come back with Kazakh-specific letters
replaced by their Russian lookalikes, and with Latin/Cyrillic homoglyphs mixed into
words. The adapted reader text, the syllable split, and every generated exercise
inherit the error, so a single misread letter degrades the whole downstream chain.

Three commits have already tried to fix this (`c5efada`, `9752eb2`, `ddc96c0`). None
of them shipped a measurement, so it is unknown whether accuracy improved, stayed
flat, or regressed.

### Root causes

1. **EasyOCR fallback cannot emit the letters at all.** `get_easyocr_languages()`
   returns `["ru", "mn", "en"]` when EasyOCR has no `kk` model (it does not).
   The Russian recognition model's charset has no `ә ғ қ ң һ і ұ`; Mongolian adds
   only `ө ү`. On this path those letters are structurally unrecoverable — no prompt,
   threshold, or preprocessing change can produce them.
2. **Groq vision path relies purely on prompt discipline.** `build_groq_ocr_prompt()`
   asks the model not to substitute lookalikes. Vision models normalize to the more
   frequent Russian letter anyway, and additionally swap Latin/Cyrillic homoglyphs
   (`c/с`, `o/о`, `p/р`, `x/х`, `y/у`, `a/а`, `e/е`, `k/к`, `m/м`, `t/т`, `H/Н`, `B/В`).
3. **No post-OCR validation.** `clean_ocr_text()` only maps `$`→`§` and tightens
   spacing. Nothing checks whether the output is orthographically possible.
4. **No eval.** No corpus, no metric, no regression gate.

## Goals

- Measure OCR text quality with a reproducible metric (CER, WER, per-letter confusion).
- Recover Kazakh-specific letters lost by both recognition paths, using deterministic
  orthographic rules rather than guesses.
- Remove Latin/Cyrillic homoglyph contamination from Cyrillic words and vice versa.
- Do all of the above without new runtime dependencies, without a second model call,
  and without increasing per-request latency measurably.
- Ship a regression gate so the next OCR change is verified, not asserted.

## Non-Goals

- Real-photo evaluation set (may be added later; synthetic corpus is the CI gate).
- Replacing EasyOCR with Tesseract/PaddleOCR, or adding a `kk` recognition model.
- Multi-model consensus decoding on Groq.
- Any change to syllabification, exercise generation, or the Android client.
- Spell-correcting genuine author errors, or normalizing loanword orthography.

## Approach

A post-OCR correction layer plus cheap engine-level tuning, validated by a synthetic
eval harness.

The correction layer is viable because the dominant error class is a **closed set of
confusable letters**, and Kazakh orthography constrains that set hard:

- Back-vowel (жуан) words carry `а о ұ ы`; front-vowel (жіңішке) words carry `ә ө ү е і`.
- `қ` and `ғ` occur only in back-vowel words; `к` and `г` only in front-vowel words.
  This is near-absolute in native vocabulary, and breaks mainly in Russian loanwords
  (`класс`, `кино`, `механика`), which are handled by an exception list.
- `ң` never appears word-initially.
- `һ` appears only in a small closed set of Arabic/Persian loans (`гауһар`, `қаһарман`,
  `жиһаз`, `шаһар`).

So when a word contains an unambiguous harmony marker, the correct form of every
confusable letter in that word is determined, not guessed.

### Rejected alternatives

- **Multi-model Groq consensus** — 2× latency and 2× quota against a free-tier HF
  Space, and it cannot recover letters both models normalize away in the same direction.
- **Swap OCR engine** — new system dependencies, Docker rebuild, slower CPU inference,
  and Tesseract `kaz` quality on phone photos is unproven for this corpus.

## Components

### 1. Eval harness — `scripts/ocr_eval.py`

Standalone dev/CI script. No production import.

- Corpus: ~40 snippets in `scripts/ocr_eval_corpus.py` — Kazakh, Russian, mixed
  Kazakh+Russian, and a few Latin-containing lines. Sourced from public-domain style
  school text written for this repo. Each snippet is its own ground truth.
- Rendering: Pillow draws each snippet to an image with varied font (Arial/Times or
  DejaVu), font size, JPEG quality, Gaussian blur, rotation up to ±2°, and background
  tint, simulating a phone photo of a page.
- Runs the corpus through a pluggable pipeline: `raw` (no correction) vs `corrected`.
- Metrics: character error rate, word error rate, and a per-letter confusion table
  restricted to the Kazakh confusable set.
- Output: JSON to `scripts/ocr_eval_results/<timestamp>.json` plus a console table.
- Degrades gracefully: if Pillow or a usable font is missing, exits with a clear
  message rather than a traceback.

Two invocation modes so the layer can be tested without burning API quota:

- `--engine synthetic-noise` — bypasses the real OCR engines and applies a scripted
  corruption model (drops Kazakh letters to their Russian lookalikes, injects
  homoglyphs) to the ground truth. Deterministic, offline, CI-safe. This is what the
  regression gate uses.
- `--engine easyocr` / `--engine groq` — runs the real path. Manual, local, opt-in.

### 2. Correction module — `backend/app/services/ocr_correction.py`

Pure functions, no I/O, no network. Public entry point:

```python
def correct_ocr_text(text: str, *, language_hint: str = "kk") -> str
```

Two tiers.

**Tier 1 — script hygiene (always applied, both languages).**

- Per word, count Cyrillic vs Latin letters. If one script is a strict majority, fold
  the minority-script homoglyphs into the majority script using a fixed bidirectional
  table (`a↔а c↔с e↔е o↔о p↔р x↔х y↔у k↔к m↔м t↔т H↔Н B↔В T↔Т M↔М P↔Р C↔С O↔О A↔А E↔Е K↔К X↔Х`).
  Words with no majority are left alone.
- Fold digits that sit inside an otherwise alphabetic word: `0→о`, `1→і` in Kazakh
  context / `1→и` otherwise, `3→з`, `6→б`. Only when the digit is surrounded by
  letters on both sides.
- Never touch a token that is entirely digits, entirely punctuation, or a URL.

**Tier 2 — Kazakh harmony repair (gated).**

Applied to a word only when the document is judged Kazakh (see gate below).

1. Classify the word: count back markers (`а о ұ ы қ ғ`) and front markers (`ә ө ү е і`).
   `и`, `у`, and consonants other than `қ ғ к г` carry no class evidence.
2. If the counts are tied, or both are zero, leave the word unchanged.
3. Otherwise, for each confusable letter of the opposite class, substitute its
   same-class counterpart: `к↔қ`, `г↔ғ`, `а↔ә`, `о↔ө`, `ұ↔ү`.
4. Skip the word entirely if its lowercase form is in `LOANWORD_EXCEPTIONS`
   (Russian/international loanwords that legitimately violate harmony) or if it
   contains a Russian-only letter that never appears in native Kazakh stems.
5. Independently of harmony: `ң` at word start is corrected to `н`; the recognized
   Kazakh suffix set (`-ның/-нің`, `-дың/-дің`, `-тың/-тің`, `-мен/-бен/-пен`,
   `-ға/-ге/-қа/-ке`, `-лар/-лер/-дар/-дер/-тар/-тер`) is snapped to its correct
   form when the stem's harmony class is known.
6. `һ` is restored only for words in the closed `H_LOANWORDS` set.

**Language gate.** Tier 2 runs when either:

- the request `language_hint` is `kk`, or
- the document shows Kazakh evidence independent of the hint: at least
  `KK_EVIDENCE_MIN_RATIO` (initial value 0.05, tuned against the eval corpus) of word
  tokens contain a Kazakh-specific letter or end in a Kazakh suffix.

Words that look Russian (contain `ё щ ъ ь э` or match the loanword exception list) are
skipped even inside a Kazakh document.

Every rule is conservative by construction: no rule fires ⇒ word passes through
unchanged. Correction can only ever be a no-op or a targeted substitution.

### 3. Engine-level tuning — `main.py`

- EasyOCR: pass `allowlist` covering Cyrillic (Russian + Kazakh) + Latin + digits +
  common punctuation to `reader.readtext()`, guarded by the existing `TypeError`
  fallback path. Removes the `ђјљњћџ` class of noise the Mongolian/Serbian models leak.
- Groq: extend `build_groq_ocr_prompt()` with an explicit confusable-pair table
  (`қ≠к ә≠а ө≠о ұ≠у ү≠у і≠и ғ≠г ң≠н һ≠х`) and an instruction that Latin letters must
  never appear inside a Cyrillic word. Prompt-only change; no model or budget change.

### 4. Wiring — `main.py:adapt_image`

`correct_ocr_text(extracted_text, language_hint=language_hint)` is called immediately
before the existing `service_clean_ocr_text(...)` call, so correction happens once, on
the raw engine output, for both the Groq and EasyOCR paths.

Kill switch: `SULU_READ_OCR_CORRECTION` (default `true`). When false, the call is
skipped and behavior is byte-identical to today.

`/health` gains `ocr_correction_enabled` so a deployment can confirm which path is live.

### 5. Dead-code removal — `main.py`

`main.py` lines ~1116–1298 duplicate `backend/app/services/syllabification.py`. Verified
call graph: these definitions are referenced only by each other, never by the live
request path, which routes through the service module via `build_adaptation_payload`.

Deleted: `prepare_text_for_adaptation`, `clean_ocr_text`, `sentence_case_text`,
`restore_known_proper_nouns`, `remove_existing_syllable_markup`, `adapt_text`,
`extract_adapted_words`, `is_adaptable_word`, `detect_language_hint`,
`detect_kazakh_vowel_harmony`, `split_word_to_syllables`, `choose_split_index`, and the
constants used only by them (`SECONDARY_SYLLABLE_DIVIDERS`, `OCR_TEXT_REPLACEMENTS`,
`KNOWN_PROPER_NOUN_ROOTS`, `HYPHENATED_WORD_PATTERN`, `TOKEN_PATTERN`,
`ALL_CYRILLIC_VOWELS`, `KAZAKH_FRONT_VOWELS`, `KAZAKH_BACK_VOWELS`).

Kept: `normalize_text` (called by the live URL and Groq helpers at lines 460–1076),
`CYRILLIC_LETTERS`/`LATIN_LETTERS`/`KAZAKH_SPECIFIC_LETTERS` (used by the live
`count_readable_letters` scoring path), and `LETTER_CLASS`.

The duplication is removed as part of this work because it is an active hazard: the
next person tuning OCR text cleanup has a 50% chance of editing the copy that never
runs. Deletion is a separate, final phase so it cannot mask an accuracy regression.

## Data Flow

```
photo upload
  → prepare_image_for_groq / create_ocr_candidate_images   (unchanged)
  → Groq vision OCR  |  EasyOCR (now with allowlist)
  → correct_ocr_text(raw, language_hint)                    ← NEW
      tier 1: script hygiene
      tier 2: Kazakh harmony repair (gated)
  → clean_ocr_text                                          (unchanged)
  → build_adaptation_payload → syllabification              (unchanged)
```

## Error Handling

- `correct_ocr_text` never raises on malformed input: empty string, whitespace-only,
  and non-Cyrillic input all return the input unchanged.
- The call site wraps the correction in a try/except that logs and falls back to the
  uncorrected text. A bug in the correction layer must not turn a readable page into
  an error response.
- The eval harness is dev-only; a failure there cannot affect a request.

## Testing

- `backend/tests/test_ocr_correction.py` — unit tests per rule: homoglyph folding both
  directions, majority-script tie handling, harmony repair for `к/қ` and `г/ғ`,
  `а/ә` and `о/ө` repair, loanword exceptions left untouched, Russian text left
  untouched, word-initial `ң`, suffix snapping, empty/garbage input, and idempotency
  (`correct(correct(x)) == correct(x)`).
- `backend/tests/test_ocr_eval_gate.py` — runs the eval harness in
  `--engine synthetic-noise` mode over the corpus and asserts corrected CER is below
  the recorded baseline CER by at least the agreed margin. Offline and deterministic.
- Existing `backend/tests/test_syllabification.py` must stay green, proving the dead
  code deletion changed no live behavior.

## Acceptance Criteria

1. `python scripts/ocr_eval.py --engine synthetic-noise` reports a baseline and a
   corrected run, with CER, WER, and the Kazakh per-letter confusion table.
2. Corrected CER is at least 40% lower than baseline CER on the synthetic corpus.
3. Zero regressions on pure-Russian corpus entries: their CER must not increase.
4. `python -m pytest backend/tests` passes.
5. `SULU_READ_OCR_CORRECTION=false` reproduces today's output byte-for-byte.
6. No new entry in `requirements.txt` for the production path.

## Risks

- **Over-correction on Russian text inside Kazakh documents.** Mitigated by the
  loanword exception list, the Russian-only-letter check, and acceptance criterion 3.
- **Synthetic corpus does not match real photo error distribution.** Accepted: the
  corruption model is derived from the confusion classes actually observed in the code
  and prompt history. Real-photo eval is listed as future work, not a blocker.
- **Harmony rule exceptions in dialect or older textbook orthography.** Mitigated by
  the tie/no-evidence rule — ambiguous words are left alone rather than guessed.
- **EasyOCR `allowlist` support varies by version.** Mitigated by the existing
  `TypeError` retry path that drops advanced kwargs.

## Future Work (explicitly out of scope)

- Real-photo eval set with human-typed ground truth.
- A Kazakh frequency lexicon for word-level correction beyond harmony rules.
- Per-word confidence surfacing to the Android reader for low-confidence highlighting.

## Post-review revision (2026-07-26)

A final whole-branch review reproduced concrete damage from the vowel-harmony repair
described above: it rewrote already-correct text, both from live examples
(`кітапхана` → `қітапхана`, `болатын` → `болатың`, a stray `ө` anywhere in a Russian
sentence turning every `к`/`г` in it into `қ`/`ғ`, `С3Н8 газы` → `СзН8 ғазы`) and inside
this spec's own eval corpus (2 of 40 clean ground-truth rows).

Root cause: Kazakh vowel harmony is a *generation* constraint — it says which vowels
and consonants a well-formed Kazakh word is built from — not a *recovery* constraint.
An `а` in a scanned word means `қ` is permitted there; it never means a scanned `к` is
wrong. Applying harmony post-hoc to a string, with no lexicon and no OCR confidence
signal, guesses at words instead of verifying them. `LOANWORD_EXCEPTIONS` could not
save this design: it matches bare stems, and Kazakh is agglutinative, so `карта` was
protected while `картасы` was not.

Removed as a result (see `backend/app/services/ocr_correction.py`):

- The `TO_BACK`/`TO_FRONT` letter-rewrite tables and the `BACK_MARKERS`/`FRONT_MARKERS`
  vowel-class counting that drove them. All four confusable pairs (`к/қ`, `г/ғ`, `а/ә`,
  `о/ө`) plus `ұ/ү` are gone — the corrector no longer rewrites any of these letters.
- `_fold_digits` / `DIGIT_TO_CYRILLIC`. It corrupted chemical formulas and page numbers
  for a speculative benefit that was never measured.
- The `тын` → `тың` suffix repair (it destroyed the extremely common `-атын`/`-йтын`
  participle) and all three front suffix repairs `нин`/`дин`/`тин` → `нің`/`дің`/`тің`
  (common Russian words such as `господин` and `гражданин` end in `дин`/`нин`, and the
  front repair guessed two letters — the vowel and `ң` — at once).

Kept unchanged: Tier 1 homoglyph folding, the word-initial `ң` → `н` fix, the closed
`H_LOANWORD_REPAIRS` table, and the `LOANWORD_EXCEPTIONS`/`RUSSIAN_ONLY_SIGNALS` guards.
The back-vowel genitive suffix repair survives in reduced form — only `нын` → `ның` and
`дын` → `дың`, which are self-identifying strings needing no vowel-class computation.

The language gate (`_is_kazakh_document`) was also tightened: an explicit
`language_hint` of `ru` or `en` now forces the Kazakh repair path off, instead of
document evidence being able to override it. The evidence heuristic (for a missing or
unrecognized hint) now additionally requires at least 2 evidence words, not just the
5% ratio, so a single stray Kazakh-looking letter in a short document cannot open the
gate.

A lexicon-gated version of harmony recovery — one that checks a candidate correction
against a real word list before applying it — remains possible future work, listed
above, but is a separate project with a real accuracy signal, not a tuning pass on the
rules removed here.
