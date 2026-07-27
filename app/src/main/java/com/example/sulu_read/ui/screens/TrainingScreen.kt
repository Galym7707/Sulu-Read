package com.example.sulu_read.ui.screens

import android.speech.tts.TextToSpeech
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sulu_read.R
import com.example.sulu_read.audio.applyNaturalVoice
import com.example.sulu_read.audio.detectSpeechLanguageCode
import com.example.sulu_read.audio.speakCompat
import com.example.sulu_read.domain.model.Exercise
import com.example.sulu_read.ui.components.ErrorState
import com.example.sulu_read.ui.components.LoadingState
import com.example.sulu_read.ui.components.SuluCard
import com.example.sulu_read.ui.components.SyllableChip

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrainingScreen(
    userId: String?,
    isProfileLoading: Boolean,
    profileErrorResId: Int?,
    onRetryProfile: () -> Unit,
    sourceWords: List<String>,
    languageCode: String,
    viewModel: TrainingViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val speakWord = rememberTrainingSpeaker(languageCode)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TrainingHeader(sourceWords = sourceWords)

        if (isProfileLoading) {
            LoadingState(message = stringResource(R.string.training_profile_loading))
            return@Column
        }
        if (profileErrorResId != null || userId == null) {
            ErrorState(
                message = stringResource(profileErrorResId ?: R.string.error_profile_create),
                onRetry = onRetryProfile
            )
            return@Column
        }

        when (val current = state) {
            UiState.Loading -> LoadingState(message = stringResource(R.string.training_loading))
            is UiState.Error -> ErrorState(
                message = stringResource(current.messageResId),
                onRetry = { viewModel.start(userId, sourceWords, languageCode) }
            )
            is UiState.Success -> TrainingContent(
                userId = userId,
                state = current.data,
                sourceWords = sourceWords,
                onSpeak = speakWord,
                onStart = { viewModel.start(userId, sourceWords, languageCode) },
                onSelect = viewModel::selectAnswer,
                onResetAnswer = viewModel::resetAnswer,
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
    onSpeak: (String) -> Unit,
    onStart: () -> Unit,
    onSelect: (String) -> Unit,
    onResetAnswer: () -> Unit,
    onSubmit: () -> Unit,
    onNext: () -> Unit
) {
    if (state.exercises.isEmpty()) {
        TrainingStartPanel(sourceWords = sourceWords, onStart = onStart)
        return
    }

    if (state.isComplete) {
        TrainingCompletePanel(onStart = onStart)
        return
    }

    val exercise = state.currentExercise ?: return
    val insight = exerciseInsight(exercise)
    val accent = skillAccent(insight.skill)
    val progress by animateFloatAsState(
        targetValue = (state.currentIndex + 1).toFloat() / state.exercises.size.toFloat(),
        animationSpec = tween(durationMillis = 350),
        label = "trainingProgress"
    )

    TrainingSkillRail(activeSkill = insight.skill)

    SuluCard {
        SyncStatusRow(pendingSyncCount = state.pendingSyncCount)
        Spacer(modifier = Modifier.height(12.dp))
        TrainingProgress(
            currentIndex = state.currentIndex,
            total = state.exercises.size,
            progress = progress,
            accent = accent
        )
        Spacer(modifier = Modifier.height(14.dp))
        FocusBadge(
            label = stringResource(insight.labelResId),
            accent = accent
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(insight.goalResId),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = exercise.prompt.ifBlank { stringResource(insight.instructionResId) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(insight.instructionResId),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(18.dp))
        ExerciseBody(
            exercise = exercise,
            selectedAnswer = state.selectedAnswer,
            accent = accent,
            onSpeak = onSpeak,
            onSelect = onSelect,
            onResetAnswer = onResetAnswer
        )
        Spacer(modifier = Modifier.height(18.dp))

        val feedbackText = when (state.feedbackResId) {
            R.string.training_feedback_incorrect -> stringResource(
                R.string.training_feedback_incorrect,
                state.feedbackAnswer ?: exercise.correctAnswer
            )
            null -> state.feedback
            else -> stringResource(state.feedbackResId)
        }
        val canSubmit = canSubmitExerciseAnswer(exercise, state.selectedAnswer) &&
            feedbackText == null &&
            !state.isSubmitting

        feedbackText?.let {
            FeedbackMessage(
                text = it,
                isCorrect = state.lastAnswerCorrect,
                accent = accent
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (feedbackText == null) {
            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent)
            ) {
                Text(text = stringResource(R.string.training_submit))
            }
        } else {
            Button(
                onClick = onNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent)
            ) {
                Text(text = stringResource(R.string.training_next_word))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrainingHeader(sourceWords: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.training_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.training_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (sourceWords.isEmpty()) {
                stringResource(R.string.training_source_empty)
            } else {
                stringResource(R.string.training_source_ready, sourceWords.size)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrainingStartPanel(sourceWords: List<String>, onStart: () -> Unit) {
    SuluCard {
        Text(
            text = stringResource(R.string.training_intro_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.training_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(14.dp))
        TrainingSkillRail(activeSkill = null)
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 54.dp)
        ) {
            Text(
                text = if (sourceWords.isEmpty()) {
                    stringResource(R.string.training_start_practice)
                } else {
                    stringResource(R.string.training_start)
                }
            )
        }
    }
}

@Composable
private fun TrainingCompletePanel(onStart: () -> Unit) {
    SuluCard {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF2F7D49),
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.training_complete),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.training_complete_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 54.dp)
        ) {
            Text(text = stringResource(R.string.training_again))
        }
    }
}

@Composable
private fun TrainingProgress(
    currentIndex: Int,
    total: Int,
    progress: Float,
    accent: Color
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.training_step_label, currentIndex + 1, total),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "${((currentIndex + 1).toFloat() / total.toFloat() * 100).toInt()}%",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(24.dp)),
        color = accent,
        trackColor = accent.copy(alpha = 0.14f)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrainingSkillRail(activeSkill: TrainingSkill?) {
    val steps = listOf(
        SkillStep(TrainingSkill.Phonology, R.string.training_skill_phonology),
        SkillStep(TrainingSkill.Syllables, R.string.training_skill_syllables),
        SkillStep(TrainingSkill.Decoding, R.string.training_skill_decoding),
        SkillStep(TrainingSkill.Visual, R.string.training_skill_visual),
        SkillStep(TrainingSkill.Morphology, R.string.training_skill_morphology)
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        steps.forEach { step ->
            val accent = skillAccent(step.skill)
            val isActive = activeSkill == step.skill
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isActive) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isActive) accent.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Text(
                    text = stringResource(step.labelResId),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isActive) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun FocusBadge(label: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun SyncStatusRow(pendingSyncCount: Int) {
    val hasPending = pendingSyncCount > 0
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (hasPending) Icons.Default.CloudUpload else Icons.Default.CloudDone,
            contentDescription = null,
            tint = if (hasPending) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
        )
        Text(
            text = if (hasPending) {
                stringResource(R.string.training_sync_pending, pendingSyncCount)
            } else {
                stringResource(R.string.training_sync_synced)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExerciseBody(
    exercise: Exercise,
    selectedAnswer: String,
    accent: Color,
    onSpeak: (String) -> Unit,
    onSelect: (String) -> Unit,
    onResetAnswer: () -> Unit
) {
    when (exercise.type) {
        "syllable_order" -> SyllableOrderBody(
            exercise = exercise,
            selectedAnswer = selectedAnswer,
            accent = accent,
            onSelect = onSelect,
            onResetAnswer = onResetAnswer
        )

        "missing_syllable" -> MissingSyllableBody(
            exercise = exercise,
            selectedAnswer = selectedAnswer,
            accent = accent,
            onSpeak = onSpeak,
            onSelect = onSelect
        )

        // The target word must stay hidden: the answer is the word itself.
        "auditory_match",
        "word_recognition" -> AuditoryMatchBody(
            exercise = exercise,
            selectedAnswer = selectedAnswer,
            accent = accent,
            onSpeak = onSpeak,
            onSelect = onSelect
        )

        "word_to_syllables" -> WordToSyllablesBody(
            exercise = exercise,
            selectedAnswer = selectedAnswer,
            accent = accent,
            onSpeak = onSpeak,
            onSelect = onSelect
        )

        "root_suffix_identification",
        "word_segmentation" -> MorphologyBody(
            exercise = exercise,
            selectedAnswer = selectedAnswer,
            accent = accent,
            onSpeak = onSpeak,
            onSelect = onSelect
        )

        else -> ChoiceExerciseBody(
            exercise = exercise,
            selectedAnswer = selectedAnswer,
            accent = accent,
            onSpeak = onSpeak,
            onSelect = onSelect
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyllableOrderBody(
    exercise: Exercise,
    selectedAnswer: String,
    accent: Color,
    onSelect: (String) -> Unit,
    onResetAnswer: () -> Unit
) {
    val selected = selectedSyllables(selectedAnswer)
    Text(
        text = stringResource(R.string.training_answer_so_far),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = selected.joinToString("-").ifBlank { stringResource(R.string.training_answer_placeholder) },
        style = MaterialTheme.typography.headlineSmall.copy(lineHeight = 34.sp, letterSpacing = 0.sp),
        fontWeight = FontWeight.SemiBold,
        color = if (selected.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    )
    if (selected.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            selected.forEachIndexed { index, syllable ->
                SyllableChip(
                    text = syllable,
                    selected = true,
                    onClick = {
                        onSelect(selected.filterIndexed { selectedIndex, _ -> selectedIndex != index }.joinToString("-"))
                    }
                )
            }
            TextButton(onClick = onResetAnswer) {
                Text(text = stringResource(R.string.training_reset_answer), color = accent)
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.training_available_syllables),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        exercise.options.forEach { option ->
            val availableCount = exercise.options.count { it == option }
            val selectedCount = selected.count { it == option }
            val canSelect = selectedCount < availableCount && selected.size < exercise.syllables.size
            SyllableChip(
                text = option,
                enabled = canSelect,
                onClick = {
                    onSelect((selected + option).joinToString("-"))
                }
            )
        }
    }
}

@Composable
private fun MissingSyllableBody(
    exercise: Exercise,
    selectedAnswer: String,
    accent: Color,
    onSpeak: (String) -> Unit,
    onSelect: (String) -> Unit
) {
    WordFocus(text = exercise.prompt)
    Spacer(modifier = Modifier.height(12.dp))
    TtsPlayButton(
        text = exercise.targetWord,
        accent = accent,
        onSpeak = onSpeak,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(12.dp))
    AnswerOptions(exercise.options, selectedAnswer, accent, onSelect)
}

@Composable
private fun AuditoryMatchBody(
    exercise: Exercise,
    selectedAnswer: String,
    accent: Color,
    onSpeak: (String) -> Unit,
    onSelect: (String) -> Unit
) {
    TtsPlayButton(
        text = exercise.targetWord,
        accent = accent,
        onSpeak = onSpeak,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(12.dp))
    AnswerOptions(exercise.options, selectedAnswer, accent, onSelect)
}

@Composable
private fun WordToSyllablesBody(
    exercise: Exercise,
    selectedAnswer: String,
    accent: Color,
    onSpeak: (String) -> Unit,
    onSelect: (String) -> Unit
) {
    WordFocus(text = exercise.targetWord)
    Spacer(modifier = Modifier.height(12.dp))
    TtsPlayButton(
        text = exercise.targetWord,
        accent = accent,
        onSpeak = onSpeak,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(12.dp))
    AnswerOptions(exercise.options, selectedAnswer, accent, onSelect)
}

@Composable
private fun MorphologyBody(
    exercise: Exercise,
    selectedAnswer: String,
    accent: Color,
    onSpeak: (String) -> Unit,
    onSelect: (String) -> Unit
) {
    WordFocus(text = exercise.targetWord)
    Spacer(modifier = Modifier.height(12.dp))
    TtsPlayButton(
        text = exercise.targetWord,
        accent = accent,
        onSpeak = onSpeak,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(12.dp))
    AnswerOptions(exercise.options, selectedAnswer, accent, onSelect)
}

@Composable
private fun ChoiceExerciseBody(
    exercise: Exercise,
    selectedAnswer: String,
    accent: Color,
    onSpeak: (String) -> Unit,
    onSelect: (String) -> Unit
) {
    if (exercise.targetWord.isNotBlank()) {
        WordFocus(text = exercise.targetWord)
        Spacer(modifier = Modifier.height(12.dp))
        TtsPlayButton(
            text = exercise.targetWord,
            accent = accent,
            onSpeak = onSpeak,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
    AnswerOptions(exercise.options, selectedAnswer, accent, onSelect)
}

@Composable
private fun WordFocus(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall.copy(lineHeight = 34.sp, letterSpacing = 0.sp),
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun FeedbackMessage(text: String, isCorrect: Boolean?, accent: Color) {
    val targetColor = when (isCorrect) {
        true -> Color(0xFF2F7D49)
        false -> Color(0xFF93621A)
        null -> accent
    }
    val feedbackColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 250),
        label = "trainingFeedbackColor"
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = feedbackColor.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, feedbackColor.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = feedbackColor,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when (isCorrect) {
                    true -> stringResource(R.string.training_feedback_correct_detail)
                    false -> stringResource(R.string.training_feedback_incorrect_detail)
                    null -> stringResource(R.string.training_feedback_saved_detail)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AnswerOptions(
    options: List<String>,
    selectedAnswer: String,
    accent: Color,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            ChoiceButton(
                text = option,
                selected = selectedAnswer == option,
                accent = accent,
                onClick = { onSelect(option) }
            )
        }
    }
}

@Composable
private fun ChoiceButton(
    text: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) accent.copy(alpha = 0.8f) else MaterialTheme.colorScheme.outlineVariant
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(lineHeight = 24.sp, letterSpacing = 0.sp),
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun selectedSyllables(answer: String): List<String> {
    return answer.split("-").filter { it.isNotBlank() }
}

private fun canSubmitExerciseAnswer(exercise: Exercise, selectedAnswer: String): Boolean {
    return if (exercise.type == "syllable_order") {
        val selected = selectedSyllables(selectedAnswer)
        selected.size == exercise.syllables.size && exercise.syllables.isNotEmpty()
    } else {
        selectedAnswer.isNotBlank()
    }
}

/**
 * One shared TTS engine for the whole training session instead of creating a
 * new engine for every exercise (engine startup is slow and caused audible lag).
 */
@Composable
private fun rememberTrainingSpeaker(languageCode: String): (String) -> Unit {
    val context = LocalContext.current.applicationContext
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.applyNaturalVoice(languageCode)
                engine?.setSpeechRate(0.9f)
                engine?.setPitch(1.0f)
            }
        }
        tts = engine
        onDispose {
            engine?.stop()
            engine?.shutdown()
            tts = null
        }
    }
    return remember(languageCode) {
        { text: String ->
            val speechLanguageCode = detectSpeechLanguageCode(text, languageCode)
            tts?.applyNaturalVoice(speechLanguageCode)
            tts?.speakCompat(text, TextToSpeech.QUEUE_FLUSH, "training-word")
            Unit
        }
    }
}

@Composable
private fun TtsPlayButton(
    text: String,
    accent: Color,
    onSpeak: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = { onSpeak(text) },
        modifier = modifier.defaultMinSize(minHeight = 54.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = accent.copy(alpha = 0.08f),
            contentColor = accent
        )
    ) {
        Icon(imageVector = Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
        Text(text = stringResource(R.string.training_listen))
    }
}

private enum class TrainingSkill {
    Phonology,
    Syllables,
    Decoding,
    Visual,
    Morphology
}

private data class SkillStep(
    val skill: TrainingSkill,
    @param:StringRes val labelResId: Int
)

private data class ExerciseInsight(
    val skill: TrainingSkill,
    @param:StringRes val labelResId: Int,
    @param:StringRes val goalResId: Int,
    @param:StringRes val instructionResId: Int
)

private fun exerciseInsight(exercise: Exercise): ExerciseInsight {
    return when (exercise.type) {
        "auditory_match" -> ExerciseInsight(
            skill = TrainingSkill.Phonology,
            labelResId = R.string.training_skill_phonology,
            goalResId = R.string.training_goal_phonology,
            instructionResId = R.string.training_instruction_auditory_match
        )
        "missing_syllable" -> ExerciseInsight(
            skill = TrainingSkill.Syllables,
            labelResId = R.string.training_skill_syllables,
            goalResId = R.string.training_goal_syllables,
            instructionResId = R.string.training_instruction_missing_syllable
        )
        "syllable_order" -> ExerciseInsight(
            skill = TrainingSkill.Syllables,
            labelResId = R.string.training_skill_syllables,
            goalResId = R.string.training_goal_syllables,
            instructionResId = R.string.training_instruction_syllable_order
        )
        "word_to_syllables" -> ExerciseInsight(
            skill = TrainingSkill.Decoding,
            labelResId = R.string.training_skill_decoding,
            goalResId = R.string.training_goal_decoding,
            instructionResId = R.string.training_instruction_word_to_syllables
        )
        "word_recognition" -> ExerciseInsight(
            skill = TrainingSkill.Visual,
            labelResId = R.string.training_skill_visual,
            goalResId = R.string.training_goal_visual,
            instructionResId = R.string.training_instruction_word_recognition
        )
        "root_suffix_identification",
        "word_segmentation" -> ExerciseInsight(
            skill = TrainingSkill.Morphology,
            labelResId = R.string.training_skill_morphology,
            goalResId = R.string.training_goal_morphology,
            instructionResId = R.string.training_instruction_morphology
        )
        else -> ExerciseInsight(
            skill = TrainingSkill.Decoding,
            labelResId = R.string.training_skill_decoding,
            goalResId = R.string.training_goal_decoding,
            instructionResId = R.string.training_instruction_choice
        )
    }
}

private fun skillAccent(skill: TrainingSkill): Color {
    return when (skill) {
        TrainingSkill.Phonology -> Color(0xFF2D776F)
        TrainingSkill.Syllables -> Color(0xFF7A6330)
        TrainingSkill.Decoding -> Color(0xFF3F5F8F)
        TrainingSkill.Visual -> Color(0xFF6A4F8F)
        TrainingSkill.Morphology -> Color(0xFF8A4C62)
    }
}
