package com.example.sulu_read

import com.example.sulu_read.data.PendingAttempt
import com.example.sulu_read.data.PendingAttemptQueue
import com.example.sulu_read.data.SuluReadApi
import com.example.sulu_read.data.UserPreferenceStore
import com.example.sulu_read.data.dto.ExerciseAttemptResultDto
import com.example.sulu_read.data.dto.ExerciseDto
import com.example.sulu_read.data.dto.ProgressDto
import com.example.sulu_read.data.dto.ScreeningResultDto
import com.example.sulu_read.data.dto.SimplifyDto
import com.example.sulu_read.data.dto.UserDto
import com.example.sulu_read.domain.model.Exercise
import com.example.sulu_read.domain.model.ReaderDisplayPreferences
import com.example.sulu_read.domain.repository.SuluReadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OfflineAttemptQueueTest {
    @Test
    fun failedExerciseAttemptIsSavedAsPending() = runBlocking {
        val queue = FakePendingAttemptQueue()
        var syncScheduled = false
        val repository = SuluReadRepository(
            api = FailingAttemptApi,
            preferences = FakePreferences,
            pendingAttemptQueue = queue,
            schedulePendingAttemptSync = { syncScheduled = true }
        )
        val exercise = Exercise(
            exerciseId = "exercise-1",
            type = "root_suffix_identification",
            subExercise = "morphology",
            prompt = "Choose root",
            targetWord = "балаларымызға",
            syllables = listOf("бала", "лар", "ымыз", "ға"),
            options = listOf("бала + лар + ымыз + ға"),
            correctAnswer = "бала + лар + ымыз + ға",
            difficultyLevel = 3,
            languageHint = "kk"
        )

        val result = repository.submitExerciseAttempt(
            userId = "user-1",
            exercise = exercise,
            userAnswer = "бала + лар + ымыз + ға",
            responseTimeMs = 2_500
        )

        assertTrue(result.isPendingSync)
        assertTrue(result.isCorrect)
        assertTrue(syncScheduled)
        assertEquals(1, queue.attempts.size)
        assertEquals("pending", queue.attempts.single().status)
        assertEquals("morphology", queue.attempts.single().subExercise)
    }
}

private object FailingAttemptApi : SuluReadApi {
    override suspend fun createUser(displayName: String, age: Int?, languagePreference: String): UserDto = error("unused")
    override suspend fun getUser(userId: String): UserDto = error("unused")
    override suspend fun updateUserLanguage(userId: String, languagePreference: String): UserDto = error("unused")
    override suspend fun generateExercises(
        userId: String,
        sourceWords: List<String>,
        exerciseType: String,
        count: Int,
        languageHint: String
    ): List<ExerciseDto> = error("unused")

    override suspend fun submitExerciseAttempt(
        userId: String,
        exerciseType: String,
        subExercise: String?,
        targetWord: String,
        correctAnswer: String,
        userAnswer: String,
        responseTimeMs: Long,
        difficultyLevel: Int,
        languageHint: String
    ): ExerciseAttemptResultDto {
        throw IOException("network unavailable")
    }

    override suspend fun submitReadingTest(
        userId: String,
        wordsTotal: Int,
        wordsReadCorrectly: Int,
        errorsCount: Int,
        durationMs: Long,
        testType: String
    ): ScreeningResultDto = error("unused")

    override suspend fun getProgress(userId: String): ProgressDto = error("unused")
    override suspend fun simplify(text: String, languageHint: String): SimplifyDto = error("unused")
}

private object FakePreferences : UserPreferenceStore {
    override val userId: Flow<String?> = MutableStateFlow("user-1")
    override val languageCode: Flow<String> = MutableStateFlow("kk")
    override val readerDisplayPreferences: Flow<ReaderDisplayPreferences> = MutableStateFlow(ReaderDisplayPreferences())
    override suspend fun saveUserId(userId: String) = Unit
    override suspend fun saveLanguageCode(languageCode: String) = Unit
    override suspend fun saveReaderDisplayPreferences(readerPreferences: ReaderDisplayPreferences) = Unit
}

private class FakePendingAttemptQueue : PendingAttemptQueue {
    val attempts = mutableListOf<PendingAttempt>()
    private val count = MutableStateFlow(0)
    override val pendingCount: Flow<Int> = count

    override suspend fun enqueue(attempt: PendingAttempt) {
        attempts.add(attempt)
        count.value = attempts.size
    }
}
