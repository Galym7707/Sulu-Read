package com.example.sulu_read

import com.example.sulu_read.focus.buildFocusWords
import com.example.sulu_read.focus.focusWordRanges
import com.example.sulu_read.focus.sceneCount
import com.example.sulu_read.focus.wordIndexAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusScenesTest {
    @Test
    fun keepsPunctuationInDisplayButNotInSpokenForm() {
        val words = buildFocusWords("Мама, папа.")
        assertEquals(listOf("Мама,", "папа."), words.map { it.display })
        assertEquals(listOf("Мама", "папа"), words.map { it.spoken })
    }

    @Test
    fun startsNewSceneAfterSentenceEnd() {
        val words = buildFocusWords("Кот спит. Пёс бежит.")
        assertEquals(listOf(0, 0, 1, 1), words.map { it.sceneIndex })
        assertEquals(2, sceneCount(words))
    }

    @Test
    fun doesNotBreakSceneOnEarlyComma() {
        val words = buildFocusWords("Кот, пёс и мышь спят.")
        assertEquals(1, sceneCount(words))
    }

    @Test
    fun breaksSceneOnCommaAfterEnoughWords() {
        val text = "один два три четыре пять шесть, семь восемь."
        val words = buildFocusWords(text)
        assertEquals(2, sceneCount(words))
        assertEquals(0, words.first { it.display == "шесть," }.sceneIndex)
        assertEquals(1, words.first { it.display == "семь" }.sceneIndex)
    }

    @Test
    fun skipsTokensWithoutLetters() {
        val words = buildFocusWords("Кот — спит.")
        assertEquals(listOf("Кот", "спит."), words.map { it.display })
    }

    @Test
    fun returnsEmptyForBlankText() {
        assertTrue(buildFocusWords("   ").isEmpty())
        assertEquals(0, sceneCount(emptyList()))
    }

    @Test
    fun aTapFindsTheWordItLandedOn() {
        val ranges = focusWordRanges(buildFocusWords("Кот спит дома."))
        assertEquals(0, wordIndexAt(ranges, 0))
        assertEquals(1, wordIndexAt(ranges, 5))
        assertEquals(2, wordIndexAt(ranges, 9))
    }

    @Test
    fun aTapOnWhitespaceFallsBackToTheWordBeforeIt() {
        // Taps land on the gaps between words and at the ragged end of a line at least as often
        // as they land on a glyph, and a tap that does nothing reads as a broken app.
        val ranges = focusWordRanges(buildFocusWords("Кот спит дома."))
        assertEquals(0, wordIndexAt(ranges, 3))
        assertEquals(2, wordIndexAt(ranges, 400))
    }

    @Test
    fun thereIsNothingToTapInAnEmptyText() {
        assertNull(wordIndexAt(emptyList(), 0))
    }
}
