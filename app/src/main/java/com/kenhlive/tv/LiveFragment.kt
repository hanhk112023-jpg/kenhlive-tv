package com.kenhlive.tv

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kenhlive.tv.ui.FocusKit
import com.kenhlive.tv.ui.RoomPickerDialog
import com.kenhlive.tv.ui.StateBinder
import com.kenhlive.tv.viewmodel.LiveViewModel
import com.kenhlive.tv.viewmodel.ScheduleViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * MÀN HÌNH CHÍNH — Cấu trúc giao diện kiểu IMG_2815, tùy chỉnh riêng cho SocoliveTV:
 *  1. Hero Banner: Trận đấu tâm điểm, đồng hồ số & lịch âm dương, nút "Xem ngay" + "Chi tiết"
 *  2. "Trực Tiếp & Tâm Điểm Thể Thao": Thẻ trận ngang có tỉ số 0:0, cờ 2 đội, badge LIVE / đếm ngược
 *  3. "Bình Luận Viên Tâm Điểm": Hàng thẻ phòng live BLV Socolive nổi bật với màu sắc gradient sang trọng
 *  4. "Khám Phá Nhanh" / Lịch thi đấu theo ngày
 */
class LiveFragment : Fragment() {

    private val vm: LiveViewModel by viewModels()
    private val svm: ScheduleViewModel by viewModels()
    private lateinit var adapter: SportAdapter
    private lateinit var list: RecyclerView
    private lateinit var state: StateBinder
    private var dialog: AlertDialog? = null

    private var leagueLabels: List<String> = listOf()
    private var selLeague = 0

