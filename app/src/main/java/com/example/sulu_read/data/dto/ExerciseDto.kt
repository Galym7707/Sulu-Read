package com.example.sulu_read.data.dto

data class ExerciseDto(
    val exerciseId: String,
    val type: String,
    val subExercise: String?,
    val prompt: String,
    val targetWord: String,
    val options: List<String>,
    val correctAnswer: String,
    val difficultyLevel: Int,
    val languageHint: String
)

data class ExerciseAttemptResultDto(
    val isCorrect: Boolean,
    val updatedDifficulty: Int,
    val skillProfile: SkillProfileDto,
    val feedback: String
)
