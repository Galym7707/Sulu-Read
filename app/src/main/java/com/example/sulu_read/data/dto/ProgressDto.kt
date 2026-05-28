package com.example.sulu_read.data.dto

data class ScreeningSummaryDto(
    val id: String,
    val testType: String,
    val wpm: Double,
    val accuracy: Double,
    val supportLevel: String,
    val createdAt: String
)

data class DailyActivityDto(
    val date: String,
    val exercises: Int,
    val screenings: Int
)

data class ProgressDto(
    val userId: String,
    val totalExercises: Int,
    val exerciseAccuracy: Double,
    val averageResponseTimeMs: Int,
    val latestSupportLevel: String?,
    val skillProfile: SkillProfileDto,
    val recentScreenings: List<ScreeningSummaryDto>,
    val dailyActivity: List<DailyActivityDto>
)
