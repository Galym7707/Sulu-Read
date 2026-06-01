package com.example.sulu_read.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sulu_read.R
import com.example.sulu_read.domain.model.DailyWpm
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
            .padding(18.dp),
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
    if (progress.totalExercises == 0 && progress.recentScreenings.isEmpty()) {
        SuluCard {
            Text(text = stringResource(R.string.progress_empty))
        }
    }
    ProgressMetricCard(label = stringResource(R.string.progress_total_exercises), value = progress.totalExercises.toString())
    ProgressMetricCard(label = stringResource(R.string.progress_accuracy), value = "${(progress.exerciseAccuracy * 100).toInt()}%")
    ProgressMetricCard(label = stringResource(R.string.progress_average_response), value = "${progress.averageResponseTimeMs} ms")
    ProgressMetricCard(label = stringResource(R.string.progress_current_difficulty), value = progress.skillProfile.currentDifficulty.toString())
    ProgressMetricCard(label = stringResource(R.string.progress_phonological_skill), value = "${(progress.skillProfile.phonologicalSkill * 100).toInt()}%")
    progress.latestSupportLevel?.let {
        ProgressMetricCard(label = stringResource(R.string.progress_support_level), value = it)
    }
    DailyWpmChart(dailyWpm = progress.dailyWpm)
    Button(onClick = onRefresh) {
        Text(text = stringResource(R.string.progress_refresh))
    }
}

@Composable
private fun DailyWpmChart(dailyWpm: List<DailyWpm>) {
    SuluCard {
        Text(
            text = stringResource(R.string.progress_chart_title),
            style = MaterialTheme.typography.titleMedium
        )
        if (dailyWpm.isEmpty()) {
            Text(
                text = stringResource(R.string.progress_chart_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val points = dailyWpm.takeLast(14)
            val wpmColor = Color(0xFF2F6F9F)
            val accuracyColor = Color(0xFFB7791F)
            val axisColor = MaterialTheme.colorScheme.outline
            val maxWpm = points.maxOfOrNull { it.wpm }?.coerceAtLeast(1.0) ?: 1.0

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .padding(top = 16.dp, bottom = 10.dp)
            ) {
                val top = 10.dp.toPx()
                val bottom = size.height - 16.dp.toPx()
                val chartHeight = bottom - top
                val lastIndex = (points.size - 1).coerceAtLeast(1)

                fun point(index: Int, normalized: Double): Offset {
                    val x = if (points.size == 1) size.width / 2f else size.width * index / lastIndex
                    val y = bottom - (normalized.coerceIn(0.0, 1.0).toFloat() * chartHeight)
                    return Offset(x, y)
                }

                drawLine(
                    color = axisColor,
                    start = Offset(0f, bottom),
                    end = Offset(size.width, bottom),
                    strokeWidth = 1.dp.toPx()
                )

                val wpmPath = Path()
                val accuracyPath = Path()
                points.forEachIndexed { index, value ->
                    val wpmPoint = point(index, value.wpm / maxWpm)
                    val accuracyPoint = point(index, value.accuracy)
                    if (index == 0) {
                        wpmPath.moveTo(wpmPoint.x, wpmPoint.y)
                        accuracyPath.moveTo(accuracyPoint.x, accuracyPoint.y)
                    } else {
                        wpmPath.lineTo(wpmPoint.x, wpmPoint.y)
                        accuracyPath.lineTo(accuracyPoint.x, accuracyPoint.y)
                    }
                    drawCircle(wpmColor, radius = 3.5.dp.toPx(), center = wpmPoint)
                    drawCircle(accuracyColor, radius = 3.5.dp.toPx(), center = accuracyPoint)
                }
                drawPath(wpmPath, color = wpmColor, style = Stroke(width = 3.dp.toPx()))
                drawPath(accuracyPath, color = accuracyColor, style = Stroke(width = 3.dp.toPx()))
            }

            Text(
                text = "${points.first().date} - ${points.last().date}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ChartLegend(
                wpmColor = wpmColor,
                accuracyColor = accuracyColor
            )
        }
    }
}

@Composable
private fun ChartLegend(wpmColor: Color, accuracyColor: Color) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendItem(color = wpmColor, label = stringResource(R.string.progress_wpm_label))
        LegendItem(color = accuracyColor, label = stringResource(R.string.progress_accuracy_label))
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
