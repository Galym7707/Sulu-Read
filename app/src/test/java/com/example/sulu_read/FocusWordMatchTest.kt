package com.example.sulu_read

import com.example.sulu_read.focus.isSpokenWordAccepted
import com.example.sulu_read.focus.matchSpokenStream
import com.example.sulu_read.focus.tokenizeTranscript
import com.example.sulu_read.focus.normalizeForMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusWordMatchTest {
    @Test
    fun stripsCaseAndPunctuation() {
        assertEquals("мама", normalizeForMatch("Мама,"))
        assertEquals("қыстақ", normalizeForMatch("«Қыстақ»!"))
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
    fun acceptsEnglishWords() {
        assertTrue(isSpokenWordAccepted("reading", listOf("reading")))
        assertTrue(isSpokenWordAccepted("Dyslexia,", listOf("dyslexia")))
        // One substitution inside a medium English word is recognizer noise, not a misreading.
        assertTrue(isSpokenWordAccepted("school", listOf("schoal")))
    }

    @Test
    fun rejectsWrongEnglishWords() {
        assertFalse(isSpokenWordAccepted("book", listOf("look")))
        assertFalse(isSpokenWordAccepted("reading", listOf("writing")))
    }

    @Test
    fun cyrillicFoldingDoesNotLeakIntoEnglish() {
        // The RU/KK folding map must not make unrelated Latin words equal.
        assertFalse(isSpokenWordAccepted("cat", listOf("cut")))
        assertFalse(isSpokenWordAccepted("men", listOf("man")))
    }

    @Test
    fun acceptsWordSpokenInsideAPhrase() {
        // Engines return filler and hesitation alongside the word, and a reader who pauses
        // before speaking must not be marked wrong for it.
        assertTrue(isSpokenWordAccepted("книга", listOf("это книга")))
        assertTrue(isSpokenWordAccepted("reading", listOf("um reading")))
    }

    @Test
    fun acceptsFinalDevoicing() {
        // Russian and Kazakh devoice word-final obstruents, so the recognizer's choice between
        // the pair says nothing about whether the reading was correct.
        assertTrue(isSpokenWordAccepted("дуб", listOf("дуп")))
        assertTrue(isSpokenWordAccepted("сад", listOf("сат")))
    }

    @Test
    fun devoicingAppliesOnlyAtTheEndOfAWord() {
        // The same swap at the front separates real words, so it must still cost.
        assertFalse(isSpokenWordAccepted("дом", listOf("том")))
        assertFalse(isSpokenWordAccepted("год", listOf("кот")))
    }

    @Test
    fun acceptsAccentedEnglishSpellings() {
        assertTrue(isSpokenWordAccepted("think", listOf("tink")))
        assertTrue(isSpokenWordAccepted("water", listOf("vater")))
        assertTrue(isSpokenWordAccepted("phone", listOf("fone")))
    }

    @Test
    fun softCStaysDistinctFromHardC() {
        // "c" is mapped by what follows it; a blanket c -> k would stop "city" matching itself
        // and would merge words that a reader has to tell apart.
        assertTrue(isSpokenWordAccepted("city", listOf("sity")))
        assertFalse(isSpokenWordAccepted("cat", listOf("sat")))
    }

    @Test
    fun phoneticFoldingStillRejectsDifferentWords() {
        assertFalse(isSpokenWordAccepted("book", listOf("look")))
        assertFalse(isSpokenWordAccepted("cat", listOf("cut")))
        assertFalse(isSpokenWordAccepted("men", listOf("man")))
        assertFalse(isSpokenWordAccepted("книга", listOf("тетрадь")))
    }

    @Test
    fun streamClearsSeveralWordsFromOneUtterance() {
        // A fluent reader says three words before the engine ends the session; all three must
        // count, without a recogniser restart between them.
        val match = matchSpokenStream(
            tokens = listOf("кот", "спит", "дома"),
            targets = listOf("кот", "спит", "дома", "тихо")
        )
        assertEquals(3, match.wordsMatched)
        assertEquals(3, match.tokensConsumed)
    }

    @Test
    fun streamStopsAtTheFirstWordNotRead() {
        val match = matchSpokenStream(
            tokens = listOf("кот", "бежит"),
            targets = listOf("кот", "спит", "дома")
        )
        assertEquals(1, match.wordsMatched)
        assertEquals(1, match.tokensConsumed)
    }

    @Test
    fun streamStepsOverFiller() {
        val match = matchSpokenStream(
            tokens = listOf("эм", "кот", "ну", "спит"),
            targets = listOf("кот", "спит")
        )
        assertEquals(2, match.wordsMatched)
        assertEquals(4, match.tokensConsumed)
    }

    @Test
    fun streamNeverMatchesOutOfOrder() {
        // "спит" arriving first must not clear the second target while the first is unread.
        val match = matchSpokenStream(
            tokens = listOf("спит", "кот"),
            targets = listOf("кот", "спит")
        )
        assertEquals(1, match.wordsMatched)
        assertEquals(2, match.tokensConsumed)
    }

    @Test
    fun consumedCountLetsAGrowingTranscriptBeRescanned() {
        // The engine revises one transcript; the caller drops what it already credited, so the
        // same speech is never counted twice.
        val first = matchSpokenStream(listOf("кот"), listOf("кот", "спит"))
        assertEquals(1, first.wordsMatched)

        val grown = listOf("кот", "спит")
        val second = matchSpokenStream(grown.drop(first.tokensConsumed), listOf("спит"))
        assertEquals(1, second.wordsMatched)
    }

    @Test
    fun streamMatchesNothingWhenSilent() {
        val match = matchSpokenStream(emptyList(), listOf("кот"))
        assertEquals(0, match.wordsMatched)
        assertEquals(0, match.tokensConsumed)
    }

    @Test
    fun streamHonoursThePhoneticRules() {
        assertEquals(1, matchSpokenStream(listOf("дуп"), listOf("дуб")).wordsMatched)
        assertEquals(0, matchSpokenStream(listOf("том"), listOf("дом")).wordsMatched)
    }

    @Test
    fun tokenizerSplitsOnAnyWhitespace() {
        assertEquals(listOf("кот", "спит"), tokenizeTranscript("  кот   спит \n"))
        assertEquals(emptyList<String>(), tokenizeTranscript("   "))
    }

    @Test
    fun rejectsEmptyInput() {
        assertFalse(isSpokenWordAccepted("книга", emptyList()))
        assertFalse(isSpokenWordAccepted("книга", listOf("", "   ")))
    }
}
