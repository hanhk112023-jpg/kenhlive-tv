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
    private var refreshLoopJob: Job? = null
    private var silentFetchJob: Job? = null
    private var loadedOnce = false

    companion object {
        const val AUTO_REFRESH_MS = 3 * 60_000L
        const val RETRY_AFTER_ERROR_MS = 15_000L
    }

    fun load(force: Boolean = false) {
        if (!force && loadedOnce) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // Bước 1: Nếu chưa từng nạp, kiểm tra Disk Cache hiển thị ngay lập tức (0s delay)
            if (!loadedOnce) {
                val cached = SocoliveRepository.getCachedLiveRoomsDisk()
                if (!cached.isNullOrEmpty()) {
                    val cachedGroups = SocoliveRepository.groupRooms(cached)
                    if (cachedGroups.isNotEmpty()) {
                        _state.value = UiState.Success(cachedGroups)
                    }
                } else {
                    _state.value = UiState.Loading
                }
            }

            // Bước 2: Tải dữ liệu mới nhất từ mạng
            try {
                val rooms = SocoliveRepository.fetchLiveRooms(force = true)
                val groups = SocoliveRepository.groupRooms(rooms)
                loadedOnce = true
                _state.value = if (groups.isEmpty())
                    UiState.Empty("Sân vắng bóng", "Hiện không có trận nào đang live")
                else UiState.Success(groups)
            } catch (e: Exception) {
                // Nếu đã có dữ liệu cache disk đang hiển thị thì không đè lỗi lên
                if (_state.value !is UiState.Success) {
                    _state.value = UiState.Error(
                        "Không tải được danh sách trận",
                        "Kiểm tra kết nối mạng rồi thử lại"
                    )
                    scheduleAutoRetry()
                }
            }
        }
    }

    /** Refresh nền: chỉ thay data khi có dữ liệu mới, giữ nguyên vị trí/focus. */
    fun silentRefresh() {
        if (!loadedOnce || silentFetchJob?.isActive == true) return
        silentFetchJob = viewModelScope.launch {
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
        refreshLoopJob = viewModelScope.launch {
            while (true) {
                delay(AUTO_REFRESH_MS)
                silentRefresh()
            }
        }
    }

    fun stopAutoRefresh() {
        refreshLoopJob?.cancel()
        refreshLoopJob = null
    }

    private fun scheduleAutoRetry() {
        viewModelScope.launch {
            delay(RETRY_AFTER_ERROR_MS)
            if (_state.value is UiState.Error) load(force = true)
        }
    }

    override fun onCleared() {
        loadJob?.cancel()
        refreshLoopJob?.cancel()
        silentFetchJob?.cancel()
    }
}
