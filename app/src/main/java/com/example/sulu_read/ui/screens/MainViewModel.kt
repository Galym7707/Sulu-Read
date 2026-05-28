package com.example.sulu_read.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sulu_read.domain.model.UserProfile
import com.example.sulu_read.domain.repository.SuluReadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(private val repository: SuluReadRepository) : ViewModel() {
    private val _userState = MutableStateFlow<UiState<UserProfile>>(UiState.Loading)
    val userState: StateFlow<UiState<UserProfile>> = _userState.asStateFlow()

    init {
        ensureUser()
    }

    fun ensureUser() {
        viewModelScope.launch {
            _userState.value = UiState.Loading
            _userState.value = runCatching { repository.ensureUser() }
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = { UiState.Error("Не удалось создать профиль. Проверьте подключение.") }
                )
        }
    }
}

class SuluReadViewModelFactory(
    private val repository: SuluReadRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(MainViewModel::class.java) -> MainViewModel(repository) as T
            modelClass.isAssignableFrom(TrainingViewModel::class.java) -> TrainingViewModel(repository) as T
            modelClass.isAssignableFrom(ScreeningViewModel::class.java) -> ScreeningViewModel(repository) as T
            modelClass.isAssignableFrom(ProgressViewModel::class.java) -> ProgressViewModel(repository) as T
            modelClass.isAssignableFrom(ProfileViewModel::class.java) -> ProfileViewModel(repository) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
