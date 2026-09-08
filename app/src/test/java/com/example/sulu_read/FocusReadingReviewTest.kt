package com.example.sulu_read

import com.example.sulu_read.focus.HeardToken
import com.example.sulu_read.focus.ReadOutcome
import com.example.sulu_read.focus.mistakesFrom
import com.example.sulu_read.focus.reviewReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusReadingReviewTest {
    /** A transcript with no second opinion, which is what a partial result gives. */
    private fun heard(vararg words: String): List<HeardToken> = words.map { HeardToken(it) }

    @Test
    fun cleanReadingHasNoMistakes() {
        val reviews = reviewReading(
            spokenTokens = heard("мама", "мыла", "раму"),
            targets = listOf("мама", "мыла", "раму")
        )

        assertTrue(reviews.all { it.outcome == ReadOutcome.Correct })
        assertTrue(mistakesFrom(reviews).isEmpty())
    }

    @Test
    fun reportsTheWordThatCameOutWrong() {
        val reviews = reviewReading(
            spokenTokens = heard("мама", "мыло", "раму"),
            targets = listOf("мама", "мыла", "раму")
        )

        val mistakes = mistakesFrom(reviews)
        assertEquals(listOf("мыла"), mistakes.map { it.word })
        assertEquals(ReadOutcome.Misread, mistakes.first().outcome)
        assertEquals("мыло", mistakes.first().heard)
    }

    @Test
    fun aSkippedWordDoesNotShiftTheWordsAfterIt() {
        val reviews = reviewReading(
            spokenTokens = heard("мама", "раму"),
            targets = listOf("мама", "мыла", "раму")
        )

        assertEquals(
            listOf(ReadOutcome.Correct, ReadOutcome.Silent, ReadOutcome.Correct),
            reviews.map { it.outcome }
        )
    }

    @Test
    fun fillerBetweenWordsIsNotAMistake() {
        val reviews = reviewReading(
            spokenTokens = heard("мама", "эм", "мыла", "раму"),
            targets = listOf("мама", "мыла", "раму")
        )

        assertTrue(mistakesFrom(reviews).isEmpty())
    }

    @Test
    fun oneWordReadWrongTwiceIsOneThingToPractise() {
        val reviews = reviewReading(
            spokenTokens = heard("кинга", "кинга"),
            targets = listOf("книга", "книга")
        )

        assertEquals(1, mistakesFrom(reviews).size)
    }

    @Test
    fun noTranscriptMeansNoVerdict() {
        assertTrue(reviewReading(emptyList(), listOf("книга")).isEmpty())
    }

    @Test
    fun aStrayTokenIsNotPinnedOnAWordTheReaderNeverReached() {
        // The reader read one word and stopped; the engine emitted one token of its own. Pairing
        // that token with a distant word used to be cheaper than dropping the two separately, so
        // the panel accused a word nobody had got to yet.
        val reviews = reviewReading(
            spokenTokens = heard("мама", "эм"),
            targets = listOf("мама", "мыла", "раму")
        )

        assertEquals(
            listOf(ReadOutcome.Correct, ReadOutcome.Silent, ReadOutcome.Silent),
            reviews.map { it.outcome }
        )
    }

    @Test
    fun aWordReadWrongThenReadRightIsNotAMistake() {
        // Going back over a line is the gesture the mode invites; correcting yourself must not
        // be reported as the failure.
        val reviews = reviewReading(
            spokenTokens = heard("кинга", "книга"),
            targets = listOf("книга", "книга")
        )

        assertTrue(mistakesFrom(reviews).isEmpty())
    }

    @Test
    fun aLaterHypothesisRescuesAReadingTheFirstGuessGotWrong() {
        // The engine's best guess is "кинга"; one of the other nine it was asked for is the word
        // the child actually said. Scoring only the first is what told an accented reader they
        // had misread a word they read correctly.
        val reviews = reviewReading(
            spokenTokens = listOf(HeardToken("кинга", listOf("книга"))),
            targets = listOf("книга")
        )

        assertEquals(listOf(ReadOutcome.Correct), reviews.map { it.outcome })
        assertTrue(mistakesFrom(reviews).isEmpty())
    }

    @Test
    fun theReaderIsToldWhatTheEngineActuallySettledOn() {
        // An alternative may rescue the match, but it is not what the panel reports back: the
        // reader is being told what they were heard to say, not which guess made it pass.
        val reviews = reviewReading(
            spokenTokens = listOf(HeardToken("кинга", listOf("книга"))),
            targets = listOf("книга")
        )

        assertEquals("кинга", reviews.first().heard)
    }

    @Test
    fun anAlternativeDoesNotPinFillerOnAWordTheReaderNeverReached() {
        // Alternatives widen what counts as a correct reading and nothing else. If they also
        // widened the misreading budget, a stray token carrying a lucky alternative would be
        // charged to a word further down the page instead of being stepped over.
        val reviews = reviewReading(
            spokenTokens = heard("мама") + HeardToken("эм", listOf("это")),
            targets = listOf("мама", "мыла", "раму")
        )

        assertEquals(
            listOf(ReadOutcome.Correct, ReadOutcome.Silent, ReadOutcome.Silent),
            reviews.map { it.outcome }
        )
    }

    @Test
    fun aWordReadRightThenReadWrongIsJudgedOnTheLastAttempt() {
        val reviews = reviewReading(
            spokenTokens = heard("книга", "кинга"),
            targets = listOf("книга", "книга")
        )

        val mistakes = mistakesFrom(reviews)
        assertEquals(listOf("книга"), mistakes.map { it.word })
        assertEquals("кинга", mistakes.first().heard)
    }
}
