package com.example.sulu_read.ui.screens

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sulu_read.R
import com.example.sulu_read.domain.repository.SuluReadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AiHelpState {
    data object Idle : AiHelpState
    data object Loading : AiHelpState
    data class Success(val result: String) : AiHelpState

    /**
     * Carries a string resource, never provider text. The AI providers and the backend only ever
     * report failures in English, so surfacing their message left an English sentence sitting in
     * the middle of a Russian or Kazakh screen.
     */
    data class Error(@param:StringRes val messageResId: Int = R.string.reader_ai_error) : AiHelpState
}

class AiHelpViewModel(private val repository: SuluReadRepository) : ViewModel() {
    private val _state = MutableStateFlow<AiHelpState>(AiHelpState.Idle)
    val state: StateFlow<AiHelpState> = _state.asStateFlow()

    fun generateAiHelp(
        task: String,
        text: String,
        languageCode: String,
        level: String? = null,
        mode: String = "explain",
        extra: Map<String, String> = emptyMap()
    ) {
        viewModelScope.launch {
            _state.value = AiHelpState.Loading
            _state.value = runCatching {
                repository.generateAiHelp(
                    task = task,
                    text = text,
                    languageCode = languageCode,
                    level = level,
                    mode = mode,
                    extra = extra
                )
            }.fold(
                onSuccess = { AiHelpState.Success(it) },
                onFailure = { AiHelpState.Error() }
            )
        }
    }

    fun explainTextWithAi(text: String, languageCode: String) {
        viewModelScope.launch {
            _state.value = AiHelpState.Loading
            _state.value = runCatching {
                repository.explainTextWithAi(text, languageCode)
            }.fold(
                onSuccess = { AiHelpState.Success(it) },
                onFailure = { AiHelpState.Error() }
            )
        }
    }

    fun hintForWord(word: String, languageCode: String) {
        viewModelScope.launch {
            _state.value = AiHelpState.Loading
            _state.value = runCatching {
                repository.hintForWord(word, languageCode)
            }.fold(
                onSuccess = { AiHelpState.Success(it) },
                onFailure = { AiHelpState.Error() }
            )
        }
    }

    fun checkAnswerWithAi(
        question: String,
        correctAnswer: String,
        userAnswer: String,
        languageCode: String
    ) {
        viewModelScope.launch {
            _state.value = AiHelpState.Loading
            _state.value = runCatching {
                repository.checkAnswerWithAi(question, correctAnswer, userAnswer, languageCode)
            }.fold(
                onSuccess = { AiHelpState.Success(it) },
                onFailure = { AiHelpState.Error() }
            )
        }
    }

    fun generateExerciseWithAi(text: String, languageCode: String, level: String? = null) {
        viewModelScope.launch {
            _state.value = AiHelpState.Loading
            _state.value = runCatching {
                repository.generateExerciseWithAi(text, languageCode, level)
            }.fold(
                onSuccess = { AiHelpState.Success(it) },
                onFailure = { AiHelpState.Error() }
            )
        }
    }

    fun clearAiHelp() {
        _state.value = AiHelpState.Idle
    }
}
