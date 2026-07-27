# Kazakh Lexicon Letter Recovery — Design Spec

Date: 2026-07-27
Status: approved for planning
Scope: `POST /v1/adapt-image` — restoring Kazakh-specific letters lost by OCR
Follows: `2026-07-26-kazakh-russian-ocr-accuracy-design.md` (and its Post-review revision)

## Problem

The previous branch shipped homoglyph folding, a hardened Groq prompt, an EasyOCR
charset allowlist, and an eval harness. It deliberately shipped **no** Kazakh letter
recovery: every rule-based attempt rewrote text that was already correct
(`кітапхана` → `қітапхана`, `болатын` → `болатың`, `телефонын` → `телефоның`) and was
deleted across two review rounds.

The root cause was recorded there: Kazakh vowel harmony is a **generation** constraint,
not a **recovery** constraint. An `а` in a word means `қ` is permitted, never that a
scanned `к` is wrong. No amount of exception-listing fixes that, because Kazakh is
agglutinative and an exception list matches bare stems.

The Kazakh-letter-drop channel of the eval therefore sits at 0% recovery. On the
EasyOCR path those letters are still structurally unrecoverable, and on the Groq path
recovery depends entirely on prompt discipline.

## Approach

Replace the rule with a **decision procedure backed by a dictionary**. For a word that
OCR may have flattened, enumerate the candidate restorations and change the word only
when the dictionary settles the question.

The rule, in full:

1. If the scanned word is already a known Kazakh word — **never touch it**.
2. Otherwise generate every candidate over the confusable set
   (`к→қ`, `г→ғ`, `а→ә`, `о→ө`, `у→ұ`, `у→ү`, `и→і`, `н→ң`, `х→һ`).
3. Change the word only if **exactly one** candidate is a known Kazakh word.
   Zero candidates or two or more → leave it alone.

This is safe where the harmony rules were not, because the dictionary — not a
heuristic — decides, and ambiguity resolves to "do nothing". `Москва` survives because
`Мосқва` is not a Kazakh word either. It also reaches all nine letters, including
`і ң ұ ү һ`, which harmony rules could never restore.

### Asymmetry that makes the design safe

`is_kazakh_word` is deliberately **generous**. Over-accepting only ever *prevents* a
repair, costing recall. Under-accepting is what causes damage. Every judgment call in
the lookup resolves toward accepting.

## Data

Vendored verbatim into `backend/app/data/`:

- `kk_KZ.dic` — 53,971 base words with affix flags (2.1 MB)
- `kk_KZ.aff` — 2,551 suffix rules in 93 flag groups, `N` (no cross-product), suffix
  additions up to 9 characters, each with a strip count and a stem-ending condition