    private var liveGroups: List<LiveMatchGroup> = listOf()
    private var daysLabeled: LinkedHashMap<String, MutableList<ScheduleMatch>> = LinkedHashMap()
    private var colaApiGroups: List<LiveMatchGroup> = listOf()
    private var gavang33ApiGroups: List<LiveMatchGroup> = listOf()
    private var giovangApiGroups: List<LiveMatchGroup> = listOf()
    private var providersRefreshJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_live, container, false)
        list = v.findViewById(R.id.liveList)
        state = StateBinder(v)

        adapter = SportAdapter(
            onGroupClick = { g -> openGroupPicker(g) },
            onGroupLong = { g -> openGroupPicker(g) },
            onFixtureClick = { m -> onFixtureTap(m) },
            onLeaguePick = { i -> selLeague = i; rebuild() },
            onGroupDetails = { g -> openGroupPicker(g) },
            onRoomClick = { r -> openRoom(r) }
        )
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        if (DeviceMode.lowRam) { list.itemAnimator = null }

        if (DeviceMode.isTv) {
            list.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
                if (newFocus == null && isResumed) {
                    list.post {
                        if (list.findFocus() == null) {
                            FocusKit.firstFocusableIn(list)?.requestFocus()
                        }
                    }
                }
            }
        }
        list.clipChildren = false
        list.clipToPadding = false

        viewLifecycleOwner.lifecycleScope.launch {
            vm.state.collect { st ->
                if (st is UiState.Success) liveGroups = st.data
                rebuild()
                if ((st is UiState.Loading || st is UiState.Error) && liveGroups.isEmpty())
                    state.render(st, R.string.live_loading) { vm.load(force = true); svm.load(force = true) }
                else state.hide()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            svm.state.collect { st ->
                if (st is UiState.Success) {
                    val flat = st.data
                    val grouped = LinkedHashMap<String, MutableList<ScheduleMatch>>()
                    var cur = ""
                    for (o in flat) when (o) {
                        is String -> { cur = o; grouped.putIfAbsent(o, mutableListOf()) }
                        is ScheduleMatch -> grouped.getOrPut(cur) { mutableListOf() }.add(o)
                    }
                    daysLabeled = grouped
                    rebuild()
                }
            }
        }
        vm.load()
        svm.load()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                colaApiGroups = com.kenhlive.tv.sports.SportsStreamResolver.fetchColaMatchGroups()
                rebuild()
            } catch (_: Exception) {}
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                gavang33ApiGroups = com.kenhlive.tv.sports.SportsStreamResolver.fetchGavang33MatchGroups()
                rebuild()
            } catch (_: Exception) {}
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                giovangApiGroups = com.kenhlive.tv.sports.SportsStreamResolver.fetchGiovangMatchGroups()
                rebuild()
            } catch (_: Exception) {}
        }
        return v
    }

    private fun rebuild() {
        if (!::adapter.isInitialized) return

        // Ánh xạ cờ/logo 2 đội từ dữ liệu lịch thi đấu / đề xuất
        val allScheduleMatches = daysLabeled.values.flatten()
        val iconMap = mutableMapOf<String, Pair<String, String>>()
        for (m in allScheduleMatches) {
            if (m.hostIcon.isNotBlank() || m.guestIcon.isNotBlank()) {
                val key = "${m.host} vs ${m.guest}".lowercase().trim()
                iconMap[key] = m.hostIcon to m.guestIcon
            }
        }

        val enrichedLiveGroups = com.kenhlive.tv.sports.SportsAggregator.enrichMatchGroups(liveGroups.map { g ->
            if (g.hostIcon.isNotBlank() && g.guestIcon.isNotBlank()) g
            else {
                val key = g.matchTitle.lowercase().trim()
                val found = iconMap[key] ?: iconMap.entries.firstOrNull { (k, _) ->
                    k in key || key in k
                }?.value
                if (found != null) {
                    g.copy(hostIcon = found.first, guestIcon = found.second)
                } else g
            }
        })

        val baseCategories = listOf("Tất cả", "Bóng đá", "Bóng rổ", "ColaTV", "Gà Vàng 33", "Giờ Vàng")
        val activeLeagues = enrichedLiveGroups.map { it.league }
            .filter { it.isNotBlank() && it !in baseCategories }
            .distinct()
            .take(4)
        leagueLabels = baseCategories + activeLeagues
        if (selLeague >= leagueLabels.size) selLeague = 0

        val socoGroups = com.kenhlive.tv.sports.SportsAggregator.createProviderMatchGroups(enrichedLiveGroups, com.kenhlive.tv.sports.SportsSource.SOCOLIVE)
        val colaGroups = colaApiGroups.ifEmpty {
            com.kenhlive.tv.sports.SportsAggregator.createProviderMatchGroups(enrichedLiveGroups, com.kenhlive.tv.sports.SportsSource.COLATV)
        }
        val gavang33Groups = gavang33ApiGroups.ifEmpty {
            com.kenhlive.tv.sports.SportsAggregator.createProviderMatchGroups(enrichedLiveGroups, com.kenhlive.tv.sports.SportsSource.GAVANG33)
        }
        val giovangGroups = giovangApiGroups.ifEmpty {
            com.kenhlive.tv.sports.SportsAggregator.createProviderMatchGroups(enrichedLiveGroups, com.kenhlive.tv.sports.SportsSource.GIOVANG)
        }

        val now = System.currentTimeMillis()
        val upcoming = allScheduleMatches
            .filter { it.matchTimeMs in (now - 30 * 60_000L)..(now + 24 * 3600_000L) && !it.isLive || (it.isLive && it.hasRoom && enrichedLiveGroups.none { g -> g.matchTitle == "${it.host} vs ${it.guest}" }) }
            .sortedBy { it.matchTimeMs }
        val upFiltered = when (selLeague) {
            0 -> upcoming
            1 -> upcoming.filter { it.category == "Bóng đá" }
            2 -> upcoming.filter { it.category == "Bóng rổ" }
            else -> {
                val target = leagueLabels[selLeague]
                upcoming.filter { it.league.equals(target, ignoreCase = true) || it.category.equals(target, ignoreCase = true) }
            }
        }

        val items = mutableListOf<Any>()

        when {
            selLeague == 0 -> {
                // 1. Hero banner ở đầu tiên
                items.add(SportAdapter.HeroItem(socoGroups.ifEmpty { enrichedLiveGroups }))

                // Phone layout: thêm chips lọc môn trên cùng
                if (!DeviceMode.isTv) {
                    items.add(0, SportAdapter.ChipsItem(leagueLabels, selLeague))
                }

                // 2. Từng đài phát sóng thành từng dòng riêng biệt (không gộp chung)
                if (socoGroups.isNotEmpty() || upFiltered.isNotEmpty()) {
                    items.add(SportAdapter.RailItem(socoGroups, upFiltered, title = "Trực Tiếp Socolive"))
                }
                if (colaGroups.isNotEmpty()) {
                    items.add(SportAdapter.RailItem(colaGroups, emptyList(), title = "Trực Tiếp ColaTV"))
                }
                if (gavang33Groups.isNotEmpty()) {
                    items.add(SportAdapter.RailItem(gavang33Groups, emptyList(), title = "Trực Tiếp Gà Vàng 33"))
                }
                if (giovangGroups.isNotEmpty()) {
                    items.add(SportAdapter.RailItem(giovangGroups, emptyList(), title = "Trực Tiếp Giờ Vàng TV"))
                }
            }
            leagueLabels[selLeague] == "ColaTV" -> {
                items.add(SportAdapter.HeroItem(colaGroups))
                if (!DeviceMode.isTv) items.add(0, SportAdapter.ChipsItem(leagueLabels, selLeague))
                items.add(SportAdapter.RailItem(colaGroups, upFiltered, title = "Trực Tiếp ColaTV"))
            }
            leagueLabels[selLeague] == "Gà Vàng 33" -> {
                items.add(SportAdapter.HeroItem(gavang33Groups))
                if (!DeviceMode.isTv) items.add(0, SportAdapter.ChipsItem(leagueLabels, selLeague))
                items.add(SportAdapter.RailItem(gavang33Groups, upFiltered, title = "Trực Tiếp Gà Vàng 33"))
            }
            leagueLabels[selLeague] == "Giờ Vàng" -> {
                items.add(SportAdapter.HeroItem(giovangGroups))
                if (!DeviceMode.isTv) items.add(0, SportAdapter.ChipsItem(leagueLabels, selLeague))
                items.add(SportAdapter.RailItem(giovangGroups, upFiltered, title = "Trực Tiếp Giờ Vàng TV"))
            }
            else -> {
                val target = leagueLabels[selLeague]
                val socoF = socoGroups.filter { it.category.equals(target, ignoreCase = true) || it.league.contains(target, ignoreCase = true) }
                val colaF = colaGroups.filter { it.category.equals(target, ignoreCase = true) || it.league.contains(target, ignoreCase = true) }
                val gavang33F = gavang33Groups.filter { it.category.equals(target, ignoreCase = true) || it.league.contains(target, ignoreCase = true) }
                val giovangF = giovangGroups.filter { it.category.equals(target, ignoreCase = true) || it.league.contains(target, ignoreCase = true) }
                val allF = socoF + colaF + gavang33F + giovangF

                items.add(SportAdapter.HeroItem(allF.ifEmpty { socoGroups }))
                if (!DeviceMode.isTv) items.add(0, SportAdapter.ChipsItem(leagueLabels, selLeague))

                if (socoF.isNotEmpty() || upFiltered.isNotEmpty()) {
                    items.add(SportAdapter.RailItem(socoF, upFiltered, title = "Trực Tiếp Socolive · $target"))
                }
                if (colaF.isNotEmpty()) {
                    items.add(SportAdapter.RailItem(colaF, emptyList(), title = "Trực Tiếp ColaTV · $target"))
                }
                if (gavang33F.isNotEmpty()) {
                    items.add(SportAdapter.RailItem(gavang33F, emptyList(), title = "Trực Tiếp Gà Vàng 33 · $target"))
                }
                if (giovangF.isNotEmpty()) {
                    items.add(SportAdapter.RailItem(giovangF, emptyList(), title = "Trực Tiếp Giờ Vàng TV · $target"))
                }
            }
        }

        // 3. Section 2: "Bình Luận Viên Tâm Điểm" (Chỉ hiện khi ở tab Tất cả / không lọc riêng)
        // Không render ở hàng thứ 2 nếu đang test hoặc để tránh làm trượt focus khi refresh
        val allRooms = enrichedLiveGroups.flatMap { it.rooms }.sortedByDescending { it.viewers }
        if (allRooms.isNotEmpty() && !DeviceMode.isTv) {
            items.add(SportAdapter.BlvRowItem(allRooms))
        }

        // 4. Section 3: "Khám Phá Nhanh" / Danh sách trận đấu theo ngày
        for ((label, ms0) in daysLabeled) {
            val ms = when (selLeague) {
                0 -> ms0
                1 -> ms0.filter { it.category == "Bóng đá" }
                2 -> ms0.filter { it.category == "Bóng rổ" }
                else -> {
                    val target = leagueLabels[selLeague]
                    ms0.filter { it.league.equals(target, ignoreCase = true) || it.category.equals(target, ignoreCase = true) }
                }
            }
            if (ms.isEmpty()) continue
            items.add(label)
            items.addAll(ms)
        }

        val hadFocus = list.hasFocus() && FocusKit.lastSlot != null
        val wasEmpty = adapter.itemCount == 0
        adapter.submitList(items)
        if (hadFocus) {
            list.post { list.post { FocusKit.restore(adapter) } }
        } else if (items.isNotEmpty() && DeviceMode.isTv) {
            // Đảm bảo luôn có 1 view cụ thể (heroPlay hoặc card) nhận focus trên màn hình TV
            list.postDelayed({
                val curF = list.findFocus()
                if (curF == null || curF == list || curF is RecyclerView || curF is androidx.viewpager2.widget.ViewPager2) {
                    if (!adapter.focusHero()) {
                        FocusKit.focusNow(list, 1, 0)
                    }
                }
            }, 60)
        }
        if (items.size > 2) state.hide()
        else if (liveGroups.isEmpty() && daysLabeled.isEmpty()) {
            state.render(UiState.Empty("Sân vắng bóng", "Hiện không có trận nào đang live"), R.string.live_loading)
        }
    }

    override fun onResume() {
        super.onResume()
        vm.startAutoRefresh()
        svm.startAutoRefresh()
        startProvidersAutoRefresh()
        if (::adapter.isInitialized) {
            adapter.restoreFocus()
            if (DeviceMode.isTv) {
                list.postDelayed({
                    val curF = list.findFocus()
                    if (curF == null || curF == list || curF is RecyclerView || curF is androidx.viewpager2.widget.ViewPager2) {
                        if (!adapter.focusHero()) {
                            FocusKit.focusNow(list, 1, 0)
                        }
                    }
                }, 120)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        vm.stopAutoRefresh()
        svm.stopAutoRefresh()
        stopProvidersAutoRefresh()
    }

    private fun startProvidersAutoRefresh() {
        stopProvidersAutoRefresh()
        providersRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(60_000L) // Quét ngầm tỉ số & trận mới mỗi 60 giây
                refreshProvidersSilently()
            }
        }
    }

    private fun stopProvidersAutoRefresh() {
        providersRefreshJob?.cancel()
        providersRefreshJob = null
    }

    private suspend fun refreshProvidersSilently() {
        try {
            val colaDeferred = viewLifecycleOwner.lifecycleScope.async(Dispatchers.IO) {
                try { com.kenhlive.tv.sports.SportsStreamResolver.fetchColaMatchGroups() } catch (_: Exception) { emptyList() }
            }
            val gavangDeferred = viewLifecycleOwner.lifecycleScope.async(Dispatchers.IO) {
                try { com.kenhlive.tv.sports.SportsStreamResolver.fetchGavang33MatchGroups() } catch (_: Exception) { emptyList() }
            }
            val giovangDeferred = viewLifecycleOwner.lifecycleScope.async(Dispatchers.IO) {
                try { com.kenhlive.tv.sports.SportsStreamResolver.fetchGiovangMatchGroups() } catch (_: Exception) { emptyList() }
            }

            val newCola = colaDeferred.await()
            val newGavang = gavangDeferred.await()
            val newGiovang = giovangDeferred.await()

            var changed = false
            if (newCola.isNotEmpty() && newCola != colaApiGroups) {
                colaApiGroups = newCola
                changed = true
            }
            if (newGavang.isNotEmpty() && newGavang != gavang33ApiGroups) {
                gavang33ApiGroups = newGavang
                changed = true
            }
            if (newGiovang.isNotEmpty() && newGiovang != giovangApiGroups) {
                giovangApiGroups = newGiovang
                changed = true
            }

            if (changed) {
                android.util.Log.i("LiveFragment", "Auto-refresh: sports data/scores updated, rebuilding via DiffUtil...")
                rebuild()
            }
        } catch (_: Exception) {}
    }

    fun debugForceRefresh() { if (isAdded) { vm.silentRefresh(); svm.load(force = true) } }

    fun openMultiView(initial: LiveMatchGroup? = null) {
        val i = Intent(requireContext(), MultiViewActivity::class.java)
        if (initial != null) i.putExtra("initial_room", "${initial.top.matchTitle} · ${initial.top.blvName}")
        startActivity(i)
    }

    private fun openGroupPicker(g: LiveMatchGroup) {
        val topRoom = g.rooms.firstOrNull()
        if (topRoom != null && (
                topRoom.roomNum.startsWith("cola_") ||
                topRoom.roomNum.startsWith("gavang33_") ||
                topRoom.roomNum.startsWith("gavang_") ||
                topRoom.roomNum.startsWith("giovang_") ||
                topRoom.roomNum.startsWith("khandai_")
            )
        ) {
            if (g.rooms.size == 1) {
                openRoom(topRoom)
            } else {
                dialog?.dismiss()
                dialog = RoomPickerDialog.show(requireContext(), g, onPickRoom = { r -> openRoom(r) })
            }
            return
        }
        val enriched = com.kenhlive.tv.sports.SportsAggregator.enrichMatchGroup(g)
        dialog?.dismiss()
        dialog = RoomPickerDialog.show(requireContext(), enriched, onPickRoom = { r -> openRoom(r) })
    }

    private fun onFixtureTap(m: ScheduleMatch) {
        val live = m.anchors.filter { it.roomNum.isNotBlank() }
        if (live.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), R.string.sched_no_room, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val matchTitle = "${m.host} vs ${m.guest}"
        val rooms = live.map { a ->
            LiveRoom(
                roomNum = a.roomNum, blvName = a.nickName, avatar = a.icon,
                viewers = 0, matchTitle = matchTitle, league = m.league,
                category = m.category, hostIcon = m.hostIcon, guestIcon = m.guestIcon
            )
        }
        val group = com.kenhlive.tv.sports.SportsAggregator.enrichMatchGroup(
            LiveMatchGroup(league = m.league, matchTitle = matchTitle, rooms = rooms, category = m.category, hostIcon = m.hostIcon, guestIcon = m.guestIcon)
        )
        dialog?.dismiss()
        dialog = RoomPickerDialog.show(requireContext(), group, onPickRoom = { r -> openRoom(r) })
    }

    private fun openRoom(room: LiveRoom) {
        viewLifecycleOwner.lifecycleScope.launch {
            val url = SocoliveRepository.fetchStream(room.roomNum)
            if (url == null) {
                android.widget.Toast.makeText(requireContext(), R.string.stream_not_ready, android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            startActivity(
                Intent(requireContext(), PlayerActivity::class.java)
                    .putExtra("url", url)
                    .putExtra("name", "${room.matchTitle} · ${room.blvName}")
                    .putExtra("roomNum", room.roomNum)
                    .putExtra("matchTitle", room.matchTitle)
                    .putExtra("league", room.league)
            )
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            dialog?.dismiss()
            dialog = null
            view?.clearFocus()
            (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        } else {
            (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }

    override fun onDestroyView() {
        stopProvidersAutoRefresh()
        dialog?.dismiss()
        dialog = null
        super.onDestroyView()
    }
}
