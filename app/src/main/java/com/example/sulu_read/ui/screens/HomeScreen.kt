package com.example.sulu_read.ui.screens

import androidx.compose.runtime.Composable
import com.example.sulu_read.domain.model.AppLanguage

@Composable
fun HomeScreen() {
    ProfileScreen(
        user = null,
        selectedLanguageCode = AppLanguage.defaultCode(),
        onLanguageSelected = {}
    )
}
