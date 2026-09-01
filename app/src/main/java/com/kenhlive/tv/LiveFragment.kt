package com.kenhlive.tv

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class LiveFragment : Fragment() {
    private lateinit var adapter: LiveRoomAdapter
    private lateinit var statusText: TextView
    private var rooms = listOf<LiveRoom>()
    private val multiSel = mutableListOf<LiveRoom>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_live, container, false)
        statusText = v.findViewById(R.id.statusText)
        val isTv = DeviceMode.usePhoneLayout(requireActivity()).not()
        adapter = LiveRoomAdapter(
            onClick = { room -> openRoom(room) },
            onLongClick = { room -> toggleMulti(room) }
        )
        v.findViewById<RecyclerView>(R.id.recyclerView).apply {
            layoutManager = GridLayoutManager(requireContext(), if (isTv) 4 else 2)
            adapter = this@LiveFragment.adapter
        }
        load()
        return v
    }

    private fun load() {
        statusText.visibility = View.VISIBLE
        statusText.text = "Đang tải phòng live..."
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                rooms = SocoliveRepository.fetchLiveRooms()
                if (rooms.isEmpty()) {
                    statusText.text = "Hiện không có phòng nào đang live"
                    return@launch
                }
                statusText.visibility = View.GONE
                adapter.submitList(rooms)
            } catch (e: Exception) {
                statusText.text = "Lỗi tải: ${e.message}"
            }
        }
    }

    private fun openRoom(room: LiveRoom) {
        viewLifecycleOwner.lifecycleScope.launch {
            val url = SocoliveRepository.fetchStream(room.roomNum)
            if (url == null) {
                Toast.makeText(context, "Stream chưa sẵn sàng — thử lại", Toast.LENGTH_SHORT).show()
                return@launch
            }
            startActivity(Intent(requireContext(), PlayerActivity::class.java)
                .putExtra("url", url)
                .putExtra("name", "${room.matchTitle} · ${room.blvName}"))
        }
    }

    /** Nhấn giữ card để thêm/bớt phòng khỏi multiview (2-4 phòng). */
    private fun toggleMulti(room: LiveRoom) {
        val existing = multiSel.indexOfFirst { it.roomNum == room.roomNum }
        if (existing >= 0) multiSel.removeAt(existing)
        else if (multiSel.size >= 4) {
            Toast.makeText(context, "Tối đa 4 phòng", Toast.LENGTH_SHORT).show()
            return
        } else multiSel.add(room)

        adapter.highlightRoomNums = multiSel.map { it.roomNum }.toSet()
        adapter.notifyDataSetChanged()

        val n = multiSel.size
        Toast.makeText(context,
            if (existing >= 0) "Bỏ ${room.blvName} (${n}/4)"
            else "Đã chọn ${room.blvName} (${n}/4) — chọn nút Multi-view để xem",
            Toast.LENGTH_SHORT).show()
    }

    fun openMultiView() {
        if (multiSel.size < 2) {
            Toast.makeText(context, "Nhấn GIỮ card để chọn 2-4 phòng trước", Toast.LENGTH_LONG).show()
            return
        }
        val nums = multiSel.map { it.roomNum }.toTypedArray()
        val names = multiSel.map { "${it.matchTitle} · ${it.blvName}" }.toTypedArray()
        startActivity(Intent(requireContext(), MultiViewActivity::class.java)
            .putExtra("roomNums", nums)
            .putExtra("names", names))
    }
}
