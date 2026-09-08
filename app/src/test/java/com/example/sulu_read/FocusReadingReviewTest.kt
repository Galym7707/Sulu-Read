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
    fun aNumberOnThePageIsCreditedWhenReadAsWords() {
        // "25" written in digits; the recogniser transcribes speech as words. Before numerals
        // were understood, digit targets were dropped from the review and never credited.
        val reviews = reviewReading(
            spokenTokens = heard("страница", "двадцать", "пять", "готова"),
            targets = listOf("страница", "25", "готова")
        )

        assertTrue(reviews.all { it.outcome == ReadOutcome.Correct })
        assertEquals("двадцать пять", reviews[1].heard)
        assertTrue(mistakesFrom(reviews).isEmpty())
    }

    @Test
    fun aNumberInWordsOnThePageIsCreditedWhenTheEngineWritesDigits() {
        // The other direction: two words on the page, one digit token from the engine.
        val reviews = reviewReading(
            spokenTokens = heard("страница", "25", "готова"),
            targets = listOf("страница", "двадцать", "пять", "готова")
        )

        assertTrue(reviews.all { it.outcome == ReadOutcome.Correct })
        assertEquals(listOf("25", "25"), reviews.subList(1, 3).map { it.heard })
    }

    @Test
    fun aKazakhYearSpansSixWords() {
        val reviews = reviewReading(
            spokenTokens = heard("бір", "мың", "тоғыз", "жүз", "тоқсан", "бес", "жыл"),
            targets = listOf("1995", "жыл")
        )

        assertTrue(reviews.all { it.outcome == ReadOutcome.Correct })
    }

    @Test
    fun aWrongNumberIsAMisreading() {
        val reviews = reviewReading(
            spokenTokens = heard("страница", "шесть"),
            targets = listOf("страница", "5")
        )

        val mistakes = mistakesFrom(reviews)
        assertEquals(listOf("5"), mistakes.map { it.word })
        assertEquals(ReadOutcome.Misread, mistakes.first().outcome)
    }

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
    fun anAlternativeAndANumeralSpanWorkInTheSameReading() {
        // Numeral spans and per-token alternatives are two separate widenings of the same
        // aligner, and the merge that brought them together is exactly where one would silently
        // break the other.
        val reviews = reviewReading(
            spokenTokens = listOf(HeardToken("кинга", listOf("книга"))) + heard("двадцать", "пять"),
            targets = listOf("книга", "25")
        )

        assertTrue(reviews.all { it.outcome == ReadOutcome.Correct })
        assertEquals("кинга", reviews[0].heard)          // rescued by an alternative
        assertEquals("двадцать пять", reviews[1].heard)  // matched as a numeral span
    }

    @Test
    fun anAlternativeDoesNotRescueAGenuinelyWrongNumber() {
        val reviews = reviewReading(
            spokenTokens = listOf(HeardToken("шесть", listOf("шесть"))),
            targets = listOf("5")
        )

        assertEquals(ReadOutcome.Misread, reviews.first().outcome)
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
