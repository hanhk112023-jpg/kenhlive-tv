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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/** Trang chủ Netflix-style: hero banner + rows cuộn ngang theo giải. */
class LiveFragment : Fragment() {
    private lateinit var adapter: HomeAdapter
    private lateinit var statusText: TextView
    private lateinit var recyclerView: RecyclerView
    private var rooms = listOf<LiveRoom>()
    private val multiSel = mutableListOf<LiveRoom>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_live, container, false)
        statusText = v.findViewById(R.id.statusText)
        recyclerView = v.findViewById(R.id.recyclerView)
        load()
        return v
    }

    private fun load() {
        statusText.visibility = View.VISIBLE
        statusText.text = "Đang tải..."
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                rooms = SocoliveRepository.fetchLiveRooms()
                if (rooms.isEmpty()) {
                    statusText.text = "Hiện không có phòng nào đang live"
                    return@launch
                }
                statusText.visibility = View.GONE
                adapter = HomeAdapter(
                    rooms = rooms,
                    lifecycleOwner = viewLifecycleOwner,
                    onPlay = { r -> openRoom(r) },
                    onLongClick = { r -> toggleMulti(r) }
                )
                recyclerView.layoutManager = LinearLayoutManager(requireContext())
                recyclerView.adapter = adapter
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

    private fun toggleMulti(room: LiveRoom) {
        val existing = multiSel.indexOfFirst { it.roomNum == room.roomNum }
        if (existing >= 0) multiSel.removeAt(existing)
        else if (multiSel.size >= 4) {
            Toast.makeText(context, "Tối đa 4 phòng", Toast.LENGTH_SHORT).show()
            return
        } else multiSel.add(room)
        Toast.makeText(context,
            if (existing >= 0) "Bỏ ${room.blvName} (${multiSel.size}/4)"
            else "Đã chọn ${room.blvName} (${multiSel.size}/4)",
            Toast.LENGTH_SHORT).show()
    }

    fun openMultiView() {
        if (multiSel.size < 2) {
            Toast.makeText(context, "Nhấn GIỮ card để chọn 2-4 phòng trước", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(requireContext(), MultiViewActivity::class.java)
            .putExtra("roomNums", multiSel.map { it.roomNum }.toTypedArray())
            .putExtra("names", multiSel.map { "${it.matchTitle} · ${it.blvName}" }.toTypedArray()))
    }
}
