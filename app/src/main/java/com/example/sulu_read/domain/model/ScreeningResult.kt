package com.example.sulu_read.domain.model

data class ScreeningResult(
    val wpm: Double,
    val accuracy: Double,
    val supportLevel: String,
    val disclaimer: String
)
