package com.example.sulu_read.domain.model

data class ScreeningSummary(
    val id: String,
    val testType: String,
    val wpm: Double,
    val accuracy: Double,
    val supportLevel: String,
    val createdAt: String
)

data class DailyActivity(
    val date: String,
    val exercises: Int,
    val screenings: Int
)

data class DailyWpm(
    val date: String,
    val wpm: Double,
    val accuracy: Double
)

data class ProgressSummary(
    val userId: String,
    val totalExercises: Int,
    val exerciseAccuracy: Double,
    val averageResponseTimeMs: Int,
    val latestSupportLevel: String?,
    val skillProfile: SkillProfile,
    val recentScreenings: List<ScreeningSummary>,
    val dailyActivity: List<DailyActivity>,
    val dailyWpm: List<DailyWpm>
)
