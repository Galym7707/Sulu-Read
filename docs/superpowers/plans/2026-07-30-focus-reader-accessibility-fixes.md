# Focus Reader — accessibility refactor spec

Follow-up to `2026-07-30-focus-reader.md`. Written after the first APK review.
Audience: whoever implements next. Every item is scoped, with the exact file to touch.

Baseline: commit `d0d3696` on `main`.

**Status.** §1, §2 and §4 are implemented and merged. §4's sub-API-31 raster blur was verified
on an API 30 x86_64 emulator on 2026-07-30: text is genuinely unreadable, paragraph shape
survives, the sharp focus word lands exactly over its blurred twin. §3 is a licensing decision
and §5 needs provider accounts; both are untouched.

That emulator run surfaced a defect worse than anything in the original review — see §6.

---

## 0. Two corrections to the review's premises

Stated so the fixes target the real defect rather than an imagined one.

**"The user must fail 4 times before any help appears."** Not what ships. Help arrives on
the *first* miss, and there is already an always-visible **Помощь** button next to the read
button (`FocusReaderScreen.kt`, `OutlinedButton` in the control `Row`) that jumps straight
to syllables. The escalation is one rung per miss: miss 1 → 200 ms flash, miss 2 → syllables,
miss 3 → letter names, miss 4 → meaning.

The real defect is narrower and still real: **the first rung is nearly worthless.** A 200 ms
re-flash of a word the reader just failed gives them no new information. It came from the
Davis "sweep" exercise, which is a *fluency drill for words already known* — I applied it to
a word the reader has just demonstrated they do not know. Wrong tool, wrong moment. And
there is no time-based help at all: a reader who stares in silence gets nothing until they
guess wrong out loud, which is exactly the reader who most needs a nudge.

**"The text simply goes serif, defaulting to Roboto/Noto Serif."** Not what ships either.
`app/src/main/res/font/sulu_serif_{regular,bold}.ttf` are bundled font files and
`FocusTypography.kt` binds them explicitly — there is no `FontFamily.Serif` anywhere in the
Focus code, so no system fallback is possible. The font is Tinos: same glyph widths, same
line breaks, same page geometry as Times New Roman, because it was commissioned as a
metric-compatible substitute. See §3 for why it is not literally Times New Roman and what
the options are.

---

## 1. Help must arrive before failure, not after it

**Why it matters.** Confusion accumulates, and each failed attempt lowers the threshold for
the next one (`docs/llm-wiki/wiki/clusters/dezorientaciya-i-porog-zameshatelstva.md`). A
support tool that requires a visible failure before it helps is charging the user in the one
currency they cannot spare. The correct posture is to help *early and cheaply*, and let the
reader decline help, not demand it.

**Fix A — drop the flash rung from the failure path.**

In `focus/FocusLadder.kt`, `onMisread` currently walks `FocusStep.entries` in order. Change
it so the first miss lands on `Syllables`, skipping `Sweep`:

```kotlin
private val ESCALATION_ORDER = listOf(
    FocusStep.Syllables,
    FocusStep.Letters,
    FocusStep.Meaning
)

fun FocusLadderState.onMisread(currentWord: String, wordCount: Int): FocusLadderState {
    val nextStep = ESCALATION_ORDER.firstOrNull { it.ordinal > step.ordinal }
        ?: return advance(currentWord, wordCount)
    return copy(step = nextStep)
}
```

Keep `FocusStep.Sweep` in the enum — it stays reachable from Fix B as a *pre-failure* nudge,
which is where it actually belongs. Update `FocusLadderTest.misreadsEscalateOneStepAtATime`
to expect `Syllables → Letters → Meaning`, and
`misreadAtLastStepReleasesTheReaderForward` to need 3 misses instead of 4.

**Fix B — offer help on silence, before any wrong answer.**

Two-stage timeout on the current word, in `FocusReaderScreen.kt`. Add next to the other
`LaunchedEffect`s:

```kotlin
private const val NUDGE_AFTER_MILLIS = 5_000L
private const val OFFER_HELP_AFTER_MILLIS = 9_000L

LaunchedEffect(ladder.wordIndex, ladder.step, isListening) {
    if (ladder.step != FocusStep.Focus) return@LaunchedEffect
    delay(NUDGE_AFTER_MILLIS)
    // Stage 1: re-show the word crisply. Costs the reader nothing, no failure recorded.
    ladder = ladder.onHelpRequested(FocusStep.Sweep)
    delay(OFFER_HELP_AFTER_MILLIS - NUDGE_AFTER_MILLIS)
    // Stage 2: syllables appear on their own. Still not a failure.
    ladder = ladder.onHelpRequested(FocusStep.Syllables)
}
```

