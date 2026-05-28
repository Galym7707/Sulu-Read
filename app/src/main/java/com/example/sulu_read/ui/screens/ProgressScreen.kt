package com.example.sulu_read.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sulu_read.domain.model.ProgressSummary
import com.example.sulu_read.ui.components.ErrorState
import com.example.sulu_read.ui.components.LoadingState
import com.example.sulu_read.ui.components.ProgressMetricCard
import com.example.sulu_read.ui.components.SuluCard

@Composable
fun ProgressScreen(
    userId: String?,
    viewModel: ProgressViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(userId) {
        if (userId != null) {
            viewModel.load(userId)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(text = "Прогресс", style = MaterialTheme.typography.headlineSmall)
        if (userId == null) {
            LoadingState(message = "Профиль дайындалып жатыр...")
            return@Column
        }

        when (val current = state) {
            UiState.Loading -> LoadingState(message = "Прогресс жүктелуде...")
            is UiState.Error -> ErrorState(message = current.message, onRetry = { viewModel.load(userId) })
            is UiState.Success -> ProgressContent(progress = current.data, onRefresh = { viewModel.load(userId) })
        }
    }
}

@Composable
private fun ProgressContent(progress: ProgressSummary, onRefresh: () -> Unit) {
    if (progress.totalExercises == 0 && progress.recentScreenings.isEmpty()) {
        SuluCard {
            Text(text = "Әзірге дерек жоқ. Жаттығу немесе оқу бағалауын бастаңыз.")
        }
    }
    ProgressMetricCard(label = "Жаттығулар", value = progress.totalExercises.toString())
    ProgressMetricCard(label = "Дәлдік", value = "${(progress.exerciseAccuracy * 100).toInt()}%")
    ProgressMetricCard(label = "Орташа жауап", value = "${progress.averageResponseTimeMs} ms")
    ProgressMetricCard(label = "Қиындық деңгейі", value = progress.skillProfile.currentDifficulty.toString())
    ProgressMetricCard(label = "Фонологиялық дағды", value = "${(progress.skillProfile.phonologicalSkill * 100).toInt()}%")
    progress.latestSupportLevel?.let {
        ProgressMetricCard(label = "Support level", value = it)
    }
    Button(onClick = onRefresh) {
        Text(text = "Жаңарту")
    }
}
