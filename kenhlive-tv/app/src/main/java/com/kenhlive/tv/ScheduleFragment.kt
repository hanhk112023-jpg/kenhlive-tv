package com.kenhlive.tv

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kenhlive.tv.ui.RoomPickerDialog
import com.kenhlive.tv.ui.StateBinder
import com.kenhlive.tv.viewmodel.ScheduleViewModel
import kotlinx.coroutines.launch

/** LỊCH TRÌNH 7 ngày: header ngày + card trận; OK → chọn BLV có phòng live. */
class ScheduleFragment : Fragment() {

    private val vm: ScheduleViewModel by viewModels()
    private lateinit var adapter: ScheduleAdapter
    private lateinit var state: StateBinder
    private var dialog: AlertDialog? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_schedule, container, false)
        state = StateBinder(v)
        adapter = ScheduleAdapter(onMatchClick = { m -> onMatchTap(m) })
        v.findViewById<RecyclerView>(R.id.schedList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            itemAnimator = null
            clipChildren = false
            clipToPadding = false
            adapter = this@ScheduleFragment.adapter
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.state.collect { st ->
                state.render(st, R.string.sched_loading) { vm.load(force = true) }
                if (st is UiState.Success) adapter.submitList(st.data)
            }
        }
        vm.load()
        return v
    }

    override fun onResume() {
        super.onResume()
        vm.startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        vm.stopAutoRefresh()
    }

    private fun onMatchTap(match: ScheduleMatch) {
        val live = match.anchors.filter { it.roomNum.isNotBlank() }
        when {
            live.isEmpty() ->
                Toast.makeText(requireContext(), R.string.sched_no_room, Toast.LENGTH_SHORT).show()
            live.size == 1 -> openAnchor(live.first(), match)
            else -> {
                // picker: dựng group ảo từ các anchor có phòng
                val groups = live.map { a ->
                    LiveMatchGroup(
                        league = match.league,
                        matchTitle = "${match.host} vs ${match.guest}",
                        rooms = listOf(
                            LiveRoom(
                                roomNum = a.roomNum, blvName = a.nickName, avatar = a.icon,
                                viewers = 0, matchTitle = "${match.host} vs ${match.guest}", league = match.league
                            )
                        )
                    )
                }
                val pseudo = groups.first()
                dialog?.dismiss()
                dialog = RoomPickerDialog.show(
                    requireContext(),
                    pseudo.copy(rooms = groups.map { it.top }),
                    onPickRoom = { r ->
                        val a = live.firstOrNull { it.roomNum == r.roomNum } ?: live.first()
                        openAnchor(a, match)
                    }
                )
            }
        }
    }

    private fun openAnchor(anchor: AnchorInfo, match: ScheduleMatch) {
        viewLifecycleOwner.lifecycleScope.launch {
            val url = SocoliveRepository.fetchStream(anchor.roomNum)
            if (url == null) {
                Toast.makeText(requireContext(), R.string.stream_not_ready, Toast.LENGTH_SHORT).show()
                return@launch
            }
            startActivity(
                Intent(requireContext(), PlayerActivity::class.java)
                    .putExtra("url", url)
                    .putExtra("name", "${match.host} vs ${match.guest} · ${anchor.nickName}")
            )
        }
    }

    override fun onDestroyView() {
        dialog?.dismiss()
        dialog = null
        super.onDestroyView()
    }
}