Critical detail: timeout escalation must **not** mark the word as a clean-read miss. It
already does not — `onHelpRequested` only raises `step`; `recentCleanReads` is written in
`advance()`. But note the consequence: a word helped by timeout still counts as
non-clean when it advances, which is correct — the mastery metric should reflect that
help was used, however it arrived.

Both constants belong in `FocusLadder.kt` next to `SWEEP_FLASH_MILLIS`, and should
eventually be user-tunable in Settings: 5 s is impatient for some readers and glacial for
others.

**Fix C — make the Help button unmissable.**

It exists but reads as secondary. In the control `Row`, give it equal visual weight and a
label that promises support rather than admits defeat:

- Swap `OutlinedButton` → `FilledTonalButton`.
- Add `Icons.Default.Lightbulb` via `Icon` + `Spacer(Modifier.width(8.dp))`.
- Russian copy: «Подскажи» rather than «Помощь» — the imperative asks a companion for
  something, the noun labels a failure state.
- Add `Modifier.heightIn(min = 56.dp)` on both buttons. Current default height is under
  the 48 dp touch-target floor once padding is accounted for on small screens.

---

## 2. Delete the end-of-scene image check

**Why it matters.** It is a comprehension quiz wearing a friendly face. It stops the reading
flow at the moment flow has just been achieved, and it asks the reader to evaluate their own
understanding — a metacognitive task that is expensive for everyone and disproportionately
so under load. It came from the Davis "Picture at Punctuation" technique, which in the
source is a *facilitator-led exercise with a human present*, not an unprompted modal.

**Fix.** Remove entirely from `focus/FocusReaderScreen.kt`:

- the `sceneCheckIndex` state,
- the scene-boundary block inside `finishWord`,
- the `ScenePanel` composable and its call site.

Then remove the now-dead strings from all three `strings.xml` files:
`focus_scene_question`, `focus_scene_saw_it`, `focus_scene_unclear`.

**Keep `FocusScenes.kt` and `sceneIndex` as they are.** Scene boundaries are still needed:
they are the natural resume point for a session and the right unit for a future
"read this sentence again" affordance. Only the interruption goes. `FocusScenesTest` stays
green untouched.

Also update the README's Focus Reading section — it currently advertises the removed
behaviour.

---

## 3. Times New Roman — the licensing wall, and three ways through it

**The situation, plainly.** Times New Roman is proprietary, owned by Monotype and licensed
to Microsoft. Redistributing `times.ttf` inside an APK is a licence violation regardless of
the fact that it sits on every Windows machine. This is not a technical limitation to
engineer around; extracting it from a Windows install and shipping it is exactly the
prohibited act. I will not do it, and neither should the team.

The reason the current build already satisfies the *intent* of the requirement: Tinos was
commissioned as a metric-compatible Times substitute. Identical advance widths, identical
line-break positions, identical page geometry, nearly identical letterforms. A reader is not
going to perceive a difference; a typographer with a loupe would.

**Three routes, pick one:**

| Route | What it costs | What you get |
|---|---|---|
| **A. Keep Tinos** (current) | nothing | Times metrics, OFL, legal, done |
| **B. License Times New Roman** | Monotype app-embedding licence, per-title, quote required | the literal font, legally |
| **C. Liberation Serif** | nothing | same as A, different vendor lineage |

**If Route B is chosen**, the swap is a two-file drop-in — the code needs no change at all,
which is exactly why it was structured this way:

```bash
cp <licensed>/times.ttf  app/src/main/res/font/sulu_serif_regular.ttf
cp <licensed>/timesbd.ttf app/src/main/res/font/sulu_serif_bold.ttf
```

Then re-run the coverage check from `docs/superpowers/notes/focus-font-license.md` —
Microsoft's Times New Roman must be verified for the Kazakh letters ә ғ қ ң ө ұ ү һ і before
shipping, since older cuts of it do not carry the full Cyrillic Extended range and would
render tofu boxes on exactly the text this app exists to read. Update the licence note with
the purchased licence reference.

**Do not** rename the resource to `times_new_roman.ttf` or label the font "Times New Roman"
in the UI while Route A is in effect.

---

## 4. True blur below Android 12

**Why it matters.** `Modifier.blur` is a documented no-op below API 31, so the current
low-contrast-grey fallback is what those devices get, and the review is right that it does
not solve visual crowding — faint text is still *shaped* text, and shape is most of what
crowds. On `minSdk = 24` this is a large share of the target install base for
budget Android hardware in Kazakhstan.

**Recommended fix: `ScriptIntrinsicBlur`, gated to API < 31.**

