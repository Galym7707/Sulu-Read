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
}
