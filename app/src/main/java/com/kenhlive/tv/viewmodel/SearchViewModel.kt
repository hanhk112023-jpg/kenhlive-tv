package com.kenhlive.tv.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kenhlive.tv.KenhLiveApp
import com.kenhlive.tv.LiveMatchGroup
import com.kenhlive.tv.SocoliveRepository
import com.kenhlive.tv.TextNorm
import com.kenhlive.tv.UiState
import com.kenhlive.tv.phim.NguoncFilm
import com.kenhlive.tv.phim.NguoncRepository
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

sealed class SearchItem {
    data class Match(val group: LiveMatchGroup) : SearchItem()
    data class Film(val film: NguoncFilm) : SearchItem()
}

/**
 * TÌM KIẾM ĐA NGUỒN:
 * 1. Trực tiếp bóng đá / thể thao Socolive
 * 2. Kho phim Nguồn C (phim.nguonc.com) với hơn 33.400+ đầu phim
 * Query debounce 250ms qua Flow — tìm kiếm mượt mà không giật lag.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel : ViewModel() {

    data class Result(
        val items: List<SearchItem>,
        val query: String,
        val chips: List<String>
    )

    private val _source = MutableStateFlow<UiState<List<LiveMatchGroup>>>(UiState.Loading)
    val source: StateFlow<UiState<List<LiveMatchGroup>>> = _source.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _result = MutableStateFlow(Result(emptyList(), "", emptyList()))
    val result: StateFlow<Result> = _result.asStateFlow()

    private var loaded = false
    private var allFilms: List<NguoncFilm> = emptyList()

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
                val films = try {
                    NguoncRepository.fetchNewFilms(KenhLiveApp.appContext, page = 1)
                } catch (_: Exception) {
                    emptyList()
                }
                allFilms = films
                loaded = true
                _source.value = UiState.Success(groups)

                val initialItems = mutableListOf<SearchItem>()
                initialItems.addAll(groups.map { SearchItem.Match(it) })
                initialItems.addAll(films.take(15).map { SearchItem.Film(it) })

                _result.value = Result(initialItems, _query.value, buildChips(groups))
                applyQuery(_query.value)
            } catch (e: Exception) {
                _source.value = UiState.Error("Không tải được danh sách", "Kiểm tra kết nối mạng rồi thử lại")
            }
        }
    }

    fun setQuery(q: String) {
        if (_query.value != q) _query.value = q
    }

    private fun buildChips(groups: List<LiveMatchGroup>): List<String> {
        val list = mutableListOf<String>()
        list.add("Phim Mới")
        list.add("Hoạt Hình")
        val leagues = groups.groupBy { it.league }.entries
            .sortedByDescending { e -> e.value.sumOf { g -> g.totalViewers } }
            .map { it.key }
            .filter { it.isNotBlank() }
            .distinct()
            .take(6)
        list.addAll(leagues)
        return list
    }

    private fun applyQuery(qRaw: String) {
        val src = (_source.value as? UiState.Success)?.data ?: emptyList()
        if (!loaded) return
        val q = TextNorm.norm(qRaw.trim())

        viewModelScope.launch {
            val matchedMatches = if (q.isEmpty()) src
            else src.filter { g ->
                TextNorm.norm(g.matchTitle).contains(q) ||
                        TextNorm.norm(g.league).contains(q) ||
                        g.rooms.any { TextNorm.norm(it.blvName).contains(q) }
            }

            val matchedFilms = if (q.isEmpty()) {
                allFilms.take(15)
            } else {
                try {
                    NguoncRepository.searchFilms(qRaw, KenhLiveApp.appContext)
                } catch (_: Exception) {
                    allFilms.filter {
                        TextNorm.norm(it.name).contains(q) ||
                                TextNorm.norm(it.originalName).contains(q) ||
                                it.categories.any { c -> TextNorm.norm(c).contains(q) }
                    }
                }
            }

            val combined = mutableListOf<SearchItem>()
            combined.addAll(matchedMatches.map { SearchItem.Match(it) })
            combined.addAll(matchedFilms.map { SearchItem.Film(it) })

            _result.value = Result(combined, qRaw.trim(), _result.value.chips)
        }
    }
}
