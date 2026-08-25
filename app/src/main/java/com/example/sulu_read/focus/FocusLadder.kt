package com.example.sulu_read.focus

import kotlin.math.min

enum class FocusStep { Focus, Sweep, Letters, Meaning }

/** The re-show flash of the Sweep step. 200 ms is the one timing the source states outright. */
const val SWEEP_FLASH_MILLIS = 200L

/** Silence on the current word before the word is re-shown crisply, unprompted. */
const val NUDGE_AFTER_MILLIS = 5_000L

/** Silence before the letters of the word are read out on their own. Not a failure. */
const val OFFER_HELP_AFTER_MILLIS = 9_000L

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

/**
 * The reader moved the focus themselves — the next word, or any word they tapped.
 *
 * Moving on is what finishes a word, so that is where the word is scored and where a word that
 * needed deep help is collected for later practice. Going back to re-read scores nothing: the
 * word behind is not finished, it is being started again, and counting it would let a reader
 * inflate the pace by tapping backwards.
 */
fun FocusLadderState.onFocusMoved(
    target: Int,
    currentWord: String,
    wordCount: Int
): FocusLadderState {
    val clamped = target.coerceIn(0, wordCount)
    if (clamped == wordIndex) {
        return this
    }
    if (clamped < wordIndex) {
        // Nothing is scored, but the word being left is still collected when it needed deep
        // help. A reader turning back is the plainest signal there is that a word was hard, and
        // dropping the step without collecting it lost exactly the words this list is for.
        return copy(
            wordIndex = clamped,
            step = FocusStep.Focus,
            triggerWords = if (step in DEEP_STEPS) triggerWords + currentWord else triggerWords
        )
    }
    return advance(currentWord, wordCount).copy(wordIndex = clamped)
}

fun FocusLadderState.onHelpRequested(minimumStep: FocusStep): FocusLadderState {
    if (minimumStep.ordinal <= step.ordinal) {
        return this
    }
    return copy(step = minimumStep)
}

/**
 * Sweep is a momentary re-show, not a resting state: once the flash is over the word must go
 * back to being enlarged and highlighted, or the reader loses their place entirely. No-op if
 * the reader climbed past Sweep while the flash was running.
 */
fun FocusLadderState.onNudgeFinished(): FocusLadderState {
    if (step != FocusStep.Sweep) {
        return this
    }
    return copy(step = FocusStep.Focus)
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
