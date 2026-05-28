package com.example.sulu_read.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sulu_read.domain.model.UserProfile
import com.example.sulu_read.ui.components.SuluCard

@Composable
fun ProfileScreen(user: UserProfile?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(text = "Профиль", style = MaterialTheme.typography.headlineSmall)
        SuluCard {
            Text(text = "Оқушы: ${user?.displayName ?: "..." }")
            Text(text = "User ID: ${user?.userId ?: "..."}")
            Text(text = "Тіл: ${user?.languagePreference ?: "kk-ru"}")
        }
    }
}