Source: [taem/hunspell-kk](https://github.com/taem/hunspell-kk), derived from
aspell-kk_KZ 0.60 by Alexey Lipchansky, with the affix file by Akmaral Mussayeva and
contributions from László Németh and Rail Aliev.

Upstream is tri-licensed "GNU GPL version 2.0 or above, GNU LGPL version 2.1 or above
and Mozilla MPL version 1.1 or above". **This project elects MPL 1.1.** The two data
files stay under MPL 1.1 with the upstream license text alongside them in
`backend/app/data/LICENSE-kk_KZ.txt` and attribution in the README. MPL 1.1 is
file-level copyleft: the dictionary files carry their own license, and the MIT license
of the rest of the repository is unaffected. No source modification of the data files
is permitted — they are vendored byte-for-byte so provenance stays checkable.

## Components

### 1. `backend/app/services/kazakh_lexicon.py`

Owns everything about the dictionary. No other module reads `backend/app/data/`.

- Parses the `.dic` into `dict[str, str]` mapping lowercase stem → concatenated affix
  flags. Flags are stored as a `str`, not a `set`: measured 11 MB heap versus 119 MB.
- Parses the `.aff` `SFX` rules into `dict[str, list[tuple[flag, strip, condition]]]`
  keyed by the added suffix, so lookup indexes by word ending.
- `contains(word)` — true when the lowercase word is a stem, or when stripping a known
  suffix yields a stem whose flags include that suffix's group and whose ending
  satisfies the rule's condition. This is a hunspell single-suffix match; the affix
  file already encodes composed endings (`птыңыздар`, `йдіңіздер`), so one level covers
  most inflection.
- Loaded lazily on first use behind a lock, so application startup is unaffected.
  Measured: ~1 s one-time, ~0.02 ms per lookup.
- Missing or unreadable data files must degrade to "no lexicon" — the caller then makes
  no repairs — rather than raising. A packaging mistake must not break image adaptation.

### 2. Repair in `backend/app/services/ocr_correction.py`

A new step in the Kazakh-gated path, alongside the existing word-initial `ң` fix and
`һ` loanword table. Guards, each measured rather than assumed:

- **Word must be ≥4 characters and fully alphabetic.** Below that the candidate space is
  dominated by abbreviations; without this, `км` → `қм`.
- **Word-final `н→ң` is excluded from candidate generation.** This is the load-bearing
  guard. Kazakh forms the 3rd-person-possessive accusative in `-ын/-ін`, and those
  forms are under-represented in the dictionary, so a final `н→ң` finds a spurious
  unique match: `орнын` → `орның`, `кітабын` → `кітабың`, `заводын` → `заводың`. The
  cost is that the genitive `-ның` stays unrecovered. That trade is deliberate — this
  exact ambiguity produced the damage in the previous branch.
- **At most 6 ambiguous positions**, and at most 256 candidates evaluated per word, so a
  pathological word cannot dominate request latency. Beyond the cap, leave the word.
- Candidate search short-circuits as soon as a second match is found.
- Original capitalization is reapplied to the repaired word.

Runs only when the existing Kazakh document gate is open, and only when
`SULU_READ_OCR_CORRECTION` is enabled. Both are unchanged.

## Measured Result

Prototyped against the real dictionary and the existing 44-row eval corpus:

| configuration | false rewrites on clean text | corrupted words restored exactly |
|---|---|---|
| no guards | 8 | 51% |
| ≥4 characters | 6 | 49% |
| **≥4 characters + no word-final `н→ң`** | **0** | **45%** |
| ≥5 characters + no word-final `н→ң` | 0 | 35% |

Lexicon coverage of clean corpus Kazakh words: 90%. Lookup cost ~9 ms for a 400-word
page after the one-time load.

## Testing

- Unit tests for `kazakh_lexicon`: known stems, known inflected forms
  (`мектептің`, `Республикасы`, `картасы`, `телефонын`), non-words (`абракадабра`,
  `қітапхана`, `Мосқва`), case insensitivity, and graceful degradation when the data
  files are absent.
- Unit tests for the repair: `кала` → `қала`, `тагы` → `тағы`, and the full clean-word
  regression list from the previous branch (`кітапхана`, `болатын`, `оқитын`,
  `телефонын`, `орнын`, `кітабын`, `заводын`, `Москва`, `картасы`, `музыкалық`)
  asserted unchanged, plus `км` unchanged and ambiguity resolving to no-op.
- The eval gains a real threshold on the Kazakh-drop channel, which is currently 0%.
- The clean-input no-op gate stays at 0 and remains the primary safety net.
- A latency test: a 2,000-word page completes well inside the request budget.

## Non-Goals

- Stem alternation (`кітап`→`кітаб`, `орын`→`орн`) and multi-suffix chaining beyond
  what the affix file encodes. Hand-written Kazakh morphology is how the previous
  branch went wrong.
- Russian lexicon lookup. Russian documents are handled by the language gate.
- Recovering the genitive `-ның` (see the word-final `н` guard).
- Replacing the Groq prompt hardening. The prompt remains the first line of defense;
  this layer is the safety net beneath it.

## Post-review revision

A review of the implementation found a **Critical**: `POST /v1/adapt-image` defaults
`language_hint="kk"` (`main.py`), so a Russian page with no explicit hint runs through
the Kazakh repair path described above, not the language gate. "Russian documents are
handled by the language gate" (the Non-Goal above) assumed the gate was always closed
for Russian text; it is not, by default. Real Russian words whose Kazakh-restored form
is also a real Kazakh word satisfied the "exactly one candidate" rule and got rewritten:
`доска`→`досқа`, `кошка`→`қошқа`, `шапка`→`шапқа`, `бумага`→`бумаға`, `карман`→`қарман`,
plus `костер`, `каша`, `кожа`, `ласка`, `катер`, `козел`, and two more — 13 confirmed
damaged words in total.

**Fix.** Extend the same generosity-favors-no-op philosophy the design already uses for
the Kazakh lexicon: vendor the Russian hunspell dictionary
(`backend/app/data/ru_RU.dic`/`.aff`, from
[wooorm/dictionaries](https://github.com/wooorm/dictionaries), BSD-3-Clause) and check it
in `_repair_with_lexicon`, after the Kazakh-lexicon check and before candidate
generation: **a word already valid in Russian is never treated as damaged Kazakh.**
Missing Russian data degrades to "no Russian guard" rather than "no repairs at all" — the
Kazakh guard, which is what actually matters for safety, stays in force either way.

**Measured cost.** All 13 damaged words are real Russian dictionary entries, so the guard
recognizes and blocks every one of them (verified directly, and via a Russian paragraph
containing five of them returned byte-identical under `language_hint="kk"`). On the
Kazakh-letter-drop channel of the eval corpus, word-level recovery (words the noise model
actually corrupted, then correctly restored) moved from 33/49 (67.3%) without the guard to
32/49 (65.3%) with it — **one previously-recovered word lost** to over-blocking, against
thirteen false rewrites of correct Russian text eliminated. The eval corpus also gained
three rows of ordinary Russian sentences under `language_hint="kk"` (`rk-01..rk-03`,
including several of the damaged words) so the clean-input no-op gate — the single most
important gate in this design — can no longer read 0 while this class of bug is live.
