package com.example.sulu_read

import com.example.sulu_read.audio.detectSpeechLanguageCode
import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalTtsTest {
    @Test
    fun detectsKazakhBySpecificLetters() {
        assertEquals("kk", detectSpeechLanguageCode("Қазақстан оқушысы", "ru"))
    }

    @Test
    fun detectsEnglishByLatinLetters() {
        assertEquals("en", detectSpeechLanguageCode("reading support", "kk"))
    }

    @Test
    fun treatsSharedCyrillicAsRussianWhenFallbackIsRussian() {
        assertEquals("ru", detectSpeechLanguageCode("ученик читает", "ru"))
    }

    @Test
    fun keepsCyrillicTextCyrillicDespiteAStrayLatinCharacter() {
        // Testing Latin with `any` handed a whole Russian sentence to the English voice as soon
        // as one Latin character appeared in it — a unit, an abbreviation, a brand name.
        assertEquals("ru", detectSpeechLanguageCode("ученик прочитал 5 km за час", "ru"))
    }

    @Test
    fun keepsCyrillicTextKazakhDespiteAStrayLatinCharacter() {
        assertEquals("kk", detectSpeechLanguageCode("оқушы сағатына 5 km жүрді", "kk"))
    }

    @Test
    fun stillDetectsEnglishWhenLatinDominatesAMixedSentence() {
        assertEquals("en", detectSpeechLanguageCode("the word дом means house", "ru"))
    }

    @Test
    fun fallsBackToTheReaderSettingWhenThereIsNothingToGoOn() {
        assertEquals("kk", detectSpeechLanguageCode("12345", "kk"))
    }
}
