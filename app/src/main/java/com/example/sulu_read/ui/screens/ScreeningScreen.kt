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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sulu_read.ui.components.SuluCard

private const val KAZAKH_DISCLAIMER = "Бұл медициналық диагноз емес. Бұл тек оқу қолдауының деңгейін шамамен бағалау."
private const val RUSSIAN_DISCLAIMER = "Это не медицинский диагноз. Это примерная оценка уровня поддержки чтения."

private val ScreeningSampleText = """
    Бүгін оқушы кітап оқыды. Ол жаңа сөздерді асықпай қайталады. Мұғалім әр сөзді анық айтуға көмектесті.
    Балалар мәтінді бірге талқылады. Әр бала өз қарқынымен оқыды. Қате жасау ұят емес, себебі оқу жаттығу арқылы жақсарады.
    Көзге демалыс беріп, қысқа үзіліс жасау да маңызды. Осы мәтін оқу қолдауын шамамен бағалау үшін ғана қолданылады.
""".trimIndent()

@Composable
fun ScreeningScreen(
    userId: String?,
    viewModel: ScreeningViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val wordsTotal = ScreeningSampleText.split(Regex("\\s+")).count { it.isNotBlank() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Оқу бағалауы / Проверка чтения", style = MaterialTheme.typography.headlineSmall)
        SuluCard {
            Text(text = KAZAKH_DISCLAIMER, style = MaterialTheme.typography.bodyMedium)
            Text(text = RUSSIAN_DISCLAIMER, style = MaterialTheme.typography.bodyMedium)
        }

        SuluCard {
            Text(text = ScreeningSampleText, style = MaterialTheme.typography.bodyLarge)
        }

        when {
            userId == null -> Text(text = "Профиль дайындалып жатыр...")
            state.result != null -> {
                val result = state.result ?: return@Column
                SuluCard {
                    Text(text = "WPM: ${result.wpm}", style = MaterialTheme.typography.titleMedium)
                    Text(text = "Accuracy: ${(result.accuracy * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
                    Text(text = "Support level: ${result.supportLevel}", style = MaterialTheme.typography.titleMedium)
                    Text(text = result.disclaimer, style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth()) {
                    Text(text = "Қайта бастау / Повторить")
                }
            }
            state.isRunning -> {
                Text(text = "Қателер: ${state.errorsCount}", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = viewModel::addError, modifier = Modifier.weight(1f)) {
                        Text(text = "Қате / Ошибка")
                    }
                    Button(
                        onClick = { viewModel.finish(userId, wordsTotal) },
                        enabled = !state.isSubmitting,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = "Аяқтау")
                    }
                }
            }
            else -> {
                state.errorMessage?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth()) {
                    Text(text = "Start")
                }
            }
        }
    }
}
