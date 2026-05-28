package com.example.sulu_read.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sulu_read.domain.model.ProgressSummary
import com.example.sulu_read.domain.repository.SuluReadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProgressViewModel(private val repository: SuluReadRepository) : ViewModel() {
    private val _state = MutableStateFlow<UiState<ProgressSummary>>(UiState.Loading)
    val state: StateFlow<UiState<ProgressSummary>> = _state.asStateFlow()

    fun load(userId: String) {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = runCatching { repository.getProgress(userId) }
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = { UiState.Error("Прогресті жүктеу мүмкін болмады.") }
                )
        }
    }
}
