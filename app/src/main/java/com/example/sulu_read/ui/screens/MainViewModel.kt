package com.example.sulu_read.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sulu_read.R
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
                    onFailure = { UiState.Error(R.string.error_profile_create) }
                )
        }
    }

    fun changeLanguage(languageCode: String) {
        viewModelScope.launch {
            val currentUserId = (_userState.value as? UiState.Success<UserProfile>)?.data?.userId
            repository.saveLanguageCode(languageCode, currentUserId)
            if (currentUserId != null) {
                _userState.value = runCatching { repository.ensureUser() }
                    .fold(
                        onSuccess = { UiState.Success(it) },
                        onFailure = { _userState.value }
                    )
            }
        }
    }

    fun register(username: String, password: String, displayName: String) {
        viewModelScope.launch {
            _userState.value = UiState.Loading
            _userState.value = runCatching {
                repository.registerUser(username, password, displayName)
            }.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(R.string.error_account_auth) }
            )
        }
    }

    fun login(username: String, password: String) {
        viewModelScope.launch {
            _userState.value = UiState.Loading
            _userState.value = runCatching {
                repository.loginUser(username, password)
            }.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(R.string.error_account_auth) }
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
