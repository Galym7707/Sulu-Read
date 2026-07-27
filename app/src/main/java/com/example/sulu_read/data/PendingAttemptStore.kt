package com.example.sulu_read.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.pendingAttemptDataStore by preferencesDataStore(name = "sulu_read_pending_attempts")

class PendingAttemptStore(private val context: Context) : PendingAttemptQueue {
    private val attemptsKey = stringPreferencesKey("pending_attempts_json")

    val pendingAttempts: Flow<List<PendingAttempt>> = context.pendingAttemptDataStore.data.map { preferences ->
        decodeAttempts(preferences[attemptsKey].orEmpty())
    }

    override val pendingCount: Flow<Int> = pendingAttempts.map { it.size }

    override suspend fun enqueue(attempt: PendingAttempt) {
        context.pendingAttemptDataStore.edit { preferences ->
            val attempts = decodeAttempts(preferences[attemptsKey].orEmpty()) + attempt
            preferences[attemptsKey] = encodeAttempts(attempts)
        }
    }

    suspend fun all(): List<PendingAttempt> = pendingAttempts.first()

    suspend fun removeSynced(ids: Set<String>) {
        if (ids.isEmpty()) return
        context.pendingAttemptDataStore.edit { preferences ->
            val attempts = decodeAttempts(preferences[attemptsKey].orEmpty())
                .filterNot { it.id in ids }
            preferences[attemptsKey] = encodeAttempts(attempts)
        }
    }

    private fun encodeAttempts(attempts: List<PendingAttempt>): String {
        val array = JSONArray()
        attempts.forEach { attempt ->
            array.put(
                JSONObject()
                    .put("id", attempt.id)
                    .put("userId", attempt.userId)
                    .put("exerciseType", attempt.exerciseType)
                    .put("subExercise", attempt.subExercise)
                    .put("targetWord", attempt.targetWord)
                    .put("correctAnswer", attempt.correctAnswer)
                    .put("userAnswer", attempt.userAnswer)
                    .put("responseTimeMs", attempt.responseTimeMs)
                    .put("difficultyLevel", attempt.difficultyLevel)
                    .put("languageHint", attempt.languageHint)
                    .put("createdAtMs", attempt.createdAtMs)
                    .put("status", attempt.status)
            )
        }
        return array.toString()
    }

    private fun decodeAttempts(rawJson: String): List<PendingAttempt> {
        if (rawJson.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(rawJson)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                PendingAttempt(
                    id = item.optString("id").ifBlank { return@mapNotNull null },
                    userId = item.optString("userId"),
                    exerciseType = item.optString("exerciseType"),
                    subExercise = item.optString("subExercise").ifBlank { null },
                    targetWord = item.optString("targetWord"),
                    correctAnswer = item.optString("correctAnswer"),
                    userAnswer = item.optString("userAnswer"),
                    responseTimeMs = item.optLong("responseTimeMs"),
                    difficultyLevel = item.optInt("difficultyLevel"),
                    languageHint = item.optString("languageHint"),
                    createdAtMs = item.optLong("createdAtMs"),
                    status = item.optString("status", "pending")
                )
            }
        }.getOrDefault(emptyList())
    }
}
