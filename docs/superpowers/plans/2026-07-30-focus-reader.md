# Focus Reader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Focus" reading mode to the Sulu Read Android app where the whole OCR'd text is blurred except one serif-rendered word, the highlight advances only when the reader says that word aloud, and each stumble peels off one layer of support (flash → syllables → letter names → meaning) without ever trapping the reader on a word.

**Architecture:** All decision logic lives in four pure-Kotlin files under `app/src/main/java/com/example/sulu_read/focus/` (word matching, letter names, scene splitting, ladder state machine) with JVM unit tests. Android-specific pieces are thin adapters: one `SpeechRecognizer` wrapper and one Compose screen. No backend changes — OCR (`POST /v1/adapt-image`), syllables (`words[].syllables`), and AI hints (`POST /ai/generate`, `mode="reading_help"`) already exist and are reused as-is.

**Tech Stack:** Kotlin, Jetpack Compose (Material3, BOM 2024.04.01), `android.speech.SpeechRecognizer`, `android.speech.tts.TextToSpeech`, JUnit4 (`app/src/test/`).

**Design spec:** `docs/llm-wiki/wiki/concepts/focus-reading-method.md`. Every non-obvious constant below traces to it; the vault also records which constants are project assumptions rather than source facts.

## Global Constraints

- `minSdk = 24`, `targetSdk = 35`, `compileSdk = 35`, `jvmTarget = "1.8"` — do not raise any of them.
- No new Gradle dependency. Everything needed is already in `app/build.gradle.kts`.
- No backend changes. No new endpoint, no schema change, no new `AiMode` value.
- `Modifier.blur` is API 31+. Every blur call site must degrade on API 24–30 without crashing and without leaving surrounding text readable.
- The bundled serif font must be a freely redistributable font metrically compatible with Times New Roman. Never name it "Times New Roman" in code, resources, or user-visible copy — Microsoft's font cannot be shipped in the APK.
- User-visible copy must not use "коррекция", "лечение", "диагностика", or promise results. Failure copy is neutral ("послушай", "попробуем ещё раз") — never "неверно"/"ошибка". Reason: stress and fear are documented disorientation triggers.
- Every new user-visible string goes into all three of `app/src/main/res/values/strings.xml` (English), `values-ru/strings.xml`, `values-kk/strings.xml`.
- No motion in the reading area: transitions are sharpness and colour only. Words never translate, pulse, or scale.
- Kazakh letter names in `LetterNames.kt` are project data pending native-speaker review — keep the review comment in the file.

---

### Task 1: Spoken-word matcher

Decides whether what the recognizer heard counts as the target word. Tolerates the distortions the source lists for dysgraphia (letter/syllable substitution, omission, addition) and the Kazakh↔Russian letter pairs a recognizer routinely confuses, but rejects pure letter transpositions because order-scrambling is a reading error, not recognizer noise.

**Files:**
- Create: `app/src/main/java/com/example/sulu_read/focus/WordMatch.kt`
- Test: `app/src/test/java/com/example/sulu_read/FocusWordMatchTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `fun normalizeForMatch(raw: String): String`
  - `fun isSpokenWordAccepted(target: String, heardAlternatives: List<String>): Boolean`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/sulu_read/FocusWordMatchTest.kt`:

```kotlin
package com.example.sulu_read

import com.example.sulu_read.focus.isSpokenWordAccepted
import com.example.sulu_read.focus.normalizeForMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusWordMatchTest {
    @Test
    fun stripsCaseAndPunctuation() {
        assertEquals("мама", normalizeForMatch("Мама,"))
        assertEquals("кыстак", normalizeForMatch("«Қыстақ»!"))
    }

    @Test
    fun acceptsExactWord() {
        assertTrue(isSpokenWordAccepted("книга", listOf("книга")))
    }

    @Test
    fun acceptsWordFromAnyAlternative() {
        assertTrue(isSpokenWordAccepted("книга", listOf("кинга", "не книга", "книга")))
    }

    @Test
    fun acceptsFoldedRussianKazakhPairs() {
        assertTrue(isSpokenWordAccepted("ёлка", listOf("елка")))
        assertTrue(isSpokenWordAccepted("қала", listOf("кала")))
        assertTrue(isSpokenWordAccepted("кітап", listOf("кытап")))
        assertTrue(isSpokenWordAccepted("үй", listOf("уй")))
    }

    @Test
    fun acceptsOneSubstitutionInMediumWord() {
        assertTrue(isSpokenWordAccepted("школа", listOf("шкода")))
    }

    @Test
    fun rejectsSubstitutionInShortWord() {
        assertFalse(isSpokenWordAccepted("дом", listOf("том")))
    }

    @Test
    fun acceptsOmittedLetterInLongWord() {
        assertTrue(isSpokenWordAccepted("математика", listOf("матматика")))
    }

    @Test
    fun rejectsTransposedLetters() {
        assertFalse(isSpokenWordAccepted("карандаш", listOf("каранадш")))
    }

    @Test
    fun rejectsDifferentWord() {
        assertFalse(isSpokenWordAccepted("книга", listOf("тетрадь")))
    }

    @Test
    fun rejectsEmptyInput() {
        assertFalse(isSpokenWordAccepted("книга", emptyList()))
        assertFalse(isSpokenWordAccepted("книга", listOf("", "   ")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sulu_read.FocusWordMatchTest"`
Expected: FAIL at compilation — "Unresolved reference: focus".

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/example/sulu_read/focus/WordMatch.kt`:

```kotlin
package com.example.sulu_read.focus

import kotlin.math.min

// Pairs a speech recognizer and a beginning reader confuse routinely. Folding both sides
// through this map means such a swap costs nothing, while a real misreading still costs.
private val FoldedLetters: Map<Char, Char> = mapOf(
    'ё' to 'е',
    'й' to 'и',
    'ъ' to 'ь',
    'қ' to 'к',
    'ғ' to 'г',
    'ң' to 'н',
    'һ' to 'х',
    'ө' to 'о',
    'ұ' to 'у',
    'ү' to 'у',
    'ә' to 'а',
    'і' to 'ы'
)

private const val SHORT_WORD_MAX_LENGTH = 3
private const val MEDIUM_WORD_MAX_LENGTH = 6
private const val MEDIUM_WORD_TOLERANCE = 1
private const val LONG_WORD_TOLERANCE = 2

fun normalizeForMatch(raw: String): String {
    return raw
        .lowercase()
        .filter { it.isLetterOrDigit() }
}

private fun fold(normalized: String): String {
    return normalized.map { character -> FoldedLetters[character] ?: character }.joinToString("")
}

private fun toleranceFor(length: Int): Int = when {
    length <= SHORT_WORD_MAX_LENGTH -> 0
    length <= MEDIUM_WORD_MAX_LENGTH -> MEDIUM_WORD_TOLERANCE
    else -> LONG_WORD_TOLERANCE
}

private fun isTransposition(first: String, second: String): Boolean {
    return first != second && first.toList().sorted() == second.toList().sorted()
}

private fun editDistance(first: String, second: String): Int {
    var previousRow = IntArray(second.length + 1) { it }
    for (firstIndex in 1..first.length) {
        val currentRow = IntArray(second.length + 1)
        currentRow[0] = firstIndex
        for (secondIndex in 1..second.length) {
            val substitutionCost = if (first[firstIndex - 1] == second[secondIndex - 1]) 0 else 1
            currentRow[secondIndex] = min(
                min(currentRow[secondIndex - 1] + 1, previousRow[secondIndex] + 1),
                previousRow[secondIndex - 1] + substitutionCost
            )
        }
        previousRow = currentRow
    }
    return previousRow[second.length]
}

