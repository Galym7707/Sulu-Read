package com.example.sulu_read

import com.example.sulu_read.focus.letterNamesFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusLetterNamesTest {
    @Test
    fun namesRussianLettersTheRussianWay() {
        assertEquals(listOf("эм", "а", "эм", "а"), letterNamesFor("мама", "ru"))
        assertEquals(listOf("дэ", "о", "эм"), letterNamesFor("Дом,", "ru"))
    }

    @Test
    fun namesKazakhConsonantsWithEAndNotWithRussianE() {
        // The bug this replaces: a Kazakh word was spelled out with Russian names, so a child
        // reading "ата-баба" heard "а тэ а бэ а бэ а".
        val names = letterNamesFor("ата-баба", "kk")
        assertEquals(listOf("а", "те", "а", "бе", "а", "бе", "а"), names)
        assertFalse(names.contains("тэ"))
        assertFalse(names.contains("бэ"))
    }

    @Test
    fun keepsTheTwoCyrillicTablesApart() {
        // Same letter, two alphabets, two names. A single merged table cannot hold both, which
        // is how the Kazakh names were lost in the first place.
        assertEquals(listOf("тэ"), letterNamesFor("т", "ru"))
        assertEquals(listOf("те"), letterNamesFor("т", "kk"))
    }

    @Test
    fun namesKazakhSpecificLetters() {
        assertEquals(listOf("қа", "а", "эл", "а"), letterNamesFor("қала", "kk"))
        assertEquals(listOf("ға", "ы"), letterNamesFor("ғы", "kk"))
    }

    @Test
    fun latinAlwaysUsesEnglishNames() {
        // A Latin letter has no Cyrillic name, so the UI language must not change it.
        assertEquals(listOf("bee", "oh", "oh", "kay"), letterNamesFor("Book", "en"))
        assertEquals(listOf("bee", "oh", "oh", "kay"), letterNamesFor("Book", "kk"))
        assertEquals(listOf("bee", "oh", "oh", "kay"), letterNamesFor("Book", "ru"))
    }

    @Test
    fun keepsCharactersItHasNoNameFor() {
        assertEquals(listOf("5"), letterNamesFor("5", "ru"))
    }

    @Test
    fun ignoresPunctuationAndBlankInput() {
        assertTrue(letterNamesFor("   ", "kk").isEmpty())
        assertEquals(listOf("а", "те", "а"), letterNamesFor("«ата»!", "kk"))
    }
}
