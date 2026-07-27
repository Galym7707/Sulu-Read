package com.example.sulu_read.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sulu_read.R
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
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(text = stringResource(R.string.progress_title), style = MaterialTheme.typography.headlineSmall)
        if (userId == null) {
            LoadingState(message = stringResource(R.string.progress_profile_loading))
            return@Column
        }

        when (val current = state) {
            UiState.Loading -> LoadingState(message = stringResource(R.string.progress_loading))
            is UiState.Error -> ErrorState(message = stringResource(current.messageResId), onRetry = { viewModel.load(userId) })
            is UiState.Success -> ProgressContent(progress = current.data, onRefresh = { viewModel.load(userId) })
        }
    }
}

@Composable
private fun ProgressContent(progress: ProgressSummary, onRefresh: () -> Unit) {
    if (progress.totalExercises == 0) {
        SuluCard {
            Text(text = stringResource(R.string.progress_empty))
        }
    }
    MetricGrid(
        metrics = listOf(
            stringResource(R.string.progress_total_exercises) to progress.totalExercises.toString(),
            stringResource(R.string.progress_accuracy) to "${(progress.exerciseAccuracy * 100).toInt()}%",
            stringResource(R.string.progress_average_response) to "${progress.averageResponseTimeMs} ms",
            stringResource(R.string.progress_current_difficulty) to progress.skillProfile.currentDifficulty.toString(),
            stringResource(R.string.progress_phonological_skill) to "${(progress.skillProfile.phonologicalSkill * 100).toInt()}%",
            stringResource(R.string.progress_decoding_skill) to "${(progress.skillProfile.decodingFluency * 100).toInt()}%"
        )
    )
    Button(onClick = onRefresh) {
        Text(text = stringResource(R.string.progress_refresh))
    }
}

@Composable
private fun MetricGrid(metrics: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        metrics.chunked(2).forEach { rowMetrics ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowMetrics.forEach { (label, value) ->
                    ProgressMetricCard(
                        label = label,
                        value = value,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowMetrics.size == 1) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

