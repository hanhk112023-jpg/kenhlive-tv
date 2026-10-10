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
            if (DeviceMode.isTv) {
                addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) {
                        if (this@ScheduleFragment.view?.findFocus() == null && isResumed) {
                            if (view.isFocusable) {
                                view.requestFocus()
                            } else if (view is ViewGroup) {
                                for (i in 0 until view.childCount) {
                                    val c = view.getChildAt(i)
                                    if (c.isFocusable && c.requestFocus()) break
                                }
                            }
                        }
                    }
                    override fun onChildViewDetachedFromWindow(view: View) {}
                })
            }
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
            focusFirstMatch()
            view.postDelayed({ if (view.findFocus() == null) focusFirstMatch() }, 120)
            view.postDelayed({ if (view.findFocus() == null) focusFirstMatch() }, 350)
        }
    }

    override fun onResume() {
        super.onResume()
        vm.startAutoRefresh()
        if (DeviceMode.isTv) {
            focusFirstMatch()
            view?.postDelayed({ if (view?.findFocus() == null) focusFirstMatch() }, 100)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && DeviceMode.isTv) {
            focusFirstMatch()
            view?.postDelayed({ if (view?.findFocus() == null) focusFirstMatch() }, 150)
        }
    }

    fun focusFirstMatch(): Boolean {
        val rv = view?.findViewById<RecyclerView>(R.id.schedList) ?: return false
        val curFocus = view?.findFocus()
        if (curFocus != null && curFocus !== view) return true

        // 1. Thử trực tiếp các child view đang hiển thị trong RecyclerView
        for (ci in 0 until rv.childCount) {
            val child = rv.getChildAt(ci)
            if (child.isFocusable && child.requestFocus()) return true
            if (child is ViewGroup) {
                for (j in 0 until child.childCount) {
                    val sub = child.getChildAt(j)
                    if (sub.isFocusable && sub.requestFocus()) return true
                }
            }
        }

        // 2. Tìm vị trí match đầu tiên trong adapter
        var targetPos = -1
        for (i in 0 until adapter.itemCount) {
            if (adapter.getItemViewType(i) == 1) { // TYPE_MATCH
                targetPos = i
                break
            }
        }
        if (targetPos < 0) return false

        // 3. Thử qua LayoutManager hoặc ViewHolder
        val lmView = rv.layoutManager?.findViewByPosition(targetPos)
        if (lmView != null && (lmView.requestFocus() || lmView.findFocus() != null)) {
            return true
        }
        val vh = rv.findViewHolderForAdapterPosition(targetPos)
        if (vh != null && (vh.itemView.requestFocus() || vh.itemView.findFocus() != null)) {
            return true
        }

        // 4. Cuộn tới vị trí và kích hoạt retry loop
        rv.scrollToPosition(targetPos)
        var retries = 8
        val retryRunnable = object : Runnable {
            override fun run() {
                if (view?.findFocus() != null) return
                for (ci in 0 until rv.childCount) {
                    val c = rv.getChildAt(ci)
                    if (c.isFocusable && c.requestFocus()) return
                }
                val v = rv.layoutManager?.findViewByPosition(targetPos)
                    ?: rv.findViewHolderForAdapterPosition(targetPos)?.itemView
                if (v != null && v.requestFocus()) return
                if (--retries > 0 && isResumed) {
                    rv.postDelayed(this, 50)
                }
            }
        }
        rv.postDelayed(retryRunnable, 50)
        return false
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
