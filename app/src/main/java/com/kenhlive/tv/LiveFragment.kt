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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_live, container, false)
        list = v.findViewById(R.id.liveList)
        state = StateBinder(v)

        adapter = SportAdapter(
            onGroupClick = { g -> openGroupPicker(g) },
            onGroupLong = { g -> openMultiView(g) },
            onFixtureClick = { m -> onFixtureTap(m) },
            onLeaguePick = { i -> selLeague = i; rebuild() },
            onGroupDetails = { g -> openGroupPicker(g) },
            onRoomClick = { r -> openRoom(r) }
        )
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        if (DeviceMode.lowRam) { list.itemAnimator = null }
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

        val enrichedLiveGroups = liveGroups.map { g ->
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
        }

        val baseCategories = listOf("Tất cả", "Bóng đá", "Bóng rổ")
        val activeLeagues = enrichedLiveGroups.map { it.league }
            .filter { it.isNotBlank() && it !in baseCategories }
            .distinct()
            .take(4)
        leagueLabels = baseCategories + activeLeagues
        if (selLeague >= leagueLabels.size) selLeague = 0

        val lg = when (selLeague) {
            0 -> enrichedLiveGroups
            1 -> enrichedLiveGroups.filter { it.category == "Bóng đá" || it.league.contains("bóng đá", ignoreCase = true) }
            2 -> enrichedLiveGroups.filter { it.category == "Bóng rổ" || it.league.contains("bóng rổ", ignoreCase = true) || it.league.contains("nba", ignoreCase = true) }
            else -> {
                val target = leagueLabels[selLeague]
                enrichedLiveGroups.filter { it.league.equals(target, ignoreCase = true) || it.category.equals(target, ignoreCase = true) }
            }
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

        // 1. Hero banner ở đầu tiên (kieu IMG_2815)
        items.add(SportAdapter.HeroItem(lg))

        // Phone layout: thêm chips lọc môn trên cùng
        if (!DeviceMode.isTv) {
            items.add(0, SportAdapter.ChipsItem(leagueLabels, selLeague))
        }

        // 2. Section 1: "Trực Tiếp & Tâm Điểm Thể Thao" (Row trận đấu trực tiếp)
        items.add(SportAdapter.RailItem(lg, upFiltered))

        // 3. Section 2: "Bình Luận Viên Tâm Điểm" (Row các BLV hot nhất của Socolive với thẻ gradient đa sắc)
        val allRooms = lg.flatMap { it.rooms }.sortedByDescending { it.viewers }
        if (allRooms.isNotEmpty()) {
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
        adapter.submitList(items)
        if (hadFocus) {
            list.post { list.post { FocusKit.restore(adapter) } }
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
        if (::adapter.isInitialized) adapter.restoreFocus()
    }

    override fun onPause() {
        super.onPause()
        vm.stopAutoRefresh()
        svm.stopAutoRefresh()
    }

    fun debugForceRefresh() { if (isAdded) { vm.silentRefresh(); svm.load(force = true) } }

    fun openMultiView(initial: LiveMatchGroup? = null) {
        val i = Intent(requireContext(), MultiViewActivity::class.java)
        if (initial != null) i.putExtra("initial_room", "${initial.top.matchTitle} · ${initial.top.blvName}")
        startActivity(i)
    }

    private fun openGroupPicker(g: LiveMatchGroup) {
        if (g.count == 1) { openRoom(g.top); return }
        dialog?.dismiss()
        dialog = RoomPickerDialog.show(requireContext(), g, onPickRoom = { r -> openRoom(r) })
    }

    private fun onFixtureTap(m: ScheduleMatch) {
        val live = m.anchors.filter { it.roomNum.isNotBlank() }
        if (live.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), R.string.sched_no_room, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val a = live.first()
        viewLifecycleOwner.lifecycleScope.launch {
            val url = SocoliveRepository.fetchStream(a.roomNum)
            if (url == null) {
                android.widget.Toast.makeText(requireContext(), R.string.stream_not_ready, android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            startActivity(
                Intent(requireContext(), PlayerActivity::class.java)
                    .putExtra("url", url)
                    .putExtra("name", "${m.host} vs ${m.guest} · ${a.nickName}")
            )
        }
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
            )
        }
    }

    override fun onDestroyView() {
        dialog?.dismiss()
        dialog = null
        super.onDestroyView()
    }
}
