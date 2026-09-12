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
import kotlinx.coroutines.launch

/**
 * Màn TRỰC TIẾP: hero carousel + row theo giải.
 * State trong ViewModel → rotation/đổi tab không fetch lại, không mất focus.
 */
class LiveFragment : Fragment() {

    private val vm: LiveViewModel by viewModels()
    private lateinit var adapter: HomeRowsAdapter
    private lateinit var list: RecyclerView
    private lateinit var state: StateBinder
    private var dialog: AlertDialog? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_live, container, false)
        list = v.findViewById(R.id.liveList)
        state = StateBinder(v)

        adapter = HomeRowsAdapter(
            onGroupClick = { g -> openGroupPicker(g) },
            onLongClickGroup = { g -> openMultiView(g) }
        )
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        if (DeviceMode.lowRam) {
            list.itemAnimator = null
            list.setHasFixedSize(true)
        }
        list.clipChildren = false
        list.clipToPadding = false

        viewLifecycleOwner.lifecycleScope.launch {
            vm.state.collect { st ->
                state.render(st, R.string.live_loading) { vm.load(force = true) }
                if (st is UiState.Success) adapter.submitList(HomeRowsAdapter.buildItems(st.data))
            }
        }
        vm.load()
        return v
    }

    override fun onResume() {
        super.onResume()
        vm.startAutoRefresh()
        if (::adapter.isInitialized) adapter.restoreFocus()
    }

    override fun onPause() {
        super.onPause()
        vm.stopAutoRefresh()
    }

    /** QA hook (`--es open refresh`): ép silent refresh chạy ngay. */
    fun debugForceRefresh() { if (isAdded) vm.silentRefresh() }

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

    private fun openRoom(room: LiveRoom) {
        viewLifecycleOwner.lifecycleScope.launch {
            val url = SocoliveRepository.fetchStream(room.roomNum)
            if (url == null) {
                toast(R.string.stream_not_ready)
                return@launch
            }
            startActivity(
                Intent(requireContext(), PlayerActivity::class.java)
                    .putExtra("url", url)
                    .putExtra("name", "${room.matchTitle} · ${room.blvName}")
            )
        }
    }

    private fun toast(res: Int) {
        android.widget.Toast.makeText(requireContext(), res, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        dialog?.dismiss()
        dialog = null
        super.onDestroyView()
    }
}
