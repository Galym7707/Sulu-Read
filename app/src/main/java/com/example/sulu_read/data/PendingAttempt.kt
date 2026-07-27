package com.example.sulu_read.data

data class PendingAttempt(
    val id: String,
    val userId: String,
    val exerciseType: String,
    val subExercise: String?,
    val targetWord: String,
    val correctAnswer: String,
    val userAnswer: String,
    val responseTimeMs: Long,
    val difficultyLevel: Int,
    val languageHint: String,
    val createdAtMs: Long,
    val status: String = "pending"
)
