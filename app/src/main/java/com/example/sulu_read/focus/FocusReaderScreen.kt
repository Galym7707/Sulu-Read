package com.example.sulu_read.focus

import android.Manifest
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.sulu_read.R
import com.example.sulu_read.SyllableWord
import com.example.sulu_read.audio.applyNaturalVoice
import com.example.sulu_read.audio.detectSpeechLanguageCode
import com.example.sulu_read.audio.speakCompat
import com.example.sulu_read.ui.screens.AiHelpState
import kotlinx.coroutines.delay

private val ScenePanelBackground = Color(0xFFFFFCF4)
private val SyllablePalette = listOf(Color(0xFF1A237E), Color(0xFF8A5A00))

// Material's minimum accessible touch target. The default button height falls under it once
// padding is accounted for on small screens.
private const val MIN_TOUCH_TARGET_DP = 56

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FocusReaderScreen(
    text: String,
    backendWords: List<SyllableWord>,
    languageCode: String,
    aiHelpState: AiHelpState,
    onRequestMeaningHint: (String) -> Unit,
    onDismissHint: () -> Unit,
    onCollectTriggerWords: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val words = remember(text, backendWords) { buildFocusWords(text, backendWords) }
    var ladder by remember(text) { mutableStateOf(FocusLadderState()) }
    var isListening by remember { mutableStateOf(false) }
    var showTryAgain by remember { mutableStateOf(false) }
    var isFlashing by remember { mutableStateOf(false) }
    var micDenied by remember { mutableStateOf(false) }

    val speechGate = remember(context) { SpeechGate(context) }
    var micUnavailable by remember(context) { mutableStateOf(!speechGate.isAvailable) }
    DisposableEffect(speechGate) {
        onDispose { speechGate.release() }
    }

    var textToSpeech by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        val engine = TextToSpeech(context) {}
        textToSpeech = engine
        onDispose {
            engine.stop()
            engine.shutdown()
            textToSpeech = null
        }
    }

    val currentWord = words.getOrNull(ladder.wordIndex)

    fun speak(value: String) {
        val engine = textToSpeech ?: return
        val speechLanguage = detectSpeechLanguageCode(value, languageCode)
        engine.applyNaturalVoice(speechLanguage)
        engine.setSpeechRate(ladder.ttsRate())
        engine.speakCompat(value, TextToSpeech.QUEUE_FLUSH, "focus-${value.hashCode()}")
    }

    fun finishWord(wasCorrect: Boolean) {
        val word = currentWord ?: return
        isListening = false
        ladder = if (wasCorrect) {
            showTryAgain = false
            ladder.onCorrectRead(word.spoken, words.size)
        } else {
            showTryAgain = true
            ladder.onMisread(word.spoken, words.size)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        micDenied = !granted
        if (granted) {
            isListening = true
        }
    }

    fun startListening() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        isListening = true
    }

    LaunchedEffect(isListening, ladder.wordIndex, ladder.step) {
        val word = currentWord ?: return@LaunchedEffect
        if (!isListening) {
            return@LaunchedEffect
        }
        speechGate.listenOnce(
            languageCode = detectSpeechLanguageCode(word.spoken, languageCode),
            onResult = { hypotheses ->
                finishWord(isSpokenWordAccepted(word.spoken, hypotheses))
            },
            onUnavailable = {
                isListening = false
                micUnavailable = true
            }
        )
    }

    // Silence help. A reader who stares at a word without speaking is the one who most needs
    // a nudge, and under the old rules got nothing until they guessed wrong out loud. Neither
    // stage records a failure: onHelpRequested only raises the step.
    //
    // Keyed on the word alone, deliberately. Keying on the step too would restart the timer
    // every time this effect moved the step, and the reader would be flashed at forever
    // instead of ever reaching the second stage.
    LaunchedEffect(ladder.wordIndex) {
        delay(NUDGE_AFTER_MILLIS)
        if (ladder.step != FocusStep.Focus) {
            return@LaunchedEffect
        }
        ladder = ladder.onHelpRequested(FocusStep.Sweep)

        delay(OFFER_HELP_AFTER_MILLIS - NUDGE_AFTER_MILLIS)
        if (ladder.step != FocusStep.Focus) {
            return@LaunchedEffect
        }
        ladder = ladder.onHelpRequested(FocusStep.Syllables)
    }

    // Sweep step: hide the word, flash it sharp for the 200 ms the source specifies, then
    // hand it back sharp and highlighted.
    LaunchedEffect(ladder.step, ladder.wordIndex) {
        if (ladder.step != FocusStep.Sweep) {
            isFlashing = false
            return@LaunchedEffect
        }
        isFlashing = false
        delay(SWEEP_FLASH_MILLIS)
        isFlashing = true
        delay(SWEEP_FLASH_MILLIS)
        isFlashing = false
        ladder = ladder.onNudgeFinished()
    }

    // Syllables, Letters and Meaning speak on entry, at the adaptive pace.
    LaunchedEffect(ladder.step, ladder.wordIndex) {
        val word = currentWord ?: return@LaunchedEffect
        when (ladder.step) {
            FocusStep.Syllables -> speak(word.syllables.joinToString(" , "))
            FocusStep.Letters -> {
                speak(letterNamesFor(word.spoken, languageCode).joinToString(" , "))
                delay(SWEEP_FLASH_MILLIS * 4)
                speak(word.spoken)
            }
            FocusStep.Meaning -> {
                onRequestMeaningHint(word.spoken)
                speak(word.spoken)
            }
            FocusStep.Focus, FocusStep.Sweep -> Unit
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.focus_mode_title),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(
                R.string.focus_progress,
                (ladder.masteryShare() * 100).toInt()
            ),
            style = MaterialTheme.typography.bodyMedium
        )

        BlurredTextBlock(
            words = words,
            focusIndex = ladder.wordIndex,
            isFocusSharp = ladder.step != FocusStep.Sweep || isFlashing
        )

        if (currentWord == null) {
            Text(text = stringResource(R.string.focus_finished))
            if (ladder.triggerWords.isNotEmpty()) {
                Button(
                    onClick = { onCollectTriggerWords(ladder.triggerWords) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.focus_practise_words))
                }
            }
        } else {
            Text(
                text = when {
                    showTryAgain -> stringResource(R.string.focus_try_again)
                    isListening -> stringResource(R.string.focus_listening)
                    else -> stringResource(R.string.focus_listen)
                },
                style = MaterialTheme.typography.bodyLarge
            )

            when (ladder.step) {
                FocusStep.Syllables -> {
                    Text(
                        text = stringResource(R.string.focus_step_syllables),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    SyllableHint(currentWord.syllables)
                }

                FocusStep.Letters -> {
                    Text(
                        text = stringResource(R.string.focus_step_letters),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = letterNamesFor(currentWord.spoken, languageCode)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                FocusStep.Meaning -> MeaningHint(
                    aiHelpState = aiHelpState,
                    onDismissHint = onDismissHint
                )

                FocusStep.Focus, FocusStep.Sweep -> Unit
            }

            if (micUnavailable || micDenied) {
                Text(
                    text = stringResource(
                        if (micDenied) R.string.focus_mic_permission
                        else R.string.focus_mic_unavailable
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (micUnavailable || micDenied) {
                    Button(
                        onClick = { finishWord(wasCorrect = true) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = MIN_TOUCH_TARGET_DP.dp)
                    ) {
                        Text(text = stringResource(R.string.focus_i_read_it))
                    }
                } else {
                    Button(
                        onClick = { startListening() },
                        enabled = !isListening,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = MIN_TOUCH_TARGET_DP.dp)
                    ) {
                        Text(text = stringResource(R.string.focus_listen))
                    }
                }

                FilledTonalButton(
                    onClick = {
                        ladder = ladder.onHelpRequested(
                            when (ladder.step) {
                                FocusStep.Focus, FocusStep.Sweep -> FocusStep.Syllables
                                FocusStep.Syllables -> FocusStep.Letters
                                FocusStep.Letters, FocusStep.Meaning -> FocusStep.Meaning
                            }
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = MIN_TOUCH_TARGET_DP.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lightbulb,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.focus_help))
                }
            }
        }

        if (ladder.suggestPause) {
            PausePanel(onContinue = { ladder = ladder.onPauseAcknowledged() })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyllableHint(syllables: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        syllables.forEachIndexed { index, syllable ->
            Text(
                text = syllable,
                fontFamily = SuluSerifFontFamily,
                fontSize = FOCUS_FONT_SIZE_SP.sp,
                fontWeight = FontWeight.Bold,
                color = SyllablePalette[index % SyllablePalette.size]
            )
        }
    }
}

@Composable
private fun MeaningHint(aiHelpState: AiHelpState, onDismissHint: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ScenePanelBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.focus_step_meaning),
            style = MaterialTheme.typography.bodyMedium
        )
        when (aiHelpState) {
            is AiHelpState.Loading -> CircularProgressIndicator()
            is AiHelpState.Success -> {
                Text(
                    text = aiHelpState.result,
                    style = MaterialTheme.typography.bodyLarge
                )
                TextButton(onClick = onDismissHint) {
                    Text(text = stringResource(R.string.focus_hint_dismiss))
                }
            }

            is AiHelpState.Error -> {
                Text(
                    text = aiHelpState.message,
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onDismissHint) {
                    Text(text = stringResource(R.string.focus_hint_dismiss))
                }
            }

            AiHelpState.Idle -> Unit
        }
    }
}

@Composable
private fun PausePanel(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ScenePanelBackground)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.focus_pause_title),
            style = MaterialTheme.typography.titleMedium
        )
        Button(onClick = onContinue) {
            Text(text = stringResource(R.string.focus_pause_continue))
        }
    }
}