RenderScript is deprecated as of API 31 — which is precisely the API where we stop needing
it. On 24–30 it is present, hardware-accelerated, and requires no dependency and no
`build.gradle` change. Deprecation is irrelevant to code that never runs above 30.

Architecture note: do **not** blur 200 `Text` nodes individually the way the current build
does — that was flagged as a known ceiling in `FocusReaderScreen.kt` and this refactor is
the moment to fix it. Blur the whole paragraph **once** as a bitmap, then draw the sharp
focus word on top.

Create `app/src/main/java/com/example/sulu_read/focus/LegacyBlur.kt`:

```kotlin
package com.example.sulu_read.focus

import android.content.Context
import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur

private const val MAX_RENDERSCRIPT_RADIUS = 25f

/**
 * Gaussian blur for API 24-30, where Modifier.blur is a no-op. RenderScript is deprecated
 * from API 31 onward, which is exactly where this stops being called — the deprecation
 * does not apply to any code path that reaches here.
 */
fun blurBitmap(context: Context, source: Bitmap, radius: Float): Bitmap {
    val output = Bitmap.createBitmap(source.width, source.height, source.config ?: Bitmap.Config.ARGB_8888)
    val renderScript = RenderScript.create(context)
    try {
        val input = Allocation.createFromBitmap(source)
        val result = Allocation.createFromBitmap(output)
        ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript)).apply {
            setRadius(radius.coerceIn(1f, MAX_RENDERSCRIPT_RADIUS))
            setInput(input)
            forEach(result)
        }
        result.copyTo(output)
        input.destroy()
        result.destroy()
    } finally {
        renderScript.destroy()
    }
    return output
}
```

Wiring in `FocusReaderScreen.kt` — record the text block into a `Picture`, blur the raster,
draw it, then draw the focus word live on top:

```kotlin
val picture = remember { android.graphics.Picture() }

Box(modifier = Modifier.fillMaxWidth()) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // API 31+: keep the existing container-level Modifier.blur path.
        TextBlock(words, focusIndex = ladder.wordIndex, modifier = Modifier.blur(BLUR_RADIUS_DP.dp))
    } else {
        TextBlock(
            words,
            focusIndex = ladder.wordIndex,
            modifier = Modifier.drawWithCache {
                val width = size.width.toInt()
                val height = size.height.toInt()
                onDrawWithContent {
                    val canvas = picture.beginRecording(width, height)
                    draw(this@drawWithCache, layoutDirection, androidx.compose.ui.graphics.Canvas(canvas), size) {
                        this@onDrawWithContent.drawContent()
                    }
                    picture.endRecording()

                    val raster = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    android.graphics.Canvas(raster).drawPicture(picture)
                    drawImage(blurBitmap(context, raster, BLUR_RADIUS_DP.toFloat()).asImageBitmap())
                }
            }
        )
    }

    // The one sharp word, drawn over the blurred layer in both branches.
    FocusWordOverlay(word = words.getOrNull(ladder.wordIndex), offset = focusWordOffset)
}
```

`focusWordOffset` comes from `Modifier.onGloballyPositioned` on the focus word inside
`TextBlock`, stored in a `mutableStateOf(Offset.Zero)`.

Cost control — the raster must not be rebuilt every frame:

- Key the blurred bitmap on `words` only, never on `ladder.wordIndex`. The blurred layer is
  *identical* for every word in the text; only the overlay moves.
- Recycle the previous bitmap in a `DisposableEffect` keyed the same way.
- Cap the raster at the visible viewport; a full 400-word page rasterised at full resolution
  will blow the bitmap budget on a 1 GB device.

**Fallback if RenderScript proves unreliable on a specific OEM ROM:** downsample-and-upsample.
Draw the raster at 1/8 scale with `Bitmap.createScaledBitmap(..., filter = true)`, then scale
it back up. Cheap, no dependency, and at that ratio it is visually indistinguishable from a
real Gaussian for this purpose.

**Verify on API 24, 28, and 30 emulators** before calling this done, and hold the same bar
the requirement implies: from 40 cm, the surrounding words must be unreadable, but the
paragraph shape must remain visible.

---

## 5. Cloud speech recognition for Kazakh

**Why it matters.** `SpeechRecognizer` availability for `kk-KZ` is decided by whichever
Google app version the OEM shipped. On a budget device in the target market, the voice gate
— the core mechanic — may simply never work, and the reader silently gets a demoted product.

**Recommendation: Yandex SpeechKit as primary for Kazakh, Google Cloud STT for Russian,
on-device recognizer as a zero-latency fast path where it works.**

Reasoning: Yandex has the strongest Kazakh acoustic coverage of the accessible commercial
options and is the closest infrastructure to the users. Google Cloud STT also lists Kazakh
and is the better pick for Russian and for mixed-script textbook material.

