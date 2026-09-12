package com.kenhlive.tv.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kenhlive.tv.SocoliveRepository
import com.kenhlive.tv.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Lịch 7 ngày, flatten thành danh sách item (String = header ngày | ScheduleMatch). */
class ScheduleViewModel : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Any>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Any>>> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadedOnce = false

    companion object { const val AUTO_REFRESH_MS = 10 * 60_000L }

    fun load(force: Boolean = false) {
        if (!force && loadedOnce) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = UiState.Loading
            try {
                val items = flatten(SocoliveRepository.fetchSchedule(7, force = true))
                loadedOnce = true
                _state.value = if (items.isEmpty())
                    UiState.Empty("Sân vắng bóng", "Không có trận nào 7 ngày tới")
                else UiState.Success(items)
            } catch (e: Exception) {
                _state.value = UiState.Error("Không tải được lịch", "Kiểm tra kết nối mạng rồi thử lại")
            }
        }
    }

    fun silentRefresh() {
        if (!loadedOnce) return
        viewModelScope.launch {
            try {
                val items = flatten(SocoliveRepository.fetchSchedule(7, force = true))
                if (items.isNotEmpty()) _state.value = UiState.Success(items)
            } catch (_: Exception) { }
        }
    }

    private var refreshJob: Job? = null

    fun startAutoRefresh() {
        stopAutoRefresh()
        refreshJob = viewModelScope.launch {
            while (true) {
                delay(AUTO_REFRESH_MS)
                silentRefresh()
            }
        }
    }

    fun stopAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun flatten(days: List<com.kenhlive.tv.DaySchedule>): List<Any> {
        val items = mutableListOf<Any>()
        for (d in days) {
            if (d.matches.isEmpty()) continue
            items.add(SocoliveRepository.dayLabel(d.date))
            items.addAll(d.matches)
        }
        return items
    }

    override fun onCleared() { loadJob?.cancel() }
}
