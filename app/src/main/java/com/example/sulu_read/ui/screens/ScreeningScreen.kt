package com.example.sulu_read.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sulu_read.R
import com.example.sulu_read.ui.components.SuluCard

@Composable
fun ScreeningScreen(
    userId: String?,
    viewModel: ScreeningViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val screeningSampleText = stringResource(R.string.screening_sample_text)
    val wordsTotal = screeningSampleText.split(Regex("\\s+")).count { it.isNotBlank() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = stringResource(R.string.screening_title), style = MaterialTheme.typography.headlineSmall)
        SuluCard {
            Text(text = stringResource(R.string.screening_disclaimer_kk), style = MaterialTheme.typography.bodyMedium)
            Text(text = stringResource(R.string.screening_disclaimer_ru), style = MaterialTheme.typography.bodyMedium)
        }

        SuluCard {
            Text(text = screeningSampleText, style = MaterialTheme.typography.bodyLarge)
        }

        when {
            userId == null -> Text(text = stringResource(R.string.training_profile_loading))
            state.result != null -> {
                val result = state.result ?: return@Column
                SuluCard {
                    Text(text = stringResource(R.string.screening_wpm, result.wpm), style = MaterialTheme.typography.titleMedium)
                    Text(text = stringResource(R.string.screening_accuracy, (result.accuracy * 100).toInt()), style = MaterialTheme.typography.titleMedium)
                    Text(text = stringResource(R.string.screening_support_level, result.supportLevel), style = MaterialTheme.typography.titleMedium)
                    Text(text = result.disclaimer, style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.screening_restart))
                }
            }
            state.isRunning -> {
                Text(text = stringResource(R.string.screening_errors, state.errorsCount), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = viewModel::addError, modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.screening_error_button))
                    }
                    Button(
                        onClick = { viewModel.finish(userId, wordsTotal) },
                        enabled = !state.isSubmitting,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = stringResource(R.string.screening_finish))
                    }
                }
            }
            else -> {
                state.errorMessageResId?.let { Text(text = stringResource(it), color = MaterialTheme.colorScheme.error) }
                Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.screening_start))
                }
            }
        }
    }
}
