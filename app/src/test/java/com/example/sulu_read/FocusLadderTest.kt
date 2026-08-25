package com.example.sulu_read

import com.example.sulu_read.focus.FocusLadderState
import com.example.sulu_read.focus.FocusStep
import com.example.sulu_read.focus.masteryShare
import com.example.sulu_read.focus.onCorrectRead
import com.example.sulu_read.focus.onFocusMoved
import com.example.sulu_read.focus.onHelpRequested
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
    fun movingForwardScoresTheWordBeingLeft() {
        val state = FocusLadderState()
            .onHelpRequested(FocusStep.Letters)
            .onFocusMoved(1, "математика", wordCount)
        assertEquals(1, state.wordIndex)
        assertEquals(FocusStep.Focus, state.step)
        assertEquals(listOf("математика"), state.triggerWords)
        assertEquals(listOf(false), state.recentCleanReads)
    }

    @Test
    fun tappingAheadSkipsToThatWordWithoutScoringTheOnesPassed() {
        val state = FocusLadderState().onFocusMoved(7, "кот", wordCount)
        assertEquals(7, state.wordIndex)
        // One word was finished — the one the reader was on. The six they jumped over were
        // never read, and counting them would be inventing reading that did not happen.
        assertEquals(listOf(true), state.recentCleanReads)
    }

    @Test
    fun goingBackKeepsTheWordThatNeededHelp() {
        // Turning back is the plainest signal there is that a word was hard, so it is the last
        // moment to collect it — the step is about to be cleared.
        val state = FocusLadderState()
            .onCorrectRead("кот", wordCount)
            .onHelpRequested(FocusStep.Letters)
            .onFocusMoved(0, "математика", wordCount)
        assertEquals(listOf("математика"), state.triggerWords)
        assertEquals(listOf(true), state.recentCleanReads)
    }

    @Test
    fun goingBackToReReadScoresNothing() {
        val state = FocusLadderState()
            .onCorrectRead("кот", wordCount)
            .onCorrectRead("дом", wordCount)
            .onHelpRequested(FocusStep.Letters)
            .onFocusMoved(0, "мяч", wordCount)
        assertEquals(0, state.wordIndex)
        // Still only the two words that were actually finished.
        assertEquals(listOf(true, true), state.recentCleanReads)
        // The support the reader climbed to belongs to the word they left.
        assertEquals(FocusStep.Focus, state.step)
    }

    @Test
    fun movingToTheWordAlreadyFocusedChangesNothing() {
        val state = FocusLadderState().onCorrectRead("кот", wordCount)
        assertEquals(state, state.onFocusMoved(1, "дом", wordCount))
    }

    @Test
    fun focusNeverLeavesTheText() {
        val state = FocusLadderState().onFocusMoved(500, "кот", wordCount)
        assertEquals(wordCount, state.wordIndex)
        assertEquals(0, FocusLadderState(wordIndex = 3).onFocusMoved(-4, "кот", wordCount).wordIndex)
    }

    @Test
    fun flashReturnsTheWordToSharpRatherThanLeavingItBlurred() {
        val nudged = FocusLadderState().onHelpRequested(FocusStep.Sweep)
        assertEquals(FocusStep.Focus, nudged.onNudgeFinished().step)
    }

    @Test
    fun flashCompletionDoesNotUndoHelpTheReaderAskedFor() {
        val helped = FocusLadderState().onHelpRequested(FocusStep.Letters)
        assertEquals(FocusStep.Letters, helped.onNudgeFinished().step)
    }

    @Test
    fun silenceNudgeCostsTheReaderNothingUntilTheyMoveOn() {
        // Escalating on a timeout must not spend the reader's accuracy budget before they
        // have even spoken; only finishing a word records anything.
        val nudged = FocusLadderState()
            .onHelpRequested(FocusStep.Sweep)
            .onHelpRequested(FocusStep.Letters)
        assertTrue(nudged.recentCleanReads.isEmpty())
        assertEquals(0, nudged.wordIndex)
    }

    @Test
    fun wordTakenWithDeepHelpBecomesTriggerWord() {
        var state = FocusLadderState()
        state = state.onHelpRequested(FocusStep.Letters)
        state = state.onCorrectRead("математика", wordCount)
        assertEquals(listOf("математика"), state.triggerWords)
    }

    @Test
    fun threeDeepWordsInARowSuggestAPause() {
        var state = FocusLadderState()
        repeat(3) {
            state = state.onHelpRequested(FocusStep.Letters)
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
        state = state.onHelpRequested(FocusStep.Letters)
        state = state.onCorrectRead("слово", wordCount)
        state = state.onCorrectRead("дом", wordCount)
        assertEquals(0, state.consecutiveDeepWords)
    }

    @Test
    fun helpJumpsForwardButNeverBackward() {
        val jumped = FocusLadderState().onHelpRequested(FocusStep.Letters)
        assertEquals(FocusStep.Letters, jumped.step)

        val held = jumped.onHelpRequested(FocusStep.Sweep)
        assertEquals(FocusStep.Letters, held.step)
    }

    @Test
    fun rateStartsSlowAndReachesFullSpeedAtTheMasteryTarget() {
        assertEquals(0.6f, FocusLadderState().ttsRate(), 0.001f)

        var slow = FocusLadderState()
        repeat(20) {
            slow = slow.onHelpRequested(FocusStep.Letters)
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
            state = state.onHelpRequested(FocusStep.Letters)
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
