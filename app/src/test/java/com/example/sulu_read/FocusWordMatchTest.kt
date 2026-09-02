package com.example.sulu_read

import com.example.sulu_read.focus.isSpokenWordAccepted
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
    fun tokenizerSplitsOnAnyWhitespace() {
        assertEquals(listOf("кот", "спит"), tokenizeTranscript("  кот   спит \n"))
        assertEquals(emptyList<String>(), tokenizeTranscript("   "))
    }

    @Test
    fun acceptsKazakhWordWrittenByARussianModeRecogniser() {
        // The engine was transcribing in Russian and has no ә, ө, ұ, ү or і to write, so the
        // missing letter is the transcriber's limit, not the child's mistake.
        assertTrue(isSpokenWordAccepted("қала", listOf("кала")))
        assertTrue(isSpokenWordAccepted("кітап", listOf("кытап")))
        assertTrue(isSpokenWordAccepted("үй", listOf("уй")))
        assertTrue(isSpokenWordAccepted("сөз", listOf("соз")))
        assertTrue(isSpokenWordAccepted("әке", listOf("аке")))
    }

    @Test
    fun keepsKazakhVowelsApartWhenTheRecogniserCouldWriteThem() {
        // These are different Kazakh words. The old table folded every Kazakh vowel away
        // unconditionally and accepted each of these as a correct reading of the other, so the
        // gate was strict for Russian and wide open for Kazakh.
        assertFalse(isSpokenWordAccepted("күн", listOf("құн")))
        assertFalse(isSpokenWordAccepted("түс", listOf("тұс")))
        assertFalse(isSpokenWordAccepted("тіс", listOf("тұс")))
        assertFalse(isSpokenWordAccepted("сөз", listOf("сұз")))
    }

    @Test
    fun aRussianModeTranscriptStillMergesKazakhVowels() {
        // The deliberate cost of the rule above. When the transcript contains no Kazakh letter
        // at all there is no way to tell "the engine could not write ө" from "the child said о",
        // so the benefit of the doubt goes to the child. Documented rather than hidden.
        assertTrue(isSpokenWordAccepted("өл", listOf("ол")))
    }

    @Test
    fun kazakhConsonantsStayInterchangeable() {
        // қ/к and ғ/г are fixed by vowel harmony, so confusing them cannot make one real word
        // into another - unlike the vowels.
        assertTrue(isSpokenWordAccepted("қар", listOf("кар")))
        assertTrue(isSpokenWordAccepted("ағаш", listOf("агаш")))
    }

    @Test
    fun acceptsANumberReadAsWords() {
        // "5" on the page, "пять" from the microphone: the same reading, in any language.
        assertTrue(isSpokenWordAccepted("5", listOf("пять")))
        assertTrue(isSpokenWordAccepted("5", listOf("бес")))
        assertTrue(isSpokenWordAccepted("5", listOf("five")))
        assertTrue(isSpokenWordAccepted("25", listOf("двадцать пять")))
        assertTrue(isSpokenWordAccepted("1995", listOf("бір мың тоғыз жүз тоқсан бес")))
    }

    @Test
    fun acceptsANumberTheEngineWroteInDigits() {
        // Most engines write "25" for spoken "двадцать пять"; the page had the words.
        assertTrue(isSpokenWordAccepted("пять", listOf("5")))
        assertTrue(isSpokenWordAccepted("бес", listOf("5")))
    }

    @Test
    fun aDifferentNumberIsStillAMisreading() {
        assertFalse(isSpokenWordAccepted("5", listOf("шесть")))
        assertFalse(isSpokenWordAccepted("25", listOf("пятьдесят два")))
        assertFalse(isSpokenWordAccepted("5", listOf("6")))
    }

    @Test
    fun numbersWrittenAsWordsOnBothSidesUseTheLetterRules() {
        // "он" is both Russian "he" and Kazakh "ten". With no digits involved the comparison
        // stays on the letters, so a Russian text is not judged by the Kazakh number.
        assertTrue(isSpokenWordAccepted("он", listOf("он")))
        assertFalse(isSpokenWordAccepted("он", listOf("десять")))
    }

    @Test
    fun rejectsEmptyInput() {
        assertFalse(isSpokenWordAccepted("книга", emptyList()))
        assertFalse(isSpokenWordAccepted("книга", listOf("", "   ")))
    }
}
