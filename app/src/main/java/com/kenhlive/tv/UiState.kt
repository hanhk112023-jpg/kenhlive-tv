package com.kenhlive.tv

/** Trạng thái màn hình chuẩn — mọi fragment render theo 4 nhánh này. */
sealed class UiState<out T> {
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Empty(val title: String, val body: String = "") : UiState<Nothing>()
    data class Error(val title: String, val body: String = "", val retryable: Boolean = true) : UiState<Nothing>()

    val isLoading: Boolean get() = this is Loading
}
