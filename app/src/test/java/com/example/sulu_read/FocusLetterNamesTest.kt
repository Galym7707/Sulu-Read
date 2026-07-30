package com.example.sulu_read

import com.example.sulu_read.focus.letterNamesFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusLetterNamesTest {
    @Test
    fun namesRussianLetters() {
        assertEquals(listOf("эм", "а", "эм", "а"), letterNamesFor("мама", "ru"))
    }

    @Test
    fun namesRussianLettersIgnoringCaseAndPunctuation() {
        assertEquals(listOf("дэ", "о", "эм"), letterNamesFor("Дом,", "ru"))
    }

    @Test
    fun namesKazakhSpecificLetters() {
        assertEquals(listOf("қа", "а", "эль", "а"), letterNamesFor("қала", "kk"))
    }

    @Test
    fun neverReturnsRawSoundsForUnknownCharacters() {
        // A digit has no letter name; it is spoken as itself rather than dropped.
        assertEquals(listOf("5"), letterNamesFor("5", "ru"))
    }

    @Test
    fun returnsEmptyForBlankWord() {
        assertTrue(letterNamesFor("   ", "ru").isEmpty())
    }
}
