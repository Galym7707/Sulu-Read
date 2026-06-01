package com.example.sulu_read.domain.model

data class SkillProfile(
    val phonologicalSkill: Double,
    val decodingFluency: Double,
    val visualTracking: Double,
    val currentDifficulty: Int
)

data class UserProfile(
    val userId: String,
    val displayName: String,
    val username: String?,
    val age: Int?,
    val languagePreference: String,
    val skillProfile: SkillProfile
)
