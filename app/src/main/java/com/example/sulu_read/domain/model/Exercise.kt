package com.example.sulu_read.domain.model

data class Exercise(
    val exerciseId: String,
    val type: String,
    val prompt: String,
    val targetWord: String,
    val syllables: List<String>,
    val options: List<String>,
    val correctAnswer: String,
    val difficultyLevel: Int,
    val languageHint: String
)

data class ExerciseAttemptResult(
    val isCorrect: Boolean,
    val updatedDifficulty: Int,
    val skillProfile: SkillProfile,
    val feedback: String
)
