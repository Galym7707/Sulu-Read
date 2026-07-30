package com.example.sulu_read.domain.repository

import com.example.sulu_read.data.PendingAttempt
import com.example.sulu_read.data.PendingAttemptQueue
import com.example.sulu_read.data.SuluReadApi
import com.example.sulu_read.data.UserPreferenceStore
import com.example.sulu_read.data.dto.ExerciseDto
import com.example.sulu_read.domain.model.AppLanguage
import com.example.sulu_read.domain.model.DailyActivity
import com.example.sulu_read.domain.model.DailyWpm
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
import java.util.UUID

class SuluReadRepository(
    private val api: SuluReadApi,
    private val preferences: UserPreferenceStore,
    private val pendingAttemptQueue: PendingAttemptQueue,
    private val schedulePendingAttemptSync: () -> Unit = {}
) {
    private val aiRepository = AiRepository(api)

    val appLanguageCode: Flow<String> = preferences.languageCode
    val readerDisplayPreferences: Flow<ReaderDisplayPreferences> = preferences.readerDisplayPreferences
    val pendingAttemptCount: Flow<Int> = pendingAttemptQueue.pendingCount

    suspend fun ensureUser(): UserProfile {
        val existingUserId = preferences.userId.first()
        if (!existingUserId.isNullOrBlank()) {
            return runCatching { api.getUser(existingUserId).toDomain() }
                .getOrElse { createAnonymousUser() }
        }
        return createAnonymousUser()
    }

    suspend fun registerUser(username: String, password: String, displayName: String): UserProfile {
        val user = api.registerUser(
            username = username,
            password = password,
            displayName = displayName.ifBlank { username },
            languagePreference = preferences.languageCode.first()
        ).toDomain()
        preferences.saveUserId(user.userId)
        return user
    }

    suspend fun loginUser(username: String, password: String): UserProfile {
        val user = api.loginUser(username, password).toDomain()
        preferences.saveUserId(user.userId)
        preferences.saveLanguageCode(user.languagePreference)
        return user
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
        return runCatching {
            val result = api.submitExerciseAttempt(
                userId = userId,
                exerciseType = exercise.type,
                subExercise = exercise.subExercise,
                targetWord = exercise.targetWord,
                correctAnswer = exercise.correctAnswer,
                userAnswer = userAnswer,
                responseTimeMs = responseTimeMs,
                difficultyLevel = exercise.difficultyLevel,
                languageHint = exercise.languageHint
            )
            ExerciseAttemptResult(
                isCorrect = result.isCorrect,
                updatedDifficulty = result.updatedDifficulty,
                skillProfile = result.skillProfile.toDomain(),
                feedback = result.feedback
            )
        }.getOrElse {
            pendingAttemptQueue.enqueue(
                PendingAttempt(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    exerciseType = exercise.type,
                    subExercise = exercise.subExercise,
                    targetWord = exercise.targetWord,
                    correctAnswer = exercise.correctAnswer,
                    userAnswer = userAnswer,
                    responseTimeMs = responseTimeMs,
                    difficultyLevel = exercise.difficultyLevel,
                    languageHint = exercise.languageHint,
                    createdAtMs = System.currentTimeMillis()
                )
            )
            schedulePendingAttemptSync()
            ExerciseAttemptResult(
                isCorrect = normalizeAnswer(exercise.correctAnswer) == normalizeAnswer(userAnswer),
                updatedDifficulty = exercise.difficultyLevel,
                skillProfile = SkillProfile(
                    phonologicalSkill = 0.50,
                    decodingFluency = 0.50,
                    visualTracking = 0.50,
                    currentDifficulty = exercise.difficultyLevel
                ),
                feedback = "",
                isPendingSync = true
            )
        }
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
            },
            dailyWpm = progress.dailyWpm.map {
                DailyWpm(
                    date = it.date,
                    wpm = it.wpm,
                    accuracy = it.accuracy
                )
            }
        )
    }

    suspend fun simplify(text: String, languageCode: String? = null): String {
        val resolvedLanguageCode = languageCode ?: preferences.languageCode.first()
        return api.simplify(text, AppLanguage.backendHintFor(resolvedLanguageCode)).simplifiedText
    }

    suspend fun generateAiHelp(
        task: String,
        text: String,
        languageCode: String,
        level: String? = null,
        mode: String = "explain",
        extra: Map<String, String> = emptyMap()
    ): String {
        val response = aiRepository.generateAiHelp(
            task = task,
            text = text,
            languageCode = languageCode,
            level = level,
            mode = mode,
            extra = extra
        )
        if (!response.success) {
            throw IllegalStateException(response.error ?: "AI help is unavailable.")
        }
        return response.result?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("AI returned an empty response.")
    }

    suspend fun explainTextWithAi(text: String, languageCode: String): String {
        val response = aiRepository.explainTextWithAi(text, languageCode)
        if (!response.success) {
            throw IllegalStateException(response.error ?: "AI help is unavailable.")
        }
        return response.result?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("AI returned an empty response.")
    }

    suspend fun hintForWord(word: String, languageCode: String): String {
        val response = aiRepository.hintForWordWithAi(word, languageCode)
        if (!response.success) {
            throw IllegalStateException(response.error ?: "AI help is unavailable.")
        }
        return response.result?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("AI returned an empty response.")
    }

    suspend fun checkAnswerWithAi(
        question: String,
        correctAnswer: String,
        userAnswer: String,
        languageCode: String
    ): String {
        val response = aiRepository.checkAnswerWithAi(question, correctAnswer, userAnswer, languageCode)
        if (!response.success) {
            throw IllegalStateException(response.error ?: "AI help is unavailable.")
        }
        return response.result?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("AI returned an empty response.")
    }

    suspend fun generateExerciseWithAi(text: String, languageCode: String, level: String? = null): String {
        val response = aiRepository.generateExerciseWithAi(text, languageCode, level)
        if (!response.success) {
            throw IllegalStateException(response.error ?: "AI help is unavailable.")
        }
        return response.result?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("AI returned an empty response.")
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
        username = username,
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
        subExercise = subExercise,
        prompt = prompt,
        targetWord = targetWord,
        syllables = syllables,
        options = options,
        correctAnswer = correctAnswer,
        difficultyLevel = difficultyLevel,
        languageHint = languageHint
    )
}

private fun normalizeAnswer(answer: String): String {
    return answer.trim().lowercase().replace(" ", "")
}
