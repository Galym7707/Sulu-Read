package com.example.sulu_read.data.dto

data class AiGenerateRequestDto(
    val task: String,
    val text: String,
    val language: String,
    val level: String? = null,
    val mode: String,
    val extra: Map<String, String> = emptyMap()
)

data class AiGenerateResponseDto(
    val success: Boolean,
    val provider: String? = null,
    val model: String? = null,
    val result: String? = null,
    val error: String? = null
)