**Non-negotiable architectural constraint: the API key never goes in the APK.** A key in an
Android app is public — it is trivially extractable from any APK, and speech APIs bill per
minute. Route audio through the existing FastAPI backend, which already holds the Gemini and
Groq credentials and already has a provider-fallback pattern to copy.

**Backend** — new endpoint, mirroring the shape of `backend/app/routers/ai.py`:

```
POST /v1/speech/recognize
  multipart: audio (OGG/Opus or WAV 16 kHz mono), language ("kk" | "ru")
  200: {"hypotheses": ["қала", "кала", ...]}
```

Implement in `backend/app/services/speech_service.py`, reusing the exact provider-fallback
structure of `ai_generation_service.py`: primary provider from env, fallback on retryable
failure, `SAFE_*_ERROR` for anything user-facing. Env vars `YANDEX_SPEECHKIT_API_KEY`,
`YANDEX_FOLDER_ID`, `GOOGLE_STT_CREDENTIALS_JSON` — added to `.env.example` only, never
committed.

Return **all** hypotheses, not just the top one. `isSpokenWordAccepted` in
`focus/WordMatch.kt` already scans every alternative, and that is a meaningful accuracy win
on single-word utterances — discarding the alternatives would throw it away.

**Client** — extend `focus/SpeechGate.kt` with a strategy behind the existing interface, so
`FocusReaderScreen` needs no change:

1. `SpeechRecognizer.isRecognitionAvailable()` and the locale is supported → use it. Free,
   offline-capable, ~0 latency.
2. Otherwise → `MediaRecorder` to a temp `.ogg` (`AudioSource.MIC`,
   `OutputFormat.OGG` / `AudioEncoder.OPUS` on API 29+, `.m4a` AAC below), POST to the
   backend, feed the returned hypotheses into the same `onResult` callback.
3. Network failure or backend error → the existing self-check button. **Keep this rung.**
   It is what makes the app usable on a plane, on a dead connection, and for a mute or
   speech-impaired user, and no cloud API removes the need for it.

Cloud round-trip is 300–800 ms on a decent connection. Show the listening indicator
immediately on button press, not on response — perceived latency is the whole difference
between a pacing mechanic that feels alive and one that feels broken.

Budget before committing: single-word utterances are short but frequent, roughly one call
per word read. Model the cost at, say, 300 words per session per daily user against current
published per-minute pricing for both providers, and check whether minimum billing
increments (some providers round every request up to 15 s) dominate the bill — for one-word
audio they usually do. If they do, batch is not an option here, so the on-device fast path
in step 1 stops being an optimisation and becomes the cost control.

---

## 6. The reading card has no height limit (found on the emulator, not yet fixed)

**Symptom.** `BlurredTextBlock` renders every word of the adapted text in one `FlowRow` with
no maximum height, inside the reading screen's `verticalScroll` column. A Wikipedia article
produces a card several thousand pixels tall. Everything the reader needs — the "read the
word aloud" prompt, the syllable and letter hints, the listen button, the help button — sits
*below* that card. On a phone screen the reader sees blurred text and nothing else, and would
have to scroll past the entire article to discover that controls exist at all.

This was invisible in unit tests and invisible in the earlier hand-check, because a short
pasted paragraph fits on one screen. It only appears with a real document, which is the only
kind this app is for.

**Why it matters more than the items above.** Every fix in §1 makes help easier to reach.
This defect makes help unreachable. It also breaks the core loop: the focus word can be
scrolled off-screen entirely while the reader is being asked to say it.

**Two candidate fixes — this is a design decision, not a mechanical one:**

- **A. Cap the card and auto-follow the focus word.** `heightIn(max = ~320.dp)` on the block
  plus an internal scroll that keeps the focus word centred as the index advances. Preserves
  the "see the shape of the whole page" property the blur exists to provide. Costs an
  auto-scroll animation, which must be instant rather than smooth — animated motion in the
  reading area is a documented disorientation trigger.
- **B. Move the controls above the card.** One-line change, zero new behaviour, but it puts
  the buttons between the progress line and the text, which reads oddly and pushes the text
  itself further down.

Recommend A, with the scroll jump non-animated.

## Suggested order

1. §2 (delete scene check) — pure deletion, largest UX gain per line changed.
2. §1 (help timing) — small, high impact, fully unit-testable.
3. §4 (blur) — largest engineering item; also fixes the known per-word blur perf ceiling.
4. §5 (speech) — backend + client, needs provider accounts and a cost decision first.
5. §3 (font) — a business decision, not an engineering task. One `cp` once decided.
