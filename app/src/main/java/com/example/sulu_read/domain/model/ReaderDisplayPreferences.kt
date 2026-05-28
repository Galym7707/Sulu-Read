package com.example.sulu_read.domain.model

data class ReaderDisplayPreferences(
    val showSyllableBreaks: Boolean = true,
    val colorSyllables: Boolean = true,
    val useOriginalWords: Boolean = false
)
