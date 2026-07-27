package com.example.sulu_read.ui.screens

import androidx.annotation.StringRes

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(@StringRes val messageResId: Int) : UiState<Nothing>
}
