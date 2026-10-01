package com.kenhlive.tv.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kenhlive.tv.LiveMatchGroup
import com.kenhlive.tv.SocoliveRepository
import com.kenhlive.tv.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State của màn TRỰC TIẾP — sống sót qua rotation/đổi tab (ViewModel),
 * auto-refresh 3 phút, silent refresh không phá focus/scroll.
 */
class LiveViewModel : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<LiveMatchGroup>>>(UiState.Loading)
    val state: StateFlow<UiState<List<LiveMatchGroup>>> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var autoRefreshJob: Job? = null   // vòng lặp auto-refresh định kỳ
    private var silentJob: Job? = null        // 1 lần refresh nền đang chạy
    private var loadedOnce = false

    companion object {
        const val AUTO_REFRESH_MS = 3 * 60_000L
        const val RETRY_AFTER_ERROR_MS = 15_000L
    }

    fun load(force: Boolean = false) {
        if (!force && loadedOnce) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = UiState.Loading
            try {
                // force=false: dùng cache TTL 60s của repository (MainActivity và
                // LiveFragment có 2 instance ViewModel riêng — tránh bắn 2 request lúc mở app)
                val rooms = SocoliveRepository.fetchLiveRooms(force = force)
                val groups = SocoliveRepository.groupRooms(rooms)
                loadedOnce = true
                _state.value = if (groups.isEmpty())
                    UiState.Empty("Sân vắng bóng", "Hiện không có trận nào đang live")
                else UiState.Success(groups)
            } catch (e: Exception) {
                _state.value = UiState.Error(
                    "Không tải được danh sách trận",
                    "Kiểm tra kết nối mạng rồi thử lại"
                )
                scheduleAutoRetry()
            }
        }
    }

    /** Refresh nền: chỉ thay data khi có dữ liệu mới, giữ nguyên vị trí/focus. */
    fun silentRefresh() {
        // BUG cũ: dùng chung biến với vòng lặp auto-refresh → isActive luôn true
        // khi được chính vòng lặp gọi → auto-refresh không bao giờ chạy thật.
        if (!loadedOnce || silentJob?.isActive == true) return
        silentJob = viewModelScope.launch {
            try {
                val groups = SocoliveRepository.groupRooms(SocoliveRepository.fetchLiveRooms(force = true))
                if (groups.isNotEmpty()) {
                    loadedOnce = true
                    _state.value = UiState.Success(groups)
                }
            } catch (_: Exception) { /* giữ dữ liệu cũ */ }
        }
    }

    fun startAutoRefresh() {
        stopAutoRefresh()
        autoRefreshJob = viewModelScope.launch {
            while (true) {
                delay(AUTO_REFRESH_MS)
                silentRefresh()
            }
        }
    }

    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    private fun scheduleAutoRetry() {
        viewModelScope.launch {
            delay(RETRY_AFTER_ERROR_MS)
            if (_state.value is UiState.Error) load(force = true)
        }
    }

    override fun onCleared() {
        loadJob?.cancel()
        autoRefreshJob?.cancel()
        silentJob?.cancel()
    }
}
