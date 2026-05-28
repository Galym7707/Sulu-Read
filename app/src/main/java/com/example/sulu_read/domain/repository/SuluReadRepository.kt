package com.example.sulu_read.domain.repository

import com.example.sulu_read.data.SuluReadApi
import com.example.sulu_read.data.UserPreferences
import com.example.sulu_read.data.dto.ExerciseDto
import com.example.sulu_read.domain.model.AppLanguage
import com.example.sulu_read.domain.model.DailyActivity
import com.example.sulu_read.domain.model.Exercise
import com.example.sulu_read.domain.model.ExerciseAttemptResult
import com.example.sulu_read.domain.model.ProgressSummary
import com.example.sulu_read.domain.model.ReaderDisplayPreferences
import com.example.sulu_read.domain.model.ScreeningResult
import com.example.sulu_read.domain.model.ScreeningSummary
import com.example.sulu_read.domain.model.SkillProfile
import com.example.sulu_read.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class SuluReadRepository(
    private val api: SuluReadApi,
    private val preferences: UserPreferences
) {
    val appLanguageCode: Flow<String> = preferences.languageCode
    val readerDisplayPreferences: Flow<ReaderDisplayPreferences> = preferences.readerDisplayPreferences

    suspend fun ensureUser(): UserProfile {
        val existingUserId = preferences.userId.first()
        if (!existingUserId.isNullOrBlank()) {
            return runCatching { api.getUser(existingUserId).toDomain() }
                .getOrElse { createAnonymousUser() }
        }
        return createAnonymousUser()
    }

    suspend fun generateExercises(
        userId: String,
        sourceWords: List<String>,
        count: Int = 5,
        languageCode: String? = null
    ): List<Exercise> {
        val resolvedLanguageCode = languageCode ?: preferences.languageCode.first()
        return api.generateExercises(
            userId = userId,
            sourceWords = sourceWords,
            exerciseType = "mixed",
            count = count.coerceIn(1, 10),
            languageHint = AppLanguage.backendHintFor(resolvedLanguageCode)
        ).map { it.toDomain() }
    }

    suspend fun submitExerciseAttempt(
        userId: String,
        exercise: Exercise,
        userAnswer: String,
        responseTimeMs: Long
    ): ExerciseAttemptResult {
        val result = api.submitExerciseAttempt(
            userId = userId,
            exerciseType = exercise.type,
            targetWord = exercise.targetWord,
            correctAnswer = exercise.correctAnswer,
            userAnswer = userAnswer,
            responseTimeMs = responseTimeMs,
            difficultyLevel = exercise.difficultyLevel,
            languageHint = exercise.languageHint
        )
        return ExerciseAttemptResult(
            isCorrect = result.isCorrect,
            updatedDifficulty = result.updatedDifficulty,
            skillProfile = result.skillProfile.toDomain(),
            feedback = result.feedback
        )
    }

    suspend fun submitReadingTest(
        userId: String,
        wordsTotal: Int,
        wordsReadCorrectly: Int,
        errorsCount: Int,
        durationMs: Long
    ): ScreeningResult {
        val result = api.submitReadingTest(
            userId = userId,
            wordsTotal = wordsTotal,
            wordsReadCorrectly = wordsReadCorrectly,
            errorsCount = errorsCount,
            durationMs = durationMs
        )
        return ScreeningResult(
            wpm = result.wpm,
            accuracy = result.accuracy,
            supportLevel = result.supportLevel,
            disclaimer = result.disclaimer
        )
    }

    suspend fun getProgress(userId: String): ProgressSummary {
        val progress = api.getProgress(userId)
        return ProgressSummary(
            userId = progress.userId,
            totalExercises = progress.totalExercises,
            exerciseAccuracy = progress.exerciseAccuracy,
            averageResponseTimeMs = progress.averageResponseTimeMs,
            latestSupportLevel = progress.latestSupportLevel,
            skillProfile = progress.skillProfile.toDomain(),
            recentScreenings = progress.recentScreenings.map {
                ScreeningSummary(
                    id = it.id,
                    testType = it.testType,
                    wpm = it.wpm,
                    accuracy = it.accuracy,
                    supportLevel = it.supportLevel,
                    createdAt = it.createdAt
                )
            },
            dailyActivity = progress.dailyActivity.map {
                DailyActivity(
                    date = it.date,
                    exercises = it.exercises,
                    screenings = it.screenings
                )
            }
        )
    }

    suspend fun simplify(text: String, languageCode: String? = null): String {
        val resolvedLanguageCode = languageCode ?: preferences.languageCode.first()
        return api.simplify(text, AppLanguage.backendHintFor(resolvedLanguageCode)).simplifiedText
    }

    suspend fun saveLanguageCode(languageCode: String, userId: String? = null) {
        val normalizedCode = AppLanguage.normalizeCode(languageCode)
        preferences.saveLanguageCode(normalizedCode)
        if (!userId.isNullOrBlank()) {
            runCatching {
                api.updateUserLanguage(userId, normalizedCode)
            }
        }
    }

    suspend fun saveReaderDisplayPreferences(readerPreferences: ReaderDisplayPreferences) {
        preferences.saveReaderDisplayPreferences(readerPreferences)
    }

    private suspend fun createAnonymousUser(): UserProfile {
        val user = api.createUser(languagePreference = preferences.languageCode.first()).toDomain()
        preferences.saveUserId(user.userId)
        return user
    }
}

private fun com.example.sulu_read.data.dto.UserDto.toDomain(): UserProfile {
    return UserProfile(
        userId = userId,
        displayName = displayName,
        age = age,
        languagePreference = languagePreference,
        skillProfile = skillProfile.toDomain()
    )
}

private fun com.example.sulu_read.data.dto.SkillProfileDto.toDomain(): SkillProfile {
    return SkillProfile(
        phonologicalSkill = phonologicalSkill,
        decodingFluency = decodingFluency,
        visualTracking = visualTracking,
        currentDifficulty = currentDifficulty
    )
}

private fun ExerciseDto.toDomain(): Exercise {
    return Exercise(
        exerciseId = exerciseId,
        type = type,
        prompt = prompt,
        targetWord = targetWord,
        syllables = syllables,
        options = options,
        correctAnswer = correctAnswer,
        difficultyLevel = difficultyLevel,
        languageHint = languageHint
    )
}
