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
                if (st is UiState.Success) {
                    adapter.submitList(st.data) {
                        if (DeviceMode.isTv) focusFirstMatch()
                    }
                }
            }
        }
        vm.load()
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (DeviceMode.isTv) {
            view.postDelayed({ if (view.findFocus() == null) focusFirstMatch() }, 150)
            view.postDelayed({ if (view.findFocus() == null) focusFirstMatch() }, 400)
        }
    }

    override fun onResume() {
        super.onResume()
        vm.startAutoRefresh()
        if (DeviceMode.isTv) {
            view?.postDelayed({ if (view?.findFocus() == null) focusFirstMatch() }, 100)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && DeviceMode.isTv) {
            focusFirstMatch()
            view?.postDelayed({ if (view?.findFocus() == null) focusFirstMatch() }, 200)
        }
    }

    private fun focusFirstMatch() {
        val rv = view?.findViewById<RecyclerView>(R.id.schedList) ?: return
        rv.post {
            for (i in 0 until adapter.itemCount) {
                if (adapter.getItemViewType(i) == 1) { // TYPE_MATCH
                    val vh = rv.findViewHolderForAdapterPosition(i)
                    if (vh != null) {
                        vh.itemView.requestFocus()
                        return@post
                    } else {
                        rv.scrollToPosition(i)
                        rv.postDelayed({
                            rv.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus()
                        }, 80)
                        rv.postDelayed({
                            if (view?.findFocus() == null) {
                                rv.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus()
                            }
                        }, 250)
                        return@post
                    }
                }
            }
            // Nếu chưa có item nào (đang tải), tự động thử lại
            if (adapter.itemCount == 0 && isResumed) {
                rv.postDelayed({ if (view?.findFocus() == null) focusFirstMatch() }, 300)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        vm.stopAutoRefresh()
    }

    private fun onMatchTap(match: ScheduleMatch) {
        val live = match.anchors.filter { it.roomNum.isNotBlank() }
        if (live.isEmpty()) {
            Toast.makeText(requireContext(), R.string.sched_no_room, Toast.LENGTH_SHORT).show()
            return
        }
        val matchTitle = "${match.host} vs ${match.guest}"
        val rooms = live.map { a ->
            LiveRoom(
                roomNum = a.roomNum, blvName = a.nickName, avatar = a.icon,
                viewers = 0, matchTitle = matchTitle, league = match.league,
                category = match.category, hostIcon = match.hostIcon, guestIcon = match.guestIcon
            )
        }
        val group = com.kenhlive.tv.sports.SportsAggregator.enrichMatchGroup(
            LiveMatchGroup(league = match.league, matchTitle = matchTitle, rooms = rooms, category = match.category, hostIcon = match.hostIcon, guestIcon = match.guestIcon)
        )
        dialog?.dismiss()
        dialog = RoomPickerDialog.show(
            requireContext(),
            group,
            onPickRoom = { r -> openRoom(r, match) }
        )
    }

    private fun openRoom(room: LiveRoom, match: ScheduleMatch) {
        viewLifecycleOwner.lifecycleScope.launch {
            val url = SocoliveRepository.fetchStream(room.roomNum)
            if (url == null) {
                Toast.makeText(requireContext(), R.string.stream_not_ready, Toast.LENGTH_SHORT).show()
                return@launch
            }
            startActivity(
                Intent(requireContext(), PlayerActivity::class.java)
                    .putExtra("url", url)
                    .putExtra("name", "${match.host} vs ${match.guest} · ${room.blvName}")
                    .putExtra("roomNum", room.roomNum)
                    .putExtra("matchTitle", "${match.host} vs ${match.guest}")
                    .putExtra("league", match.league)
            )
        }
    }

    override fun onDestroyView() {
        dialog?.dismiss()
        dialog = null
        super.onDestroyView()
    }
}
