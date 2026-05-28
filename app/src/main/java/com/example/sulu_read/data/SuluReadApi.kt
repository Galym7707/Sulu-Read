package com.example.sulu_read.data

import com.example.sulu_read.data.dto.ExerciseAttemptResultDto
import com.example.sulu_read.data.dto.ExerciseDto
import com.example.sulu_read.data.dto.ProgressDto
import com.example.sulu_read.data.dto.ScreeningResultDto
import com.example.sulu_read.data.dto.SimplifyDto
import com.example.sulu_read.data.dto.UserDto

interface SuluReadApi {
    suspend fun createUser(displayName: String = "Оқушы", age: Int? = null, languagePreference: String = "kk"): UserDto
    suspend fun getUser(userId: String): UserDto
    suspend fun updateUserLanguage(userId: String, languagePreference: String): UserDto
    suspend fun generateExercises(
        userId: String,
        sourceWords: List<String>,
        exerciseType: String,
        count: Int,
        languageHint: String
    ): List<ExerciseDto>
    suspend fun submitExerciseAttempt(
        userId: String,
        exerciseType: String,
        targetWord: String,
        correctAnswer: String,
        userAnswer: String,
        responseTimeMs: Long,
        difficultyLevel: Int,
        languageHint: String
    ): ExerciseAttemptResultDto
    suspend fun submitReadingTest(
        userId: String,
        wordsTotal: Int,
        wordsReadCorrectly: Int,
        errorsCount: Int,
        durationMs: Long,
        testType: String = "short_reading"
    ): ScreeningResultDto
    suspend fun getProgress(userId: String): ProgressDto
    suspend fun simplify(text: String, languageHint: String): SimplifyDto
}
