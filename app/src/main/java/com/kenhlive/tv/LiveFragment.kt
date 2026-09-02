package com.kenhlive.tv

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.launch

/** Trang chủ Netflix-style theo TRẬN: hero top 5 trận + rows cuộn ngang theo giải, click trận mở chọn phòng. */
class LiveFragment : Fragment() {
    private lateinit var adapter: HomeAdapter
    private lateinit var statusText: TextView
    private lateinit var recyclerView: RecyclerView
    private var groups = listOf<LiveMatchGroup>()
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
                groups = SocoliveRepository.groupRooms(rooms)
                if (groups.isEmpty()) {
                    statusText.text = "Hiện không có trận nào đang live"
                    return@launch
                }
                statusText.visibility = View.GONE
                adapter = HomeAdapter(
                    groups = groups,
                    lifecycleOwner = viewLifecycleOwner,
                    onGroupClick = { g -> openGroupPicker(g) },
                    onLongClickGroup = { g -> toggleMultiGroup(g) }
                )
                recyclerView.layoutManager = LinearLayoutManager(requireContext())
                recyclerView.adapter = adapter
            } catch (e: Exception) {
                statusText.text = "Lỗi tải: ${e.message}"
            }
        }
    }

    // Mở dialog chọn phòng trong trận (nếu 1 phòng thì play luôn)
    private fun openGroupPicker(g: LiveMatchGroup) {
        if (g.count == 1) { openRoom(g.top); return }
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_room_picker, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = g.matchTitle
        view.findViewById<TextView>(R.id.dialogLeague).text = "${g.league} · ${g.count} phòng live"
        val list = view.findViewById<LinearLayout>(R.id.roomList)
        val inf = LayoutInflater.from(requireContext())
        g.rooms.forEach { r ->
            val opt = inf.inflate(R.layout.item_room_option, list, false)
            opt.findViewById<TextView>(R.id.roomName).text = r.blvName
            opt.findViewById<TextView>(R.id.roomMeta).text = "👁 ${SocoliveRepository.fmtViewers(r.viewers)}"
            opt.findViewById<ImageView>(R.id.roomAvatar).load(r.avatar) {
                crossfade(80); transformations(CircleCropTransformation())
                placeholder(R.drawable.logo_placeholder); error(R.drawable.logo_placeholder)
            }
            opt.setOnClickListener { openRoom(r); dialog?.dismiss() }
            list.addView(opt)
        }
        dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .setOnDismissListener { dialog = null }
            .create()
        dialog?.setOnCancelListener { dialog = null }
        dialog?.show()
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

    private fun toggleMultiGroup(g: LiveMatchGroup) {
        // thêm cả nhóm phòng vào multiview (tối đa 4)
        for (r in g.rooms) {
            if (multiSel.size >= 4) break
            if (multiSel.none { it.roomNum == r.roomNum }) multiSel.add(r)
        }
        Toast.makeText(context, "Multiview: ${multiSel.map { it.blvName }.take(3).joinToString(", ")}${if (multiSel.size > 3) "…" else ""} (${multiSel.size}/4)",
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

    private var dialog: AlertDialog? = null
}
