package com.example.sulu_read.ui.screens

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sulu_read.R
import com.example.sulu_read.domain.model.Exercise
import com.example.sulu_read.domain.repository.SuluReadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TrainingSessionState(
    val exercises: List<Exercise> = emptyList(),
    val currentIndex: Int = 0,
    val selectedAnswer: String = "",
    val feedback: String? = null,
    val feedbackResId: Int? = null,
    val feedbackAnswer: String? = null,
    val lastAnswerCorrect: Boolean? = null,
    val isSubmitting: Boolean = false,
    val pendingSyncCount: Int = 0,
    val startedAtMs: Long = 0L
) {
    val currentExercise: Exercise? get() = exercises.getOrNull(currentIndex)
    val isComplete: Boolean get() = exercises.isNotEmpty() && currentIndex >= exercises.size
}

class TrainingViewModel(private val repository: SuluReadRepository) : ViewModel() {
    private val _state = MutableStateFlow<UiState<TrainingSessionState>>(UiState.Success(TrainingSessionState()))
    val state: StateFlow<UiState<TrainingSessionState>> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.pendingAttemptCount.collect { count ->
                val current = (_state.value as? UiState.Success)?.data ?: return@collect
                _state.value = UiState.Success(current.copy(pendingSyncCount = count))
            }
        }
    }

    fun start(userId: String, sourceWords: List<String>, languageCode: String, count: Int = 6) {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = runCatching {
                TrainingSessionState(
                    exercises = repository.generateExercises(userId, sourceWords, count, languageCode),
                    startedAtMs = SystemClock.elapsedRealtime()
                )
            }.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(R.string.error_training_load) }
            )
        }
    }

    fun selectAnswer(answer: String) {
        val current = (_state.value as? UiState.Success)?.data ?: return
        if (current.feedbackResId != null || current.feedback != null) {
            return
        }
        _state.value = UiState.Success(current.copy(selectedAnswer = answer))
    }

    fun resetAnswer() {
        val current = (_state.value as? UiState.Success)?.data ?: return
        if (current.feedbackResId != null || current.feedback != null) {
            return
        }
        _state.value = UiState.Success(current.copy(selectedAnswer = ""))
    }

    fun submit(userId: String) {
        val current = (_state.value as? UiState.Success)?.data ?: return
        val exercise = current.currentExercise ?: return
        val answer = current.selectedAnswer.ifBlank { return }
        viewModelScope.launch {
            _state.value = UiState.Success(current.copy(isSubmitting = true))
            val elapsed = SystemClock.elapsedRealtime() - current.startedAtMs
            val result = runCatching {
                repository.submitExerciseAttempt(userId, exercise, answer, elapsed)
            }
            _state.value = result.fold(
                onSuccess = {
                    UiState.Success(
                        current.copy(
                            feedback = null,
                            feedbackResId = if (it.isCorrect) {
                                R.string.training_feedback_correct
                            } else {
                                R.string.training_feedback_incorrect
                            },
                            feedbackAnswer = if (it.isCorrect) null else exercise.correctAnswer,
                            lastAnswerCorrect = it.isCorrect,
                            isSubmitting = false
                        )
                    )
                },
                onFailure = {
                    UiState.Success(
                        current.copy(
                            feedback = null,
                            feedbackResId = R.string.training_attempt_save_error,
                            feedbackAnswer = null,
                            lastAnswerCorrect = null,
                            isSubmitting = false
                        )
                    )
                }
            )
        }
    }

    fun next() {
        val current = (_state.value as? UiState.Success)?.data ?: return
        _state.value = UiState.Success(
            current.copy(
                currentIndex = current.currentIndex + 1,
                selectedAnswer = "",
                feedback = null,
                feedbackResId = null,
                feedbackAnswer = null,
                lastAnswerCorrect = null,
                startedAtMs = SystemClock.elapsedRealtime()
            )
        )
    }
}
