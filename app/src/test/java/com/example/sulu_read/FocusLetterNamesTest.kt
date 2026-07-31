package com.example.sulu_read

import com.example.sulu_read.focus.letterNamesFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusLetterNamesTest {
    @Test
    fun namesRussianLetters() {
        assertEquals(listOf("эм", "а", "эм", "а"), letterNamesFor("мама"))
    }

    @Test
    fun namesRussianLettersIgnoringCaseAndPunctuation() {
        assertEquals(listOf("дэ", "о", "эм"), letterNamesFor("Дом,"))
    }

    @Test
    fun namesKazakhSpecificLetters() {
        assertEquals(listOf("қа", "а", "эль", "а"), letterNamesFor("қала"))
    }

    @Test
    fun namesEnglishLetters() {
        // The English UI previously had no letter table at all, so every Latin character came
        // back as itself and the spell step read out sounds instead of names.
        assertEquals(listOf("bee", "oh", "oh", "kay"), letterNamesFor("Book"))
    }

    @Test
    fun namesKazakhLettersWhateverTheReaderPicked() {
        // The letter decides its own name, not the language selected in the menu. Choosing the
        // table by UI language left Kazakh letters inside a Russian text with no name at all.
        assertEquals(listOf("қа", "а", "һә", "а"), letterNamesFor("қаһа"))
    }

    @Test
    fun neverReturnsRawSoundsForUnknownCharacters() {
        // A digit has no letter name; it is spoken as itself rather than dropped.
        assertEquals(listOf("5"), letterNamesFor("5"))
    }

    @Test
    fun returnsEmptyForBlankWord() {
        assertTrue(letterNamesFor("   ").isEmpty())
    }
}
