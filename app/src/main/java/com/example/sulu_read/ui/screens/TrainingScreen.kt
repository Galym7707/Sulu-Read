package com.example.sulu_read.ui.screens

import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sulu_read.domain.model.Exercise
import com.example.sulu_read.ui.components.ErrorState
import com.example.sulu_read.ui.components.LoadingState
import com.example.sulu_read.ui.components.SuluCard
import com.example.sulu_read.ui.components.SyllableChip
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrainingScreen(
    userId: String?,
    sourceWords: List<String>,
    viewModel: TrainingViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Жаттығу / Тренировка",
            style = MaterialTheme.typography.headlineSmall
        )

        if (userId == null) {
            LoadingState(message = "Профиль дайындалып жатыр...")
            return@Column
        }

        when (val current = state) {
            UiState.Loading -> LoadingState(message = "Жаттығулар жүктелуде...")
            is UiState.Error -> ErrorState(
                message = current.message,
                onRetry = { viewModel.start(userId, sourceWords) }
            )
            is UiState.Success -> TrainingContent(
                userId = userId,
                state = current.data,
                sourceWords = sourceWords,
                onStart = { viewModel.start(userId, sourceWords) },
                onSelect = viewModel::selectAnswer,
                onSubmit = { viewModel.submit(userId) },
                onNext = viewModel::next
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrainingContent(
    userId: String,
    state: TrainingSessionState,
    sourceWords: List<String>,
    onStart: () -> Unit,
    onSelect: (String) -> Unit,
    onSubmit: () -> Unit,
    onNext: () -> Unit
) {
    if (state.exercises.isEmpty()) {
        SuluCard {
            Text(
                text = "Қысқа жаттығу сессиясын бастайық. Бір сессияда 5 тапсырма болады.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Бастау / Начать")
            }
        }
        return
    }

    if (state.isComplete) {
        SuluCard {
            Text(
                text = "Жақсы жұмыс! Көзге демалыс берейік / Давай сделаем короткий перерыв.",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Тағы 5 тапсырма / Ещё 5")
            }
        }
        return
    }

    val exercise = state.currentExercise ?: return
    SuluCard {
        Text(
            text = "${state.currentIndex + 1} / ${state.exercises.size}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = exercise.prompt, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))
        ExerciseBody(
            exercise = exercise,
            selectedAnswer = state.selectedAnswer,
            onSelect = onSelect
        )
        Spacer(modifier = Modifier.height(16.dp))
        state.feedback?.let {
            Text(text = it, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onSubmit,
                enabled = state.selectedAnswer.isNotBlank() && state.feedback == null && !state.isSubmitting,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "Тексеру")
            }
            OutlinedButton(
                onClick = onNext,
                enabled = state.feedback != null,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "Келесі сөз")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExerciseBody(
    exercise: Exercise,
    selectedAnswer: String,
    onSelect: (String) -> Unit
) {
    when (exercise.type) {
        "syllable_order" -> {
            val selected = selectedAnswer.split("-").filter { it.isNotBlank() }
            Text(text = selected.joinToString("-").ifBlank { "..." }, style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                exercise.options.forEach { option ->
                    SyllableChip(text = option, onClick = {
                        onSelect((selected + option).joinToString("-"))
                    })
                }
            }
        }
        "auditory_match" -> {
            TtsPlayButton(text = exercise.targetWord)
            Spacer(modifier = Modifier.height(12.dp))
            AnswerOptions(exercise.options, selectedAnswer, onSelect)
        }
        else -> {
            if (exercise.type == "word_to_syllables") {
                Text(text = exercise.targetWord, style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(12.dp))
            }
            AnswerOptions(exercise.options, selectedAnswer, onSelect)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerOptions(options: List<String>, selectedAnswer: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            SyllableChip(
                text = option,
                selected = selectedAnswer == option,
                onClick = { onSelect(option) }
            )
        }
    }
}

@Composable
private fun TtsPlayButton(text: String) {
    val context = LocalContext.current.applicationContext
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        val engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("kk", "KZ")
            }
        }
        tts = engine
        onDispose {
            engine.stop()
            engine.shutdown()
            tts = null
        }
    }
    OutlinedButton(onClick = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "training-word")
        } else {
            @Suppress("DEPRECATION")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null)
        }
    }) {
        Icon(imageVector = Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
        Text(text = " Тыңдау")
    }
}
