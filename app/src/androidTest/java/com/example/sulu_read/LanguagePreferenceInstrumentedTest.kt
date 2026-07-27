package com.example.sulu_read

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.sulu_read.data.UserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguagePreferenceInstrumentedTest {
    @Test
    fun languagePreferencePersistsAcrossPreferenceInstances() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firstInstance = UserPreferences(context)

        firstInstance.saveLanguageCode("ru")
        assertEquals("ru", firstInstance.languageCode.first())

        val secondInstance = UserPreferences(context)
        assertEquals("ru", secondInstance.languageCode.first())

        secondInstance.saveLanguageCode("kk")
    }
}
