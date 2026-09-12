package com.kenhlive.tv.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kenhlive.tv.LiveMatchGroup
import com.kenhlive.tv.SocoliveRepository
import com.kenhlive.tv.TextNorm
import com.kenhlive.tv.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * TÌM KIẾM: nguồn = danh sách phòng live (cache chung với tab Live),
 * query debounce 250ms qua Flow — không spam rebind khi gõ.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel : ViewModel() {

    data class Result(val groups: List<LiveMatchGroup>, val query: String, val chips: List<String>)

    private val _source = MutableStateFlow<UiState<List<LiveMatchGroup>>>(UiState.Loading)
    val source: StateFlow<UiState<List<LiveMatchGroup>>> = _source.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _result = MutableStateFlow(Result(emptyList(), "", emptyList()))
    val result: StateFlow<Result> = _result.asStateFlow()

    private var loaded = false

    init {
        _query
            .debounce(250)
            .distinctUntilChanged()
            .onEach { q -> applyQuery(q) }
            .launchIn(viewModelScope)
    }

    fun load(force: Boolean = false) {
        if (loaded && !force) return
        viewModelScope.launch {
            _source.value = UiState.Loading
            try {
                val groups = SocoliveRepository.groupRooms(SocoliveRepository.fetchLiveRooms(force = true))
                loaded = true
                _source.value = UiState.Success(groups)
                _result.value = Result(groups, _query.value, buildChips(groups))
                applyQuery(_query.value)
            } catch (e: Exception) {
                _source.value = UiState.Error("Không tải được danh sách", "Kiểm tra kết nối mạng rồi thử lại")
            }
        }
    }

    fun setQuery(q: String) {
        if (_query.value != q) _query.value = q
    }

    private fun buildChips(groups: List<LiveMatchGroup>): List<String> =
        groups.groupBy { it.league }.entries
            .sortedByDescending { e -> e.value.sumOf { g -> g.totalViewers } }
            .map { it.key }
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)

    private fun applyQuery(qRaw: String) {
        val src = (_source.value as? UiState.Success)?.data ?: emptyList()
        if (!loaded) return
        val q = TextNorm.norm(qRaw.trim())
        val res = if (q.isEmpty()) src
        else src.filter { g ->
            TextNorm.norm(g.matchTitle).contains(q) ||
                TextNorm.norm(g.league).contains(q) ||
                g.rooms.any { TextNorm.norm(it.blvName).contains(q) }
        }
        _result.value = Result(res, qRaw.trim(), (_result.value.chips))
    }
}
