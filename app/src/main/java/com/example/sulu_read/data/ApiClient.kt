package com.example.sulu_read.data

import com.example.sulu_read.data.dto.DailyActivityDto
import com.example.sulu_read.data.dto.DailyWpmDto
import com.example.sulu_read.data.dto.ExerciseAttemptResultDto
import com.example.sulu_read.data.dto.ExerciseDto
import com.example.sulu_read.data.dto.ProgressDto
import com.example.sulu_read.data.dto.ScreeningResultDto
import com.example.sulu_read.data.dto.ScreeningSummaryDto
import com.example.sulu_read.data.dto.SimplifyDto
import com.example.sulu_read.data.dto.SkillProfileDto
import com.example.sulu_read.data.dto.UserDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ApiClient : SuluReadApi {
    val backendBaseUrls: List<String> = listOf(
        "https://galym7707-sulu-read-backend.hf.space",
        "http://10.0.2.2:8000",
        "http://192.168.0.100:8000",
        "http://192.168.0.103:8000"
    )

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 240_000

    override suspend fun createUser(displayName: String, age: Int?, languagePreference: String): UserDto {
        val payload = JSONObject()
            .put("display_name", displayName)
            .put("age", age)
            .put("language_preference", languagePreference)
        return parseUser(postJson("/v1/users", payload))
    }

    override suspend fun getUser(userId: String): UserDto {
        return parseUser(getJson("/v1/users/$userId"))
    }

    override suspend fun updateUserLanguage(userId: String, languagePreference: String): UserDto {
        val payload = JSONObject()
            .put("language_preference", languagePreference)
        return parseUser(patchJson("/v1/users/$userId/language", payload))
    }

    override suspend fun generateExercises(
        userId: String,
        sourceWords: List<String>,
        exerciseType: String,
        count: Int,
        languageHint: String
    ): List<ExerciseDto> {
        val payload = JSONObject()
            .put("user_id", userId)
            .put("source_words", JSONArray(sourceWords))
            .put("exercise_type", exerciseType)
            .put("count", count)
            .put("language_hint", languageHint)
        val response = postJsonArray("/v1/exercises/generate", payload)
        return (0 until response.length()).map { index -> parseExercise(response.getJSONObject(index)) }
    }

    override suspend fun submitExerciseAttempt(
        userId: String,
        exerciseType: String,
        targetWord: String,
        correctAnswer: String,
        userAnswer: String,
        responseTimeMs: Long,
        difficultyLevel: Int,
        languageHint: String
    ): ExerciseAttemptResultDto {
        val payload = JSONObject()
            .put("user_id", userId)
            .put("exercise_type", exerciseType)
            .put("target_word", targetWord)
            .put("correct_answer", correctAnswer)
            .put("user_answer", userAnswer)
            .put("response_time_ms", responseTimeMs)
            .put("difficulty_level", difficultyLevel)
            .put("language_hint", languageHint)
        val json = postJson("/v1/exercises/attempt", payload)
        return ExerciseAttemptResultDto(
            isCorrect = json.optBoolean("is_correct"),
            updatedDifficulty = json.optInt("updated_difficulty"),
            skillProfile = parseSkillProfile(json.getJSONObject("skill_profile")),
            feedback = json.optString("feedback")
        )
    }

    override suspend fun submitReadingTest(
        userId: String,
        wordsTotal: Int,
        wordsReadCorrectly: Int,
        errorsCount: Int,
        durationMs: Long,
        testType: String
    ): ScreeningResultDto {
        val payload = JSONObject()
            .put("user_id", userId)
            .put("words_total", wordsTotal)
            .put("words_read_correctly", wordsReadCorrectly)
            .put("errors_count", errorsCount)
            .put("duration_ms", durationMs)
            .put("test_type", testType)
        val json = postJson("/v1/screening/reading-test", payload)
        return ScreeningResultDto(
            wpm = json.optDouble("wpm"),
            accuracy = json.optDouble("accuracy"),
            supportLevel = json.optString("support_level"),
            disclaimer = json.optString("disclaimer")
        )
    }

    override suspend fun getProgress(userId: String): ProgressDto {
        val json = getJson("/v1/progress/$userId")
        val screenings = json.optJSONArray("recent_screenings") ?: JSONArray()
        val daily = json.optJSONArray("daily_activity") ?: JSONArray()
        val dailyWpm = json.optJSONArray("daily_wpm") ?: JSONArray()
        return ProgressDto(
            userId = json.optString("user_id"),
            totalExercises = json.optInt("total_exercises"),
            exerciseAccuracy = json.optDouble("exercise_accuracy"),
            averageResponseTimeMs = json.optInt("average_response_time_ms"),
            latestSupportLevel = json.optString("latest_support_level").ifBlank { null },
            skillProfile = parseSkillProfile(json.getJSONObject("skill_profile")),
            recentScreenings = (0 until screenings.length()).map { index ->
                val item = screenings.getJSONObject(index)
                ScreeningSummaryDto(
                    id = item.optString("id"),
                    testType = item.optString("test_type"),
                    wpm = item.optDouble("wpm"),
                    accuracy = item.optDouble("accuracy"),
                    supportLevel = item.optString("support_level"),
                    createdAt = item.optString("created_at")
                )
            },
            dailyActivity = (0 until daily.length()).map { index ->
                val item = daily.getJSONObject(index)
                DailyActivityDto(
                    date = item.optString("date"),
                    exercises = item.optInt("exercises"),
                    screenings = item.optInt("screenings")
                )
            },
            dailyWpm = (0 until dailyWpm.length()).map { index ->
                val item = dailyWpm.getJSONObject(index)
                DailyWpmDto(
                    date = item.optString("date"),
                    wpm = item.optDouble("wpm"),
                    accuracy = item.optDouble("accuracy")
                )
            }
        )
    }

    override suspend fun simplify(text: String, languageHint: String): SimplifyDto {
        val payload = JSONObject()
            .put("text", text)
            .put("language_hint", languageHint)
        val json = postJson("/v1/simplify", payload)
        return SimplifyDto(
            status = json.optString("status"),
            simplifiedText = json.optString("simplified_text")
        )
    }

    suspend fun postJson(path: String, payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        request(path = path, method = "POST", payload = payload).let(::JSONObject)
    }

    private suspend fun patchJson(path: String, payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        request(path = path, method = "PATCH", payload = payload).let(::JSONObject)
    }

    private suspend fun postJsonArray(path: String, payload: JSONObject): JSONArray = withContext(Dispatchers.IO) {
        request(path = path, method = "POST", payload = payload).let(::JSONArray)
    }

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        request(path = path, method = "GET", payload = null).let(::JSONObject)
    }

    private fun request(path: String, method: String, payload: JSONObject?): String {
        var lastError: Exception? = null
        for (baseUrl in backendBaseUrls) {
            try {
                val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doInput = true
                    useCaches = false
                    setRequestProperty("Accept", "application/json")
                    if (payload != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }
                }
                try {
                    if (payload != null) {
                        BufferedOutputStream(connection.outputStream).use { output ->
                            output.write(payload.toString().toByteArray(Charsets.UTF_8))
                        }
                    }
                    val body = readBody(connection)
                    if (connection.responseCode in 200..299) {
                        return body
                    }
                    lastError = IllegalStateException(body.ifBlank { "HTTP ${connection.responseCode}" })
                } finally {
                    connection.disconnect()
                }
            } catch (exception: Exception) {
                lastError = exception
            }
        }
        throw lastError ?: IllegalStateException("Backend is unavailable")
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun parseUser(json: JSONObject): UserDto {
        return UserDto(
            userId = json.optString("user_id"),
            displayName = json.optString("display_name"),
            age = json.opt("age")?.takeIf { it != JSONObject.NULL } as? Int,
            languagePreference = json.optString("language_preference"),
            skillProfile = parseSkillProfile(json.getJSONObject("skill_profile"))
        )
    }

    private fun parseSkillProfile(json: JSONObject): SkillProfileDto {
        return SkillProfileDto(
            phonologicalSkill = json.optDouble("phonological_skill"),
            decodingFluency = json.optDouble("decoding_fluency"),
            visualTracking = json.optDouble("visual_tracking"),
            currentDifficulty = json.optInt("current_difficulty")
        )
    }

    private fun parseExercise(json: JSONObject): ExerciseDto {
        return ExerciseDto(
            exerciseId = json.optString("exercise_id"),
            type = json.optString("type"),
            prompt = json.optString("prompt"),
            targetWord = json.optString("target_word"),
            syllables = json.optJSONArray("syllables").toStringList(),
            options = json.optJSONArray("options").toStringList(),
            correctAnswer = json.optString("correct_answer"),
            difficultyLevel = json.optInt("difficulty_level"),
            languageHint = json.optString("language_hint")
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { index -> optString(index) }
    }
}
