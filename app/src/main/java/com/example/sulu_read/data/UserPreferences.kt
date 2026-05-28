package com.example.sulu_read.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.sulu_read.domain.model.AppLanguage
import com.example.sulu_read.domain.model.ReaderDisplayPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.suluReadDataStore by preferencesDataStore(name = "sulu_read_user")

class UserPreferences(private val context: Context) {
    val userId: Flow<String?> = context.suluReadDataStore.data.map { preferences ->
        preferences[USER_ID_KEY]
    }

    val languageCode: Flow<String> = context.suluReadDataStore.data.map { preferences ->
        AppLanguage.normalizeCode(
            preferences[LANGUAGE_CODE_KEY] ?: AppLanguage.defaultCode()
        )
    }

    val readerDisplayPreferences: Flow<ReaderDisplayPreferences> = context.suluReadDataStore.data.map { preferences ->
        ReaderDisplayPreferences(
            showSyllableBreaks = preferences[SHOW_SYLLABLE_BREAKS_KEY] ?: true,
            colorSyllables = preferences[COLOR_SYLLABLES_KEY] ?: true,
            useOriginalWords = preferences[USE_ORIGINAL_WORDS_KEY] ?: false
        )
    }

    suspend fun saveUserId(userId: String) {
        context.suluReadDataStore.edit { preferences ->
            preferences[USER_ID_KEY] = userId
        }
    }

    suspend fun saveLanguageCode(languageCode: String) {
        context.suluReadDataStore.edit { preferences ->
            preferences[LANGUAGE_CODE_KEY] = AppLanguage.normalizeCode(languageCode)
        }
    }

    suspend fun saveReaderDisplayPreferences(readerPreferences: ReaderDisplayPreferences) {
        context.suluReadDataStore.edit { preferences ->
            preferences[SHOW_SYLLABLE_BREAKS_KEY] = readerPreferences.showSyllableBreaks
            preferences[COLOR_SYLLABLES_KEY] = readerPreferences.colorSyllables
            preferences[USE_ORIGINAL_WORDS_KEY] = readerPreferences.useOriginalWords
        }
    }

    private companion object {
        val USER_ID_KEY = stringPreferencesKey("user_id")
        val LANGUAGE_CODE_KEY = stringPreferencesKey("language_code")
        val SHOW_SYLLABLE_BREAKS_KEY = booleanPreferencesKey("reader_show_syllable_breaks")
        val COLOR_SYLLABLES_KEY = booleanPreferencesKey("reader_color_syllables")
        val USE_ORIGINAL_WORDS_KEY = booleanPreferencesKey("reader_use_original_words")
    }
}
