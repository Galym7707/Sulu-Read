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
    fun rejectsEmptyInput() {
        assertFalse(isSpokenWordAccepted("книга", emptyList()))
        assertFalse(isSpokenWordAccepted("книга", listOf("", "   ")))
    }
}
