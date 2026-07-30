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
