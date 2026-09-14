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
import com.kenhlive.tv.ui.RoomPickerDialog
import com.kenhlive.tv.ui.StateBinder
import com.kenhlive.tv.viewmodel.LiveViewModel
import com.kenhlive.tv.viewmodel.ScheduleViewModel
import kotlinx.coroutines.launch

/**
 * MAN BONG DA — cau truc Sport Zone (lay cam FPT Play moi):
 *  chip giai dau -> rail "Truc tiep & Sap dien ra" -> danh sach theo ngay.
 * Data: LiveViewModel (all_live_rooms) + ScheduleViewModel (matches_YYYYMMDD), cache san.
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
            onLeaguePick = { i -> selLeague = i; rebuild() }
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
                // Da co noi dung (live HOAC lich) -> an overlay, giu UI kieu FPT va im lang retry nen.
                // Chua co gi -> dung overlay cua StateBinder: Loading / Error / Empty "San vang bong".
                // (Truoc day nhanh nay goi state.hide() vo dieu kien nen UiState.Empty cua
                //  LiveViewModel bi an mat -> man hinh trong khong khi khong co tran nao.)
                val hasContent = liveGroups.isNotEmpty() || daysLabeled.isNotEmpty()
                when {
                    hasContent -> state.hide()
                    st is UiState.Success -> Unit // rebuild() da dat Empty
                    else -> state.render(st, R.string.live_loading) {
                        vm.load(force = true); svm.load(force = true)
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            svm.state.collect { st ->
                if (st is UiState.Success) {
                    // ScheduleViewModel tra danh sach FLATTEN (String header ngay | ScheduleMatch)
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

    private fun leagueOf(g: LiveMatchGroup): String = g.league
    private fun leagueOf(m: ScheduleMatch): String = m.league.ifBlank { m.category }

    private fun rebuild() {
        if (!::adapter.isInitialized) return
        // chip = MON (dinh), khong phai danh sach giai dai dong: Tat ca / Bong da / Bong ro
        if (leagueLabels.size != 3) leagueLabels = listOf("Tất cả", "Bóng đá", "Bóng rổ")
        if (selLeague >= leagueLabels.size) selLeague = 0

        val lg = if (selLeague == 0) liveGroups
        else liveGroups.filter { it.category == leagueLabels[selLeague] }

        val now = System.currentTimeMillis()
        val upcoming = daysLabeled.values.flatten()
            .filter { it.matchTimeMs in (now - 30 * 60_000L)..(now + 24 * 3600_000L) && !it.isLive || (it.isLive && it.hasRoom && liveGroups.none { g -> g.matchTitle == "${it.host} vs ${it.guest}" }) }
            .sortedBy { it.matchTimeMs }
        val upFiltered = if (selLeague == 0) upcoming
        else upcoming.filter { it.category == leagueLabels[selLeague] }

        val items = mutableListOf<Any>()
        items.add(SportAdapter.ChipsItem(leagueLabels, selLeague))
        items.add(SportAdapter.HeroItem(lg))
        items.add(SportAdapter.RailItem(lg, upFiltered))
        for ((label, ms0) in daysLabeled) {
            val ms = if (selLeague == 0) ms0 else ms0.filter { it.category == leagueLabels[selLeague] }
            if (ms.isEmpty()) continue
            items.add(label)
            items.addAll(ms)
        }
        adapter.submitList(items)
        if (items.size > 3) state.hide()
        else if (liveGroups.isEmpty() && daysLabeled.isEmpty())
            state.render(
                UiState.Empty(
                    getString(R.string.live_empty_title),
                    getString(R.string.live_empty_body)
                ),
                R.string.live_loading
            )
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

    /** QA hook (`--es open refresh`). */
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

    /** Tap tran sap toi: mo phong BLV dau tien co phong (neu khong -> toast). */
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
