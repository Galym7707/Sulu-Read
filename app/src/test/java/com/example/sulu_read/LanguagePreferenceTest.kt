package com.example.sulu_read

import com.example.sulu_read.domain.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguagePreferenceTest {
    @Test
    fun defaultCodeUsesSupportedSystemLanguage() {
        assertEquals("en", AppLanguage.defaultCode("en"))
        assertEquals("ru", AppLanguage.defaultCode("ru"))
        assertEquals("kk", AppLanguage.defaultCode("kk"))
    }

    @Test
    fun defaultCodeFallsBackToKazakhForUnsupportedLanguage() {
        assertEquals("kk", AppLanguage.defaultCode("de"))
        assertEquals("kk", AppLanguage.defaultCode(""))
    }

    @Test
    fun backendHintMatchesSelectedLanguage() {
        assertEquals("en", AppLanguage.backendHintFor("en"))
        assertEquals("ru", AppLanguage.backendHintFor("ru"))
        assertEquals("kk", AppLanguage.backendHintFor("kk"))
        assertEquals("kk", AppLanguage.backendHintFor("unknown"))
    }
}
