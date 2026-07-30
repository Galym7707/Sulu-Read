package com.example.sulu_read

import com.example.sulu_read.focus.FocusLadderState
import com.example.sulu_read.focus.FocusStep
import com.example.sulu_read.focus.masteryShare
import com.example.sulu_read.focus.onCorrectRead
import com.example.sulu_read.focus.onHelpRequested
import com.example.sulu_read.focus.onMisread
import com.example.sulu_read.focus.onNudgeFinished
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
    fun firstMisreadGoesStraightToSyllables() {
        // A 200 ms re-flash of a word the reader has just failed teaches them nothing.
        // The flash is a pre-failure nudge only; failure buys real support immediately.
        val state = FocusLadderState().onMisread("кот", wordCount)
        assertEquals(FocusStep.Syllables, state.step)
    }

    @Test
    fun misreadsEscalateOneStepAtATime() {
        var state = FocusLadderState()
        state = state.onMisread("кот", wordCount)
        assertEquals(FocusStep.Syllables, state.step)
        state = state.onMisread("кот", wordCount)
        assertEquals(FocusStep.Letters, state.step)
        state = state.onMisread("кот", wordCount)
        assertEquals(FocusStep.Meaning, state.step)
        assertEquals(0, state.wordIndex)
    }

    @Test
    fun misreadFromTheFlashNudgeStillLandsOnSyllables() {
        // The silence timer may have raised the step to Sweep before the reader spoke.
        val nudged = FocusLadderState().onHelpRequested(FocusStep.Sweep)
        assertEquals(FocusStep.Syllables, nudged.onMisread("кот", wordCount).step)
    }

    @Test
    fun misreadAtLastStepReleasesTheReaderForward() {
        var state = FocusLadderState()
        repeat(3) { state = state.onMisread("карандаш", wordCount) }
        assertEquals(FocusStep.Meaning, state.step)
        state = state.onMisread("карандаш", wordCount)
        assertEquals(1, state.wordIndex)
        assertEquals(FocusStep.Focus, state.step)
        assertEquals(listOf("карандаш"), state.triggerWords)
    }

    @Test
    fun flashReturnsTheWordToSharpRatherThanLeavingItBlurred() {
        val nudged = FocusLadderState().onHelpRequested(FocusStep.Sweep)
        assertEquals(FocusStep.Focus, nudged.onNudgeFinished().step)
    }

    @Test
    fun flashCompletionDoesNotUndoHelpTheReaderAskedFor() {
        val helped = FocusLadderState().onHelpRequested(FocusStep.Syllables)
        assertEquals(FocusStep.Syllables, helped.onNudgeFinished().step)
    }

    @Test
    fun silenceNudgeDoesNotCountAsAMisread() {
        // Escalating on a timeout must not spend the reader's accuracy budget before they
        // have even spoken; only advancing a word records anything.
        val nudged = FocusLadderState()
            .onHelpRequested(FocusStep.Sweep)
            .onHelpRequested(FocusStep.Syllables)
        assertTrue(nudged.recentCleanReads.isEmpty())
        assertEquals(0, nudged.wordIndex)
    }

    @Test
    fun wordTakenWithDeepHelpBecomesTriggerWord() {
        var state = FocusLadderState()
        repeat(2) { state = state.onMisread("математика", wordCount) }
        assertEquals(FocusStep.Letters, state.step)
        state = state.onCorrectRead("математика", wordCount)
        assertEquals(listOf("математика"), state.triggerWords)
    }

    @Test
    fun threeDeepWordsInARowSuggestAPause() {
        var state = FocusLadderState()
        repeat(3) {
            repeat(2) { state = state.onMisread("слово", wordCount) }
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
        repeat(2) { state = state.onMisread("слово", wordCount) }
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
            slow = slow.onMisread("слово", wordCount)
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
