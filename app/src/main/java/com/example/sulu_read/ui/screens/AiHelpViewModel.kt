package com.example.sulu_read.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sulu_read.domain.repository.SuluReadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AiHelpState {
    data object Idle : AiHelpState
    data object Loading : AiHelpState
    data class Success(val result: String) : AiHelpState
    data class Error(val message: String) : AiHelpState
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
                onFailure = { AiHelpState.Error(it.message ?: DEFAULT_AI_ERROR) }
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
                onFailure = { AiHelpState.Error(it.message ?: DEFAULT_AI_ERROR) }
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
                onFailure = { AiHelpState.Error(it.message ?: DEFAULT_AI_ERROR) }
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
                onFailure = { AiHelpState.Error(it.message ?: DEFAULT_AI_ERROR) }
            )
        }
    }

    fun clearAiHelp() {
        _state.value = AiHelpState.Idle
    }
}

private const val DEFAULT_AI_ERROR = "AI help is temporarily unavailable. Please try again later."
