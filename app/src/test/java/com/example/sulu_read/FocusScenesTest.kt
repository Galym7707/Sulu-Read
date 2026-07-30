package com.example.sulu_read

import com.example.sulu_read.focus.buildFocusWords
import com.example.sulu_read.focus.sceneCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusScenesTest {
    @Test
    fun keepsPunctuationInDisplayButNotInSpokenForm() {
        val words = buildFocusWords("Мама, папа.", emptyList())
        assertEquals(listOf("Мама,", "папа."), words.map { it.display })
        assertEquals(listOf("Мама", "папа"), words.map { it.spoken })
    }

    @Test
    fun startsNewSceneAfterSentenceEnd() {
        val words = buildFocusWords("Кот спит. Пёс бежит.", emptyList())
        assertEquals(listOf(0, 0, 1, 1), words.map { it.sceneIndex })
        assertEquals(2, sceneCount(words))
    }

    @Test
    fun doesNotBreakSceneOnEarlyComma() {
        val words = buildFocusWords("Кот, пёс и мышь спят.", emptyList())
        assertEquals(1, sceneCount(words))
    }

    @Test
    fun breaksSceneOnCommaAfterEnoughWords() {
        val text = "один два три четыре пять шесть, семь восемь."
        val words = buildFocusWords(text, emptyList())
        assertEquals(2, sceneCount(words))
        assertEquals(0, words.first { it.display == "шесть," }.sceneIndex)
        assertEquals(1, words.first { it.display == "семь" }.sceneIndex)
    }

    @Test
    fun takesSyllablesFromBackendWhenAvailable() {
        val backend = listOf(SyllableWord(original = "мама", syllables = listOf("ма", "ма")))
        val words = buildFocusWords("Мама.", backend)
        assertEquals(listOf("ма", "ма"), words.single().syllables)
    }

    @Test
    fun fallsBackToWholeWordWhenBackendHasNoMatch() {
        val words = buildFocusWords("Мама.", emptyList())
        assertEquals(listOf("Мама"), words.single().syllables)
    }

    @Test
    fun skipsTokensWithoutLetters() {
        val words = buildFocusWords("Кот — спит.", emptyList())
        assertEquals(listOf("Кот", "спит."), words.map { it.display })
    }

    @Test
    fun returnsEmptyForBlankText() {
        assertTrue(buildFocusWords("   ", emptyList()).isEmpty())
        assertEquals(0, sceneCount(emptyList()))
    }
}
