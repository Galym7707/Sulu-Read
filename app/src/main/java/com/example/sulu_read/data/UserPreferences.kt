package com.example.sulu_read.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.suluReadDataStore by preferencesDataStore(name = "sulu_read_user")

class UserPreferences(private val context: Context) {
    val userId: Flow<String?> = context.suluReadDataStore.data.map { preferences ->
        preferences[USER_ID_KEY]
    }

    suspend fun saveUserId(userId: String) {
        context.suluReadDataStore.edit { preferences ->
            preferences[USER_ID_KEY] = userId
        }
    }

    private companion object {
        val USER_ID_KEY = stringPreferencesKey("user_id")
    }
}
