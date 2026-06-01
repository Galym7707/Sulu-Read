package com.example.sulu_read.data.dto

data class SkillProfileDto(
    val phonologicalSkill: Double,
    val decodingFluency: Double,
    val visualTracking: Double,
    val currentDifficulty: Int
)

data class UserDto(
    val userId: String,
    val displayName: String,
    val username: String?,
    val age: Int?,
    val languagePreference: String,
    val skillProfile: SkillProfileDto
)
