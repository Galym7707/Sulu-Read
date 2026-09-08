package com.example.sulu_read

import com.example.sulu_read.focus.HeardToken
import com.example.sulu_read.focus.isSpokenWordAccepted
import com.example.sulu_read.focus.tokenizeTranscript
import com.example.sulu_read.focus.tokensWithAlternatives
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
    fun hypothesesBecomePerWordAlternatives() {
        // The best hypothesis is the spine; the others contribute only where they disagree, and
        // at the position where they disagree.
        val tokens = tokensWithAlternatives(listOf("кинга на столе", "книга на столе"))

        assertEquals(listOf("кинга", "на", "столе"), tokens.map { it.text })
        assertEquals(listOf("книга"), tokens[0].alternatives)
        assertEquals(emptyList<String>(), tokens[1].alternatives)
        assertEquals(emptyList<String>(), tokens[2].alternatives)
    }

    @Test
    fun aHypothesisWithAWordMissingStillLinesUpAfterTheGap() {
        // The naive pairing — by position — would put "столе" against "на" and report it as an
        // alternative for the wrong word, which is how an alternative turns into a false accept.
        val tokens = tokensWithAlternatives(listOf("книга на столе", "книга столе"))

        assertEquals(emptyList<String>(), tokens[1].alternatives)
        assertEquals(emptyList<String>(), tokens[2].alternatives)
    }

    @Test
    fun anAlternativeIdenticalToTheBestGuessIsNotKept() {
        val tokens = tokensWithAlternatives(listOf("Книга", "книга,", "кинга"))

        assertEquals(listOf("кинга"), tokens[0].alternatives)
    }

    @Test
    fun alternativesAreCappedAndTheBestHypothesesComeFirst() {
        val tokens = tokensWithAlternatives(
            listOf("нулевой", "первый", "второй", "третий", "четвёртый", "пятый", "шестой")
        )

        assertEquals(
            listOf("первый", "второй", "третий", "четвёртый"),
            tokens[0].alternatives
        )
    }

    @Test
    fun noHypothesesMeansNoTokens() {
        assertEquals(emptyList<HeardToken>(), tokensWithAlternatives(emptyList()))
        assertEquals(emptyList<HeardToken>(), tokensWithAlternatives(listOf("   ")))
    }

    @Test
    fun anAlternativeIsEnoughToAcceptAReading() {
        // The whole point: isSpokenWordAccepted already took a list, and until now was only ever
        // handed one string.
        val tokens = tokensWithAlternatives(listOf("кинга", "книга"))

        assertFalse(isSpokenWordAccepted("книга", listOf(tokens[0].text)))
        assertTrue(isSpokenWordAccepted("книга", tokens[0].candidates()))
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
    fun rejectsEmptyInput() {
        assertFalse(isSpokenWordAccepted("книга", emptyList()))
        assertFalse(isSpokenWordAccepted("книга", listOf("", "   ")))
    }
}
