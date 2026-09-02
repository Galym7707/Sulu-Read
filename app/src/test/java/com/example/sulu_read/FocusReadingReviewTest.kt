package com.example.sulu_read

import com.example.sulu_read.focus.ReadOutcome
import com.example.sulu_read.focus.mistakesFrom
import com.example.sulu_read.focus.reviewReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusReadingReviewTest {
    @Test
    fun aNumberOnThePageIsCreditedWhenReadAsWords() {
        // "25" written in digits; the recogniser transcribes speech as words. Before numerals
        // were understood, digit targets were dropped from the review and never credited.
        val reviews = reviewReading(
            spokenTokens = listOf("страница", "двадцать", "пять", "готова"),
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
            spokenTokens = listOf("страница", "25", "готова"),
            targets = listOf("страница", "двадцать", "пять", "готова")
        )

        assertTrue(reviews.all { it.outcome == ReadOutcome.Correct })
        assertEquals(listOf("25", "25"), reviews.subList(1, 3).map { it.heard })
    }

    @Test
    fun aKazakhYearSpansSixWords() {
        val reviews = reviewReading(
            spokenTokens = listOf("бір", "мың", "тоғыз", "жүз", "тоқсан", "бес", "жыл"),
            targets = listOf("1995", "жыл")
        )

        assertTrue(reviews.all { it.outcome == ReadOutcome.Correct })
    }

    @Test
    fun aWrongNumberIsAMisreading() {
        val reviews = reviewReading(
            spokenTokens = listOf("страница", "шесть"),
            targets = listOf("страница", "5")
        )

        val mistakes = mistakesFrom(reviews)
        assertEquals(listOf("5"), mistakes.map { it.word })
        assertEquals(ReadOutcome.Misread, mistakes.first().outcome)
    }

    @Test
    fun cleanReadingHasNoMistakes() {
        val reviews = reviewReading(
            spokenTokens = listOf("мама", "мыла", "раму"),
            targets = listOf("мама", "мыла", "раму")
        )

        assertTrue(reviews.all { it.outcome == ReadOutcome.Correct })
        assertTrue(mistakesFrom(reviews).isEmpty())
    }

    @Test
    fun reportsTheWordThatCameOutWrong() {
        val reviews = reviewReading(
            spokenTokens = listOf("мама", "мыло", "раму"),
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
            spokenTokens = listOf("мама", "раму"),
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
            spokenTokens = listOf("мама", "эм", "мыла", "раму"),
            targets = listOf("мама", "мыла", "раму")
        )

        assertTrue(mistakesFrom(reviews).isEmpty())
    }

    @Test
    fun oneWordReadWrongTwiceIsOneThingToPractise() {
        val reviews = reviewReading(
            spokenTokens = listOf("кинга", "кинга"),
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
            spokenTokens = listOf("мама", "эм"),
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
            spokenTokens = listOf("кинга", "книга"),
            targets = listOf("книга", "книга")
        )

        assertTrue(mistakesFrom(reviews).isEmpty())
    }

    @Test
    fun aWordReadRightThenReadWrongIsJudgedOnTheLastAttempt() {
        val reviews = reviewReading(
            spokenTokens = listOf("книга", "кинга"),
            targets = listOf("книга", "книга")
        )

        val mistakes = mistakesFrom(reviews)
        assertEquals(listOf("книга"), mistakes.map { it.word })
        assertEquals("кинга", mistakes.first().heard)
    }
}
