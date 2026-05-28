package com.example.sulu_read.data.dto

data class AdaptedWordDto(
    val original: String,
    val adapted: String,
    val syllables: List<String>,
    val languageHint: String
)

data class SimplifyDto(
    val status: String,
    val simplifiedText: String
)
