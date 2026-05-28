package com.example.sulu_read.ui.screens

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sulu_read.R
import com.example.sulu_read.domain.model.ScreeningResult
import com.example.sulu_read.domain.repository.SuluReadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ScreeningUiState(
    val startedAtMs: Long = 0L,
    val errorsCount: Int = 0,
    val isRunning: Boolean = false,
    val result: ScreeningResult? = null,
    val isSubmitting: Boolean = false,
    val errorMessageResId: Int? = null
)

class ScreeningViewModel(private val repository: SuluReadRepository) : ViewModel() {
    private val _state = MutableStateFlow(ScreeningUiState())
    val state: StateFlow<ScreeningUiState> = _state.asStateFlow()

    fun start() {
        _state.value = ScreeningUiState(
            startedAtMs = SystemClock.elapsedRealtime(),
            isRunning = true
        )
    }

    fun addError() {
        val current = _state.value
        if (current.isRunning) {
            _state.value = current.copy(errorsCount = current.errorsCount + 1)
        }
    }

    fun finish(userId: String, wordsTotal: Int) {
        val current = _state.value
        if (!current.isRunning) return
        viewModelScope.launch {
            _state.value = current.copy(isSubmitting = true)
            val durationMs = SystemClock.elapsedRealtime() - current.startedAtMs
            val errors = current.errorsCount.coerceAtMost(wordsTotal)
            val result = runCatching {
                repository.submitReadingTest(
                    userId = userId,
                    wordsTotal = wordsTotal,
                    wordsReadCorrectly = wordsTotal - errors,
                    errorsCount = errors,
                    durationMs = durationMs
                )
            }
            _state.value = result.fold(
                onSuccess = { ScreeningUiState(result = it) },
                onFailure = {
                    current.copy(
                        isSubmitting = false,
                        errorMessageResId = R.string.screening_save_error
                    )
                }
            )
        }
    }
}