fun isSpokenWordAccepted(target: String, heardAlternatives: List<String>): Boolean {
    val foldedTarget = fold(normalizeForMatch(target))
    if (foldedTarget.isEmpty()) {
        return false
    }

    val tolerance = toleranceFor(foldedTarget.length)
    return heardAlternatives.any { heard ->
        val foldedHeard = fold(normalizeForMatch(heard))
        when {
            foldedHeard.isEmpty() -> false
            foldedHeard == foldedTarget -> true
            // Same letters in a different order is a reading error, not recognizer noise.
            isTransposition(foldedTarget, foldedHeard) -> false
            else -> editDistance(foldedTarget, foldedHeard) <= tolerance
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sulu_read.FocusWordMatchTest"`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/sulu_read/focus/WordMatch.kt app/src/test/java/com/example/sulu_read/FocusWordMatchTest.kt
git commit -m "feat(focus): add spoken-word matcher with RU/KK letter folding"
```

---

### Task 2: Letter names for the spell step

The spell step must say letter **names**, never letter sounds — the source is explicit: «Тебе не нужно произносить звуки слова. Говори только названия букв по одной». Feeding `"м-а-м-а"` to TTS would produce sounds, so the names are an explicit table.

**Files:**
- Create: `app/src/main/java/com/example/sulu_read/focus/LetterNames.kt`
- Test: `app/src/test/java/com/example/sulu_read/FocusLetterNamesTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `fun letterNamesFor(word: String, languageCode: String): List<String>` — `languageCode` accepts `"ru"`, `"kk"`, or anything else (falls back to Russian names for Cyrillic).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/sulu_read/FocusLetterNamesTest.kt`:

```kotlin
package com.example.sulu_read

import com.example.sulu_read.focus.letterNamesFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusLetterNamesTest {
    @Test
    fun namesRussianLetters() {
        assertEquals(listOf("эм", "а", "эм", "а"), letterNamesFor("мама", "ru"))
    }

    @Test
    fun namesRussianLettersIgnoringCaseAndPunctuation() {
        assertEquals(listOf("дэ", "о", "эм"), letterNamesFor("Дом,", "ru"))
    }

    @Test
    fun namesKazakhSpecificLetters() {
        assertEquals(listOf("қа", "а", "эль", "а"), letterNamesFor("қала", "kk"))
    }

    @Test
    fun neverReturnsRawSoundsForUnknownCharacters() {
        // A digit has no letter name; it is spoken as itself rather than dropped.
        assertEquals(listOf("5"), letterNamesFor("5", "ru"))
    }

    @Test
    fun returnsEmptyForBlankWord() {
        assertTrue(letterNamesFor("   ", "ru").isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sulu_read.FocusLetterNamesTest"`
Expected: FAIL at compilation — "Unresolved reference: letterNamesFor".

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/example/sulu_read/focus/LetterNames.kt`:

```kotlin
package com.example.sulu_read.focus

// Letter NAMES, not letter sounds. The spell step of the ladder exists because the source
// method forbids sounding letters out: «Говори только названия букв по одной».
private val RussianLetterNames: Map<Char, String> = mapOf(
    'а' to "а", 'б' to "бэ", 'в' to "вэ", 'г' to "гэ", 'д' to "дэ", 'е' to "е", 'ё' to "ё",
    'ж' to "жэ", 'з' to "зэ", 'и' to "и", 'й' to "и краткое", 'к' to "ка", 'л' to "эль",
    'м' to "эм", 'н' to "эн", 'о' to "о", 'п' to "пэ", 'р' to "эр", 'с' to "эс", 'т' to "тэ",
    'у' to "у", 'ф' to "эф", 'х' to "ха", 'ц' to "цэ", 'ч' to "че", 'ш' to "ша", 'щ' to "ща",
    'ъ' to "твёрдый знак", 'ы' to "ы", 'ь' to "мягкий знак", 'э' to "э", 'ю' to "ю", 'я' to "я"
)

// ponytail: project data, NOT taken from any provided source — the sources never spell out
// Kazakh letter names. Needs a native-speaker / school-textbook review before release;
// the vault records this as an open question (docs/llm-wiki/wiki/lint-report.md).
private val KazakhOnlyLetterNames: Map<Char, String> = mapOf(
    'ә' to "ә", 'ғ' to "ғе", 'қ' to "қа", 'ң' to "ың", 'ө' to "ө",
    'ұ' to "ұ", 'ү' to "ү", 'һ' to "һә", 'і' to "і"
)

fun letterNamesFor(word: String, languageCode: String): List<String> {
    val names = if (languageCode == "kk") RussianLetterNames + KazakhOnlyLetterNames else RussianLetterNames
    return word
        .lowercase()
        .filter { it.isLetterOrDigit() }
        .map { character -> names[character] ?: character.toString() }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sulu_read.FocusLetterNamesTest"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/sulu_read/focus/LetterNames.kt app/src/test/java/com/example/sulu_read/FocusLetterNamesTest.kt
git commit -m "feat(focus): add RU/KK letter-name table for the spell step"
```

---

### Task 3: Scene splitting

The reading area is chunked into "scenes" — the unit at which the reader is asked whether an image formed. The source says to stop «на каждой точке и большинстве запятых»; a comma break is only taken once the scene already has enough words to be worth checking, so short lists do not fragment into meaningless scenes.

**Files:**
- Create: `app/src/main/java/com/example/sulu_read/focus/FocusScenes.kt`
- Test: `app/src/test/java/com/example/sulu_read/FocusScenesTest.kt`

**Interfaces:**
- Consumes: `com.example.sulu_read.SyllableWord` (existing: `data class SyllableWord(val original: String, val syllables: List<String>, val languageHint: String? = null)` in `app/src/main/java/com/example/sulu_read/PremiumReadingScreen.kt`).
- Produces:
  - `data class FocusWord(val display: String, val spoken: String, val syllables: List<String>, val sceneIndex: Int)`
  - `fun buildFocusWords(text: String, backendWords: List<SyllableWord>): List<FocusWord>`
  - `fun sceneCount(words: List<FocusWord>): Int`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/sulu_read/FocusScenesTest.kt`:

```kotlin
package com.example.sulu_read

import com.example.sulu_read.SyllableWord
import com.example.sulu_read.focus.buildFocusWords
import com.example.sulu_read.focus.sceneCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusScenesTest {
    @Test
    fun keepsPunctuationInDisplayButNotInSpokenForm() {
        val words = buildFocusWords("Мама, папа.", emptyList())
        assertEquals(listOf("Мама,", "папа."), words.map { it.display })
        assertEquals(listOf("Мама", "папа"), words.map { it.spoken })
    }

    @Test
    fun startsNewSceneAfterSentenceEnd() {
        val words = buildFocusWords("Кот спит. Пёс бежит.", emptyList())
        assertEquals(listOf(0, 0, 1, 1), words.map { it.sceneIndex })
        assertEquals(2, sceneCount(words))
    }

    @Test
    fun doesNotBreakSceneOnEarlyComma() {
        val words = buildFocusWords("Кот, пёс и мышь спят.", emptyList())
        assertEquals(1, sceneCount(words))
    }

    @Test
    fun breaksSceneOnCommaAfterEnoughWords() {
        val text = "один два три четыре пять шесть, семь восемь."
        val words = buildFocusWords(text, emptyList())
        assertEquals(2, sceneCount(words))
        assertEquals(0, words.first { it.display == "шесть," }.sceneIndex)
        assertEquals(1, words.first { it.display == "семь" }.sceneIndex)
    }

    @Test
    fun takesSyllablesFromBackendWhenAvailable() {
        val backend = listOf(SyllableWord(original = "мама", syllables = listOf("ма", "ма")))
        val words = buildFocusWords("Мама.", backend)
        assertEquals(listOf("ма", "ма"), words.single().syllables)
    }

    @Test
    fun fallsBackToWholeWordWhenBackendHasNoMatch() {
        val words = buildFocusWords("Мама.", emptyList())
        assertEquals(listOf("Мама"), words.single().syllables)
    }

    @Test
    fun skipsTokensWithoutLetters() {
        val words = buildFocusWords("Кот — спит.", emptyList())
        assertEquals(listOf("Кот", "спит."), words.map { it.display })
    }

    @Test
    fun returnsEmptyForBlankText() {
        assertTrue(buildFocusWords("   ", emptyList()).isEmpty())
        assertEquals(0, sceneCount(emptyList()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sulu_read.FocusScenesTest"`
Expected: FAIL at compilation — "Unresolved reference: buildFocusWords".

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/example/sulu_read/focus/FocusScenes.kt`:

```kotlin
package com.example.sulu_read.focus

import com.example.sulu_read.SyllableWord

data class FocusWord(
    val display: String,
    val spoken: String,
    val syllables: List<String>,
    val sceneIndex: Int
)

private const val SENTENCE_END_CHARACTERS = ".!?…:;"
private const val COMMA_CHARACTERS = ","

// A scene is what the reader is asked to picture. Breaking on every comma would produce
// two-word scenes with nothing to picture, so a comma only ends a scene once it has body.
private const val MIN_WORDS_BEFORE_COMMA_BREAK = 6

fun buildFocusWords(text: String, backendWords: List<SyllableWord>): List<FocusWord> {
    val syllablesByWord = backendWords.associate { word ->
        word.original.lowercase() to word.syllables
    }

    val focusWords = mutableListOf<FocusWord>()
    var sceneIndex = 0
    var wordsInScene = 0

    for (token in text.split(Regex("\\s+"))) {
        val display = token.trim()
        if (display.isEmpty() || display.none { it.isLetterOrDigit() }) {
            continue
        }

        val spoken = display.trim { !it.isLetterOrDigit() }
        focusWords += FocusWord(
            display = display,
            spoken = spoken,
            syllables = syllablesByWord[spoken.lowercase()] ?: listOf(spoken),
            sceneIndex = sceneIndex
        )
        wordsInScene += 1

        val lastCharacter = display.last()
        val endsScene = lastCharacter in SENTENCE_END_CHARACTERS ||
            (lastCharacter in COMMA_CHARACTERS && wordsInScene >= MIN_WORDS_BEFORE_COMMA_BREAK)
        if (endsScene) {
            sceneIndex += 1
            wordsInScene = 0
        }
    }

    return focusWords
}

fun sceneCount(words: List<FocusWord>): Int {
    return words.map { it.sceneIndex }.distinct().size
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sulu_read.FocusScenesTest"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/sulu_read/focus/FocusScenes.kt app/src/test/java/com/example/sulu_read/FocusScenesTest.kt
git commit -m "feat(focus): split adapted text into scene-tagged focus words"
```

---

### Task 4: Ladder state machine

The five-step ladder, the pause suggestion, the trigger-word list, and the adaptive TTS rate. Pure data in, pure data out — no Android types, so it is fully unit-tested.

Steps: 0 Focus · 1 Sweep (200 ms flash) · 2 Syllables · 3 Letters · 4 Meaning. Step 4 always releases the reader forward: getting stuck accumulates confusion, which is the thing the whole method is built to avoid.

**Files:**
- Create: `app/src/main/java/com/example/sulu_read/focus/FocusLadder.kt`
- Test: `app/src/test/java/com/example/sulu_read/FocusLadderTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class FocusStep { Focus, Sweep, Syllables, Letters, Meaning }`
  - `data class FocusLadderState(val wordIndex: Int = 0, val step: FocusStep = FocusStep.Focus, val recentCleanReads: List<Boolean> = emptyList(), val consecutiveDeepWords: Int = 0, val triggerWords: List<String> = emptyList(), val suggestPause: Boolean = false)`
  - `fun FocusLadderState.onMisread(currentWord: String, wordCount: Int): FocusLadderState`
  - `fun FocusLadderState.onCorrectRead(currentWord: String, wordCount: Int): FocusLadderState`
  - `fun FocusLadderState.onHelpRequested(minimumStep: FocusStep): FocusLadderState`
  - `fun FocusLadderState.onPauseAcknowledged(): FocusLadderState`
  - `fun FocusLadderState.ttsRate(): Float`
  - `fun FocusLadderState.masteryShare(): Float`
  - Constants: `SWEEP_FLASH_MILLIS = 200L`, `MASTERY_TARGET = 0.8f`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/sulu_read/FocusLadderTest.kt`:

```kotlin
package com.example.sulu_read

import com.example.sulu_read.focus.FocusLadderState
import com.example.sulu_read.focus.FocusStep
import com.example.sulu_read.focus.masteryShare
import com.example.sulu_read.focus.onCorrectRead
import com.example.sulu_read.focus.onHelpRequested
import com.example.sulu_read.focus.onMisread
import com.example.sulu_read.focus.onPauseAcknowledged
import com.example.sulu_read.focus.ttsRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusLadderTest {
    private val wordCount = 40

    @Test
    fun correctReadAdvancesAndResetsStep() {
        val state = FocusLadderState().onCorrectRead("кот", wordCount)
        assertEquals(1, state.wordIndex)
        assertEquals(FocusStep.Focus, state.step)
        assertTrue(state.triggerWords.isEmpty())
    }

    @Test
    fun misreadsEscalateOneStepAtATime() {
        var state = FocusLadderState()
        state = state.onMisread("кот", wordCount)
        assertEquals(FocusStep.Sweep, state.step)
        state = state.onMisread("кот", wordCount)
        assertEquals(FocusStep.Syllables, state.step)
        state = state.onMisread("кот", wordCount)
        assertEquals(FocusStep.Letters, state.step)
        state = state.onMisread("кот", wordCount)
        assertEquals(FocusStep.Meaning, state.step)
        assertEquals(0, state.wordIndex)
    }

    @Test
    fun misreadAtLastStepReleasesTheReaderForward() {
        var state = FocusLadderState()
        repeat(4) { state = state.onMisread("карандаш", wordCount) }
        state = state.onMisread("карандаш", wordCount)
        assertEquals(1, state.wordIndex)
        assertEquals(FocusStep.Focus, state.step)
        assertEquals(listOf("карандаш"), state.triggerWords)
    }

    @Test
    fun wordTakenWithDeepHelpBecomesTriggerWord() {
        var state = FocusLadderState()
        repeat(3) { state = state.onMisread("математика", wordCount) }
        state = state.onCorrectRead("математика", wordCount)
        assertEquals(listOf("математика"), state.triggerWords)
    }

    @Test
    fun threeDeepWordsInARowSuggestAPause() {
        var state = FocusLadderState()
        repeat(3) {
            repeat(3) { state = state.onMisread("слово", wordCount) }
            state = state.onCorrectRead("слово", wordCount)
        }
        assertTrue(state.suggestPause)

        val resumed = state.onPauseAcknowledged()
        assertFalse(resumed.suggestPause)
        assertEquals(0, resumed.consecutiveDeepWords)
    }

    @Test
    fun cleanReadBreaksTheDeepWordStreak() {
        var state = FocusLadderState()
        repeat(3) { state = state.onMisread("слово", wordCount) }
        state = state.onCorrectRead("слово", wordCount)
        state = state.onCorrectRead("дом", wordCount)
        assertEquals(0, state.consecutiveDeepWords)
    }

    @Test
    fun helpJumpsForwardButNeverBackward() {
        val jumped = FocusLadderState().onHelpRequested(FocusStep.Syllables)
        assertEquals(FocusStep.Syllables, jumped.step)

        val held = jumped.onHelpRequested(FocusStep.Sweep)
        assertEquals(FocusStep.Syllables, held.step)
    }

    @Test
    fun rateStartsSlowAndReachesFullSpeedAtTheMasteryTarget() {
        assertEquals(0.6f, FocusLadderState().ttsRate(), 0.001f)

        var slow = FocusLadderState()
        repeat(20) {
            repeat(2) { slow = slow.onMisread("слово", wordCount) }
            slow = slow.onCorrectRead("слово", wordCount)
        }
        assertEquals(0.6f, slow.ttsRate(), 0.001f)

        var fast = FocusLadderState()
        repeat(20) { fast = fast.onCorrectRead("дом", wordCount) }
        assertEquals(1.0f, fast.ttsRate(), 0.001f)
        assertEquals(1.0f, fast.masteryShare(), 0.001f)
    }

    @Test
    fun rateScalesLinearlyBelowTheMasteryTarget() {
        var state = FocusLadderState()
        repeat(10) { state = state.onCorrectRead("дом", wordCount) }
        repeat(10) {
            state = state.onMisread("слово", wordCount)
            state = state.onCorrectRead("слово", wordCount)
        }
        // 10 clean reads out of a 20-word window = 0.5 share = 0.5/0.8 of the way up.
        assertEquals(0.5f, state.masteryShare(), 0.001f)
        assertEquals(0.85f, state.ttsRate(), 0.001f)
    }

    @Test
    fun wordIndexNeverPassesTheEndOfTheText() {
        var state = FocusLadderState(wordIndex = 1)
        state = state.onCorrectRead("конец", 2)
        assertEquals(2, state.wordIndex)
        state = state.onCorrectRead("конец", 2)
        assertEquals(2, state.wordIndex)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sulu_read.FocusLadderTest"`
Expected: FAIL at compilation — "Unresolved reference: FocusLadderState".

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/example/sulu_read/focus/FocusLadder.kt`:

```kotlin
package com.example.sulu_read.focus

import kotlin.math.min

enum class FocusStep { Focus, Sweep, Syllables, Letters, Meaning }

/** The re-show flash of the Sweep step. 200 ms is the one timing the source states outright. */
const val SWEEP_FLASH_MILLIS = 200L

/** Share of clean reads at which the pace reaches full speed. Mirrors the source's 80% mark. */
const val MASTERY_TARGET = 0.8f

private const val MIN_TTS_RATE = 0.6f
private const val MAX_TTS_RATE = 1.0f
private const val ACCURACY_WINDOW = 20
private const val PAUSE_AFTER_DEEP_WORDS = 3

// A word that needed the Letters step or deeper is "deep": worth practising later, and a
// streak of them means today's confusion threshold is low, so offer a break.
private val DEEP_STEPS = setOf(FocusStep.Letters, FocusStep.Meaning)

data class FocusLadderState(
    val wordIndex: Int = 0,
    val step: FocusStep = FocusStep.Focus,
    val recentCleanReads: List<Boolean> = emptyList(),
    val consecutiveDeepWords: Int = 0,
    val triggerWords: List<String> = emptyList(),
    val suggestPause: Boolean = false
)

private fun FocusLadderState.advance(currentWord: String, wordCount: Int): FocusLadderState {
    val wasDeep = step in DEEP_STEPS
    val deepStreak = if (wasDeep) consecutiveDeepWords + 1 else 0
    val shouldPause = deepStreak >= PAUSE_AFTER_DEEP_WORDS
    return copy(
        wordIndex = min(wordIndex + 1, wordCount),
        step = FocusStep.Focus,
        recentCleanReads = (recentCleanReads + (step == FocusStep.Focus)).takeLast(ACCURACY_WINDOW),
        consecutiveDeepWords = if (shouldPause) 0 else deepStreak,
        triggerWords = if (wasDeep) triggerWords + currentWord else triggerWords,
        suggestPause = shouldPause
    )
}

fun FocusLadderState.onCorrectRead(currentWord: String, wordCount: Int): FocusLadderState {
    return advance(currentWord, wordCount)
}

fun FocusLadderState.onMisread(currentWord: String, wordCount: Int): FocusLadderState {
    val nextStep = FocusStep.entries.getOrNull(step.ordinal + 1)
        ?: return advance(currentWord, wordCount)
    return copy(step = nextStep)
}

fun FocusLadderState.onHelpRequested(minimumStep: FocusStep): FocusLadderState {
    if (minimumStep.ordinal <= step.ordinal) {
        return this
    }
    return copy(step = minimumStep)
}

fun FocusLadderState.onPauseAcknowledged(): FocusLadderState {
    return copy(suggestPause = false, consecutiveDeepWords = 0)
}

fun FocusLadderState.masteryShare(): Float {
    if (recentCleanReads.isEmpty()) {
        return 0f
    }
    return recentCleanReads.count { it }.toFloat() / recentCleanReads.size
}

fun FocusLadderState.ttsRate(): Float {
    val progress = min(1f, masteryShare() / MASTERY_TARGET)
    return MIN_TTS_RATE + (MAX_TTS_RATE - MIN_TTS_RATE) * progress
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sulu_read.FocusLadderTest"`
Expected: PASS, 10 tests.

- [ ] **Step 5: Run the whole unit-test suite to check nothing else broke**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, including the pre-existing `NaturalTtsTest`, `AiApiClientTest`, `LanguagePreferenceTest`, `OfflineAttemptQueueTest`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/sulu_read/focus/FocusLadder.kt app/src/test/java/com/example/sulu_read/FocusLadderTest.kt
git commit -m "feat(focus): add hint-ladder state machine with adaptive pace"
```

---

### Task 5: Bundled serif font

The spec demands Times New Roman. Microsoft's font cannot ship in an APK, so bundle a metrically compatible, freely licensed substitute with full Kazakh Cyrillic coverage, and verify that coverage before trusting it.

**Files:**
- Create: `app/src/main/res/font/sulu_serif_regular.ttf` (binary, downloaded)
- Create: `app/src/main/res/font/sulu_serif_bold.ttf` (binary, downloaded)
- Create: `app/src/main/java/com/example/sulu_read/focus/FocusTypography.kt`
- Create: `docs/superpowers/notes/focus-font-license.md`

**Interfaces:**
- Consumes: nothing.
- Produces: `val SuluSerifFontFamily: FontFamily`

- [ ] **Step 1: Download the candidate font**

Tinos is Apache-2.0 and metrically compatible with Times New Roman.

```bash
mkdir -p app/src/main/res/font
curl -fsSL -o app/src/main/res/font/sulu_serif_regular.ttf https://raw.githubusercontent.com/google/fonts/main/apache/tinos/Tinos-Regular.ttf
curl -fsSL -o app/src/main/res/font/sulu_serif_bold.ttf https://raw.githubusercontent.com/google/fonts/main/apache/tinos/Tinos-Bold.ttf
ls -l app/src/main/res/font/
```

Expected: two files, roughly 400–600 KB each.

- [ ] **Step 2: Verify Kazakh glyph coverage before trusting the font**

`fontTools` is a dev-only tool — install it into the existing virtualenv, do **not** add it to `requirements.txt`.

```bash
.venv/Scripts/python -m pip install fonttools
.venv/Scripts/python -c "from fontTools.ttLib import TTFont; f=TTFont('app/src/main/res/font/sulu_serif_regular.ttf'); cps={c for t in f['cmap'].tables for c in t.cmap}; missing=[ch for ch in 'әғқңөұүһіӘҒҚҢӨҰҮҺІёЁ' if ord(ch) not in cps]; print('missing:', missing or 'none')"
```

Expected: `missing: none`.

If any glyph is missing, delete both files and use Liberation Serif instead (also Times-metric, OFL):

```bash
curl -fsSL -o /tmp/liberation.tar.gz https://github.com/liberationfonts/liberation-fonts/releases/download/2.1.5/liberation-fonts-ttf-2.1.5.tar.gz
tar -xzf /tmp/liberation.tar.gz -C /tmp
cp /tmp/liberation-fonts-ttf-2.1.5/LiberationSerif-Regular.ttf app/src/main/res/font/sulu_serif_regular.ttf
cp /tmp/liberation-fonts-ttf-2.1.5/LiberationSerif-Bold.ttf app/src/main/res/font/sulu_serif_bold.ttf
```

Then re-run the coverage check above and record which font won in the note from Step 4.

- [ ] **Step 3: Add the font family**

Create `app/src/main/java/com/example/sulu_read/focus/FocusTypography.kt`:

```kotlin
package com.example.sulu_read.focus

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.sulu_read.R

/**
 * The reading area uses a bundled serif that is metrically compatible with Times New Roman.
 * The Microsoft font itself cannot be redistributed in an APK, so this is a licensed
 * substitute — see docs/superpowers/notes/focus-font-license.md. Do not present it to users
 * as "Times New Roman".
 */
val SuluSerifFontFamily: FontFamily = FontFamily(
    Font(R.font.sulu_serif_regular, FontWeight.Normal),
    Font(R.font.sulu_serif_bold, FontWeight.Bold)
)
```

- [ ] **Step 4: Record the licence**

Create `docs/superpowers/notes/focus-font-license.md`:

```markdown
# Serif font bundled with the Focus Reader

The Focus Reader must render text in a Times New Roman-metric serif (client requirement,
see docs/llm-wiki/wiki/sources/tz-zakazchika-focus-reader.md). Microsoft's Times New Roman
cannot be redistributed inside an APK, so the app bundles a metrically compatible substitute
as `app/src/main/res/font/sulu_serif_{regular,bold}.ttf`.

- Font shipped: Tinos (Steve Matteson) — Apache License 2.0, metrically compatible with
  Times New Roman. Source: https://github.com/google/fonts/tree/main/apache/tinos
- Fallback if coverage of Kazakh Cyrillic ever regresses: Liberation Serif — SIL Open Font
  License 1.1. Source: https://github.com/liberationfonts/liberation-fonts
- Verified glyph coverage: ә ғ қ ң ө ұ ү һ і Ә Ғ Қ Ң Ө Ұ Ү Һ І ё Ё

Never label this font "Times New Roman" in code, resources, or user-visible copy.
```

If Step 2 forced the Liberation fallback, swap the two bullet points so the file records what
actually shipped.

- [ ] **Step 5: Verify the project still builds**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/font app/src/main/java/com/example/sulu_read/focus/FocusTypography.kt docs/superpowers/notes/focus-font-license.md
git commit -m "feat(focus): bundle a Times-metric serif with Kazakh coverage"
```

---

### Task 6: Speech gate

Thin wrapper over `android.speech.SpeechRecognizer`: start listening for one word, hand back every hypothesis, and report honestly when recognition is unavailable so the UI can fall back to a self-check button. No unit test — it is an adapter over framework callbacks with no logic of its own; the logic it feeds lives in Task 1.

**Files:**
- Create: `app/src/main/java/com/example/sulu_read/focus/SpeechGate.kt`
- Modify: `app/src/main/AndroidManifest.xml` (add `RECORD_AUDIO` and the recognition-service query)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `class SpeechGate(context: Context)` with `val isAvailable: Boolean`, `fun listenOnce(languageCode: String, onResult: (List<String>) -> Unit, onUnavailable: () -> Unit)`, `fun cancel()`, `fun release()`

- [ ] **Step 1: Add the microphone permission and recognition query**

In `app/src/main/AndroidManifest.xml`, immediately after the existing
`<uses-permission android:name="android.permission.INTERNET" />` line, add:

```xml
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
```

And immediately after the closing `</uses-permission>` block for
`READ_MEDIA_VISUAL_USER_SELECTED` (that is, before `<application`), add:

```xml
    <queries>
        <intent>
            <action android:name="android.speech.RecognitionService" />
        </intent>
    </queries>
```

Without the `<queries>` element, `SpeechRecognizer.isRecognitionAvailable` returns false on
API 30+ because package visibility is filtered.

- [ ] **Step 2: Write the speech gate**

Create `app/src/main/java/com/example/sulu_read/focus/SpeechGate.kt`:

```kotlin
package com.example.sulu_read.focus

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.sulu_read.domain.model.AppLanguage

private const val MAX_HYPOTHESES = 5

/**
 * Listens for a single spoken word and returns every hypothesis the recognizer offered,
 * so the matcher can accept a word that was only the recognizer's third guess.
 *
 * Recognition is not guaranteed on every device or for every language, so callers must
 * handle [onUnavailable] by falling back to a self-check button. The reading mode has to
 * work with no microphone at all.
 */
class SpeechGate(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun listenOnce(
        languageCode: String,
        onResult: (List<String>) -> Unit,
        onUnavailable: () -> Unit
    ) {
        if (!isAvailable) {
            onUnavailable()
            return
        }

        val activeRecognizer = recognizer
            ?: SpeechRecognizer.createSpeechRecognizer(context)?.also { recognizer = it }
            ?: run {
                onUnavailable()
                return
            }

        activeRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val hypotheses = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.toList()
                    .orEmpty()
                onResult(hypotheses)
            }

            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_NO_MATCH -> onResult(emptyList())
                    else -> onUnavailable()
                }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val locale = AppLanguage.localeFor(languageCode)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_HYPOTHESES)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

        runCatching { activeRecognizer.startListening(intent) }
            .onFailure { onUnavailable() }
    }

    fun cancel() {
        runCatching { recognizer?.cancel() }
    }

    fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Confirm `AppLanguage.localeFor` exists with that signature**

Run: `grep -n "fun localeFor" app/src/main/java/com/example/sulu_read/domain/model/AppLanguage.kt`
Expected: one match returning `Locale`. If the helper is named differently, adjust the call in
`SpeechGate.kt` to whatever the file actually exposes — do not add a duplicate helper.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/example/sulu_read/focus/SpeechGate.kt
git commit -m "feat(focus): add single-word speech gate with availability fallback"
```

---

### Task 7: Focus reader screen

The visible mode: blurred serif text, one sharp highlighted word, ladder rendering, help button, scene image check, pause card.

**Files:**
- Create: `app/src/main/java/com/example/sulu_read/focus/FocusReaderScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-ru/strings.xml`, `app/src/main/res/values-kk/strings.xml`

**Interfaces:**
- Consumes: `FocusWord`, `buildFocusWords`, `sceneCount` (Task 3); `FocusLadderState`, `FocusStep`, `onCorrectRead`, `onMisread`, `onHelpRequested`, `onPauseAcknowledged`, `ttsRate`, `masteryShare`, `SWEEP_FLASH_MILLIS` (Task 4); `SuluSerifFontFamily` (Task 5); `SpeechGate` (Task 6); `isSpokenWordAccepted` (Task 1); `letterNamesFor` (Task 2); existing `AiHelpState`, `applyNaturalVoice`, `speakCompat`, `detectSpeechLanguageCode`.
- Produces: `@Composable fun FocusReaderScreen(text: String, backendWords: List<SyllableWord>, languageCode: String, aiHelpState: AiHelpState, onRequestMeaningHint: (String) -> Unit, onDismissHint: () -> Unit, onCollectTriggerWords: (List<String>) -> Unit, modifier: Modifier = Modifier)`

- [ ] **Step 1: Add the strings**

Append to `app/src/main/res/values/strings.xml` (before `</resources>`):

```xml
    <string name="focus_mode_title">Focus reading</string>
    <string name="focus_mode_enter">Read word by word</string>
    <string name="focus_mode_exit">Back to the whole text</string>
    <string name="focus_listen">Read the word aloud</string>
    <string name="focus_listening">Listening…</string>
    <string name="focus_try_again">Let\'s try once more</string>
    <string name="focus_help">Help</string>
    <string name="focus_i_read_it">I read it</string>
    <string name="focus_mic_unavailable">The microphone is not available, so tap when you have read the word.</string>
    <string name="focus_mic_permission">Allow the microphone so the app can hear you read.</string>
    <string name="focus_step_syllables">Listen to the syllables</string>
    <string name="focus_step_letters">Letter by letter</string>
    <string name="focus_step_meaning">What the word means</string>
    <string name="focus_scene_question">What did you see?</string>
    <string name="focus_scene_saw_it">I saw it</string>
    <string name="focus_scene_unclear">It did not come together</string>
    <string name="focus_pause_title">Let\'s take a short break</string>
    <string name="focus_pause_continue">I am ready</string>
    <string name="focus_progress">Read without help: %1$d%%</string>
    <string name="focus_finished">The text is finished.</string>
    <string name="focus_practise_words">Practise the difficult words</string>
```

Append the Russian copy to `app/src/main/res/values-ru/strings.xml`:

```xml
    <string name="focus_mode_title">Чтение по словам</string>
    <string name="focus_mode_enter">Читать по словам</string>
    <string name="focus_mode_exit">Вернуться ко всему тексту</string>
    <string name="focus_listen">Прочитай слово вслух</string>
    <string name="focus_listening">Слушаю…</string>
    <string name="focus_try_again">Попробуем ещё раз</string>
    <string name="focus_help">Помощь</string>
    <string name="focus_i_read_it">Я прочитал</string>
    <string name="focus_mic_unavailable">Микрофон недоступен — нажимай кнопку, когда прочитаешь слово.</string>
    <string name="focus_mic_permission">Разреши микрофон, чтобы приложение слышало, как ты читаешь.</string>
    <string name="focus_step_syllables">Послушай слоги</string>
    <string name="focus_step_letters">По буквам</string>
    <string name="focus_step_meaning">Что значит слово</string>
    <string name="focus_scene_question">Что ты увидел?</string>
    <string name="focus_scene_saw_it">Увидел</string>
    <string name="focus_scene_unclear">Не сложилось</string>
    <string name="focus_pause_title">Сделаем небольшую паузу</string>
    <string name="focus_pause_continue">Я готов</string>
    <string name="focus_progress">Прочитано без подсказки: %1$d%%</string>
    <string name="focus_finished">Текст закончился.</string>
    <string name="focus_practise_words">Потренировать трудные слова</string>
```

Append the Kazakh copy to `app/src/main/res/values-kk/strings.xml`:

```xml
    <string name="focus_mode_title">Сөзбен оқу</string>
    <string name="focus_mode_enter">Сөзбен оқу</string>
    <string name="focus_mode_exit">Мәтіннің толық түріне қайту</string>
    <string name="focus_listen">Сөзді дауыстап оқы</string>
    <string name="focus_listening">Тыңдап тұрмын…</string>
    <string name="focus_try_again">Тағы бір рет көрейік</string>
    <string name="focus_help">Көмек</string>
    <string name="focus_i_read_it">Оқып шықтым</string>
    <string name="focus_mic_unavailable">Микрофон қолжетімсіз — сөзді оқыған соң түймені бас.</string>
    <string name="focus_mic_permission">Оқығаныңды қолданба естуі үшін микрофонға рұқсат бер.</string>
    <string name="focus_step_syllables">Буындарды тыңда</string>
    <string name="focus_step_letters">Әріппен</string>
    <string name="focus_step_meaning">Сөздің мағынасы</string>
    <string name="focus_scene_question">Не көрдің?</string>
    <string name="focus_scene_saw_it">Көрдім</string>
    <string name="focus_scene_unclear">Түсінікті болмады</string>
    <string name="focus_pause_title">Кішкене үзіліс жасайық</string>
    <string name="focus_pause_continue">Дайынмын</string>
    <string name="focus_progress">Көмексіз оқылды: %1$d%%</string>
    <string name="focus_finished">Мәтін бітті.</string>
    <string name="focus_practise_words">Қиын сөздерді жаттығу</string>
```

- [ ] **Step 2: Write the screen**

Create `app/src/main/java/com/example/sulu_read/focus/FocusReaderScreen.kt`:

```kotlin
package com.example.sulu_read.focus

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.sulu_read.R
import com.example.sulu_read.SyllableWord
import com.example.sulu_read.audio.applyNaturalVoice
import com.example.sulu_read.audio.detectSpeechLanguageCode
import com.example.sulu_read.audio.speakCompat
import com.example.sulu_read.ui.screens.AiHelpState
import kotlinx.coroutines.delay

private val FocusHighlight = Color(0xFFFFE0B2)
private val FocusWordColor = Color(0xFF1A1A1A)
private val BlurredWordColor = Color(0xFF9E9E9E)
private val ScenePanelBackground = Color(0xFFFFFCF4)
private const val BLUR_RADIUS_DP = 6
private const val LEGACY_BLUR_ALPHA = 0.30f
private const val FOCUS_FONT_SIZE_SP = 22
private const val FOCUS_LINE_HEIGHT_SP = 38

/**
 * Blur is only available from API 31. On older devices the surrounding words are muted to a
 * low-contrast grey instead: still visible as paragraph shape, still not readable.
 */
private fun Modifier.focusBlur(): Modifier {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        blur(radius = BLUR_RADIUS_DP.dp)
    } else {
        this
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FocusReaderScreen(
    text: String,
    backendWords: List<SyllableWord>,
    languageCode: String,
    aiHelpState: AiHelpState,
    onRequestMeaningHint: (String) -> Unit,
    onDismissHint: () -> Unit,
    onCollectTriggerWords: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val words = remember(text, backendWords) { buildFocusWords(text, backendWords) }
    var ladder by remember(text) { mutableStateOf(FocusLadderState()) }
    var isListening by remember { mutableStateOf(false) }
    var showTryAgain by remember { mutableStateOf(false) }
    var isFlashing by remember { mutableStateOf(false) }
    var sceneCheckIndex by remember(text) { mutableStateOf<Int?>(null) }
    var micDenied by remember { mutableStateOf(false) }

    val speechGate = remember { SpeechGate(context) }
    var micUnavailable by remember { mutableStateOf(!speechGate.isAvailable) }
    DisposableEffect(speechGate) {
        onDispose { speechGate.release() }
    }

    var textToSpeech by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        val engine = TextToSpeech(context) {}
        textToSpeech = engine
        onDispose {
            engine.stop()
            engine.shutdown()
            textToSpeech = null
        }
    }

    val currentWord = words.getOrNull(ladder.wordIndex)

    fun speak(value: String) {
        val engine = textToSpeech ?: return
        val speechLanguage = detectSpeechLanguageCode(value, languageCode)
        engine.applyNaturalVoice(speechLanguage)
        engine.setSpeechRate(ladder.ttsRate())
        engine.speakCompat(value, TextToSpeech.QUEUE_FLUSH, "focus-${value.hashCode()}")
    }

    fun finishWord(wasCorrect: Boolean) {
        val word = currentWord ?: return
        isListening = false
        ladder = if (wasCorrect) {
            showTryAgain = false
            ladder.onCorrectRead(word.spoken, words.size)
        } else {
            showTryAgain = true
            ladder.onMisread(word.spoken, words.size)
        }

        val advanced = ladder.step == FocusStep.Focus
        if (advanced) {
            val finishedScene = word.sceneIndex
            val nextScene = words.getOrNull(ladder.wordIndex)?.sceneIndex
            if (nextScene != finishedScene) {
                sceneCheckIndex = finishedScene
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        micDenied = !granted
        if (granted) {
            isListening = true
        }
    }

    fun startListening() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        isListening = true
    }

    LaunchedEffect(isListening, ladder.wordIndex, ladder.step) {
        val word = currentWord ?: return@LaunchedEffect
        if (!isListening) {
            return@LaunchedEffect
        }
        speechGate.listenOnce(
            languageCode = detectSpeechLanguageCode(word.spoken, languageCode),
            onResult = { hypotheses ->
                finishWord(isSpokenWordAccepted(word.spoken, hypotheses))
            },
            onUnavailable = {
                isListening = false
                micUnavailable = true
            }
        )
    }

    // Sweep step: hide the word, then flash it sharp for the 200 ms the source specifies.
    LaunchedEffect(ladder.step, ladder.wordIndex) {
        if (ladder.step == FocusStep.Sweep) {
            isFlashing = false
            delay(SWEEP_FLASH_MILLIS)
            isFlashing = true
            delay(SWEEP_FLASH_MILLIS)
            isFlashing = false
        } else {
            isFlashing = false
        }
    }

    // Syllables and Letters steps speak on entry, at the adaptive pace.
    LaunchedEffect(ladder.step, ladder.wordIndex) {
        val word = currentWord ?: return@LaunchedEffect
        when (ladder.step) {
            FocusStep.Syllables -> speak(word.syllables.joinToString(" , "))
            FocusStep.Letters -> {
                speak(letterNamesFor(word.spoken, languageCode).joinToString(" , "))
                delay(SWEEP_FLASH_MILLIS * 4)
                speak(word.spoken)
            }
            FocusStep.Meaning -> {
                onRequestMeaningHint(word.spoken)
                speak(word.spoken)
            }
            else -> Unit
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(
                R.string.focus_progress,
                (ladder.masteryShare() * 100).toInt()
            ),
            style = MaterialTheme.typography.bodyMedium
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White)
                .padding(18.dp)
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                words.forEachIndexed { index, word ->
                    val isCurrent = index == ladder.wordIndex
                    val isVisible = isCurrent &&
                        (ladder.step != FocusStep.Sweep || isFlashing)

                    Text(
                        text = word.display,
                        fontFamily = SuluSerifFontFamily,
                        fontSize = FOCUS_FONT_SIZE_SP.sp,
                        lineHeight = FOCUS_LINE_HEIGHT_SP.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (isVisible) FocusWordColor else BlurredWordColor,
                        modifier = Modifier
                            .then(if (isVisible) Modifier else Modifier.focusBlur())
                            .then(
                                if (isVisible) {
                                    Modifier
                                        .background(FocusHighlight, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 4.dp)
                                } else {
                                    Modifier
                                }
                            )
                    )
                }
            }
        }

        if (ladder.wordIndex >= words.size) {
            Text(text = stringResource(R.string.focus_finished))
            if (ladder.triggerWords.isNotEmpty()) {
                Button(
                    onClick = { onCollectTriggerWords(ladder.triggerWords) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.focus_practise_words))
                }
            }
            return@Column
        }

        Text(
            text = when {
                showTryAgain -> stringResource(R.string.focus_try_again)
                isListening -> stringResource(R.string.focus_listening)
                else -> stringResource(R.string.focus_listen)
            },
            style = MaterialTheme.typography.bodyLarge
        )

        when (ladder.step) {
            FocusStep.Syllables -> SyllableHint(currentWord?.syllables.orEmpty())
            FocusStep.Letters -> Text(
                text = letterNamesFor(currentWord?.spoken.orEmpty(), languageCode)
                    .joinToString(" · "),
                style = MaterialTheme.typography.titleMedium
            )
            FocusStep.Meaning -> MeaningHint(aiHelpState = aiHelpState)
            else -> Unit
        }

        if (micUnavailable || micDenied) {
            Text(
                text = stringResource(
                    if (micDenied) R.string.focus_mic_permission
                    else R.string.focus_mic_unavailable
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (micUnavailable || micDenied) {
                Button(
                    onClick = { finishWord(wasCorrect = true) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(R.string.focus_i_read_it))
                }
            } else {
                Button(
                    onClick = { startListening() },
                    enabled = !isListening,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(R.string.focus_listen))
                }
            }

            OutlinedButton(
                onClick = {
                    ladder = ladder.onHelpRequested(
                        when (ladder.step) {
                            FocusStep.Focus, FocusStep.Sweep -> FocusStep.Syllables
                            FocusStep.Syllables -> FocusStep.Letters
                            else -> FocusStep.Meaning
                        }
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(text = stringResource(R.string.focus_help))
            }
        }
    }

    val checkedScene = sceneCheckIndex
    if (checkedScene != null) {
        ScenePanel(
            onSawIt = { sceneCheckIndex = null },
            onUnclear = {
                sceneCheckIndex = null
                val hardestWord = words
                    .filter { it.sceneIndex == checkedScene }
                    .maxByOrNull { it.spoken.length }
                    ?.spoken
                if (hardestWord != null) {
                    onRequestMeaningHint(hardestWord)
                }
            }
        )
    }

    if (ladder.suggestPause) {
        PausePanel(onContinue = { ladder = ladder.onPauseAcknowledged() })
    }

    if (aiHelpState is AiHelpState.Success || aiHelpState is AiHelpState.Error) {
        TextButton(onClick = onDismissHint) {
            Text(text = stringResource(R.string.focus_mode_exit))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyllableHint(syllables: List<String>) {
    val palette = listOf(Color(0xFF1A237E), Color(0xFF8A5A00))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        syllables.forEachIndexed { index, syllable ->
            Text(
                text = syllable,
                fontFamily = SuluSerifFontFamily,
                fontSize = FOCUS_FONT_SIZE_SP.sp,
                fontWeight = FontWeight.Bold,
                color = palette[index % palette.size]
            )
        }
    }
}

@Composable
private fun MeaningHint(aiHelpState: AiHelpState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ScenePanelBackground)
            .padding(14.dp)
    ) {
        when (aiHelpState) {
            is AiHelpState.Loading -> CircularProgressIndicator()
            is AiHelpState.Success -> Text(
                text = aiHelpState.result,
                style = MaterialTheme.typography.bodyLarge
            )
            is AiHelpState.Error -> Text(
                text = aiHelpState.message,
                style = MaterialTheme.typography.bodyMedium
            )
            AiHelpState.Idle -> Text(
                text = stringResource(R.string.focus_step_meaning),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ScenePanel(onSawIt: () -> Unit, onUnclear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ScenePanelBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.focus_scene_question),
            style = MaterialTheme.typography.titleMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSawIt) {
                Text(text = stringResource(R.string.focus_scene_saw_it))
            }
            OutlinedButton(onClick = onUnclear) {
                Text(text = stringResource(R.string.focus_scene_unclear))
            }
        }
    }
}

@Composable
private fun PausePanel(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ScenePanelBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.focus_pause_title),
            style = MaterialTheme.typography.titleMedium
        )
        Button(onClick = onContinue) {
            Text(text = stringResource(R.string.focus_pause_continue))
        }
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If `FlowRow` needs a different opt-in in this Compose version,
match the annotation already used in `PremiumReadingScreen.kt` (`@OptIn(ExperimentalLayoutApi::class)`).

- [ ] **Step 4: Verify translations are complete**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL with no `MissingTranslation` failure. If lint reports a missing
key, add it to the locale file that lacks it — never suppress the check.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/sulu_read/focus/FocusReaderScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-ru/strings.xml app/src/main/res/values-kk/strings.xml
git commit -m "feat(focus): add blurred word-by-word focus reading screen"
```

---

### Task 8: Wire the mode into the reader and document it

The Focus mode becomes a toggle on the existing reading screen, and the meaning hint reuses
the existing AI plumbing with a prompt shaped for a struggling reader.

**Files:**
- Modify: `app/src/main/java/com/example/sulu_read/domain/repository/AiRepository.kt` (add one method)
- Modify: `app/src/main/java/com/example/sulu_read/ui/screens/AiHelpViewModel.kt` (add one method)
- Modify: `app/src/main/java/com/example/sulu_read/MainActivity.kt` (`ReadingScreen`, lines 791–872)
- Modify: `app/src/main/java/com/example/sulu_read/ui/screens/ReaderScreen.kt`
- Modify: `README.md`

**Interfaces:**
- Consumes: `FocusReaderScreen` (Task 7).
- Produces: `AiRepository.hintForWordWithAi(word: String, languageCode: String): AiGenerateResponseDto`, `AiHelpViewModel.hintForWord(word: String, languageCode: String)`.

- [ ] **Step 1: Add the word-hint prompt to the repository**

In `app/src/main/java/com/example/sulu_read/domain/repository/AiRepository.kt`, add this method
after `explainTextWithAi`:

```kotlin
    suspend fun hintForWordWithAi(word: String, languageCode: String): AiGenerateResponseDto {
        return generateAiHelp(
            task = "A child with dyslexia is stuck on one word while reading aloud. " +
                "Answer in at most two very short lines, in the student's language. " +
                "Line 1: a concrete sensory clue to what the word means, the kind of thing " +
                "you could picture or touch. Line 2: one everyday synonym. " +
                "No definitions, no grammar terms, no encouragement, no extra words.",
            text = word,
            languageCode = languageCode,
            mode = "reading_help"
        )
    }
```

- [ ] **Step 2: Expose it on the view model**

In `app/src/main/java/com/example/sulu_read/ui/screens/AiHelpViewModel.kt`, add this method
after `explainTextWithAi`:

```kotlin
    fun hintForWord(word: String, languageCode: String) {
        viewModelScope.launch {
            _state.value = AiHelpState.Loading
            _state.value = runCatching {
                repository.hintForWord(word, languageCode)
            }.fold(
                onSuccess = { AiHelpState.Success(it) },
                onFailure = { AiHelpState.Error(it.message ?: DEFAULT_AI_ERROR) }
            )
        }
    }
```

- [ ] **Step 3: Add the matching repository facade method**

`SuluReadRepository` is what the view model talks to, so it needs the passthrough too. In
`app/src/main/java/com/example/sulu_read/domain/repository/SuluReadRepository.kt`, add this
method immediately after `explainTextWithAi` (which ends at line 227), matching its DTO
unwrapping exactly:

```kotlin
    suspend fun hintForWord(word: String, languageCode: String): String {
        val response = aiRepository.hintForWordWithAi(word, languageCode)
        if (!response.success) {
            throw IllegalStateException(response.error ?: "AI help is unavailable.")
        }
        return response.result?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("AI returned an empty response.")
    }
```

- [ ] **Step 4: Add the toggle to the reading screen**

In `app/src/main/java/com/example/sulu_read/MainActivity.kt`, add these imports to the
existing import block:

```kotlin
import com.example.sulu_read.focus.FocusReaderScreen
```

Then in `ReadingScreen` (currently line 791), add the mode state right after the
`readerDisplayPreferences` declaration:

```kotlin
    var isFocusMode by rememberSaveable(state.adaptedText) { mutableStateOf(false) }
```

Replace the existing `PremiumReadingScreen(...)` call (currently lines 856–870) with:

```kotlin
        TextButton(onClick = { isFocusMode = !isFocusMode }) {
            Text(
                text = stringResource(
                    if (isFocusMode) R.string.focus_mode_exit else R.string.focus_mode_enter
                )
            )
        }

        if (isFocusMode) {
            FocusReaderScreen(
                text = state.originalText.ifBlank { state.adaptedText },
                backendWords = state.words,
                languageCode = languageCode,
                aiHelpState = aiHelpState,
                onRequestMeaningHint = onRequestWordHint,
                onDismissHint = onDismissAiHelp,
                onCollectTriggerWords = onCreateTrainingFromText
            )
        } else {
            PremiumReadingScreen(
                text = state.adaptedText,
                backendWords = state.words,
                onSimplifyText = { source -> repository.simplify(source, languageCode) },
                aiHelpState = aiHelpState,
                onExplainTextWithAi = onExplainTextWithAi,
                onDismissAiHelp = onDismissAiHelp,
                languageCode = languageCode,
                readerDisplayPreferences = readerDisplayPreferences,
                onReaderDisplayPreferencesChange = { preferences ->
                    coroutineScope.launch {
                        repository.saveReaderDisplayPreferences(preferences)
                    }
                }
            )
        }
```

Note the Focus mode is fed `state.originalText` (punctuation intact) rather than
`state.adaptedText` (which carries syllable hyphens) — scene splitting needs real punctuation.

- [ ] **Step 5: Thread the new callback through**

`ReadingScreen` needs a new parameter. Add it to its signature after `onExplainTextWithAi`:

```kotlin
    onRequestWordHint: (String) -> Unit,
```

Add the same parameter to `SuluReadRoute` (line 344) after `onExplainTextWithAi`:

```kotlin
    onRequestWordHint: (String) -> Unit = {},
```

and pass it down at the `ReadingScreen(...)` call site inside `SuluReadRoute`.

Then in `app/src/main/java/com/example/sulu_read/ui/screens/ReaderScreen.kt`, wire the view
model method into the route:

```kotlin
    SuluReadRoute(
        modifier = modifier,
        repository = repository,
        languageCode = languageCode,
        onCreateTrainingFromText = onCreateTraining,
        aiHelpState = aiState,
        onExplainTextWithAi = { text -> aiViewModel.explainTextWithAi(text, languageCode) },
        onRequestWordHint = { word -> aiViewModel.hintForWord(word, languageCode) },
        onDismissAiHelp = aiViewModel::clearAiHelp
    )
```

- [ ] **Step 6: Build and run the full test suite**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all unit tests pass (33 tests: 10 word-match, 5 letter-name,
8 scene, 10 ladder, plus the pre-existing suites).

- [ ] **Step 7: Document the mode in the README**

In `README.md`, after the `## Exercise Types` section, add:

```markdown
## Focus Reading Mode

The reader screen has a word-by-word mode. The whole text is rendered in a bundled
Times-metric serif and blurred except the current word, which is sharp and highlighted.
The highlight advances when the reader says the word aloud; each miss peels off one layer
of support — a 200 ms re-flash, coloured syllables, letter names, then a short AI meaning
hint — and the last layer always releases the reader forward rather than trapping them.
Speech pacing starts slow and speeds up as the share of unaided reads approaches 80%.
Words that needed deep help are collected and can be sent straight into the exercise
generator.

The mode works without a microphone: if speech recognition is unavailable or the permission
is declined, the gate becomes a self-check button.

Design notes and their sources: `docs/llm-wiki/wiki/concepts/focus-reading-method.md`.
The bundled font and its licence: `docs/superpowers/notes/focus-font-license.md`.
```

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/sulu_read/MainActivity.kt app/src/main/java/com/example/sulu_read/ui/screens/ReaderScreen.kt app/src/main/java/com/example/sulu_read/ui/screens/AiHelpViewModel.kt app/src/main/java/com/example/sulu_read/domain/repository/AiRepository.kt app/src/main/java/com/example/sulu_read/domain/repository/SuluReadRepository.kt README.md
git commit -m "feat(focus): wire focus reading mode into the reader screen"
```

---

## Deliberately out of scope

- **Icon-based meaning hints.** The spec lists the meaning channel as "a short, simple audio cue
  **or** visual icon". The audio cue is delivered (the word and its syllables are spoken at the
  adaptive pace), and the AI clue is a concrete sensory image in words. A word→icon asset
  library is a separate piece of work with its own sourcing and licensing; add it when the
  audio cue measurably falls short.
- **Backend persistence of Focus-mode metrics.** Mastery share is shown live and trigger words
  flow into the existing exercise generator, but nothing new is written to `/v1/progress`.
  Add it when there is a screen that needs the history.

## Open risks the implementer must not paper over

1. **Kazakh speech recognition may be absent** on the test device. That is not a bug to fix in
   this plan — it is why `onUnavailable` and the self-check button exist. Verify the fallback
   path works by revoking the microphone permission and reading a text end to end.
2. **Kazakh letter names are unreviewed project data.** Ship the mode, keep the comment, and
   raise the review before any release that markets the spell step.
3. **`Modifier.blur` is a no-op below API 31.** Check the muted-grey fallback on an API 24–30
   emulator; if surrounding words are still readable, lower the fallback contrast rather than
   raising blur.
4. **The 200 ms flash is at the edge of what a frame budget allows.** If the flash is not
   visible on a slow device, keep the constant and lengthen only the gap around it — the
   200 ms figure is the one timing the source states outright.
