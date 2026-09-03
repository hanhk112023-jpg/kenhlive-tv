package com.kenhlive.tv

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private lateinit var retryBtn: TextView
    private lateinit var recyclerView: RecyclerView
    private var groups = listOf<LiveMatchGroup>()
    private var rooms = listOf<LiveRoom>()
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshing = false
    private val autoRefresh = object : Runnable {
        override fun run() {
            silentRefresh()
            refreshHandler.postDelayed(this, 3 * 60_000L) // 3 phút — playlist GH cũng cập nhật 5'
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_live, container, false)
        statusText = v.findViewById(R.id.statusText)
        retryBtn = v.findViewById(R.id.retryBtn)
        recyclerView = v.findViewById(R.id.recyclerView)
        retryBtn.setOnClickListener { load() }
        load()
        return v
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.removeCallbacks(autoRefresh)
        refreshHandler.postDelayed(autoRefresh, 3 * 60_000L)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(autoRefresh)
        refreshHandler.removeCallbacks(autoRetry)
    }

    /** Fetch lại âm thầm: giữ nguyên vị trí cuộn, chỉ cập nhật dữ liệu. */
    private fun silentRefresh() {
        if (refreshing || !isAdded || !::adapter.isInitialized) return
        refreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val newRooms = SocoliveRepository.fetchLiveRooms()
                val newGroups = SocoliveRepository.groupRooms(newRooms)
                if (newGroups.isNotEmpty() && isAdded) {
                    rooms = newRooms; groups = newGroups
                    adapter.submit(newGroups)
                }
            } catch (e: Exception) { /* giữ dữ liệu cũ */ }
            finally { refreshing = false }
        }
    }

    private fun load() {
        statusText.visibility = View.VISIBLE
        statusText.text = "Đang tải..."
        retryBtn.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                rooms = SocoliveRepository.fetchLiveRooms()
                groups = SocoliveRepository.groupRooms(rooms)
                if (groups.isEmpty()) {
                    statusText.text = "Hiện không có trận nào đang live"
                    return@launch
                }
                statusText.visibility = View.GONE
                retryBtn.visibility = View.GONE
                adapter = HomeAdapter(
                    groups = groups,
                    lifecycleOwner = viewLifecycleOwner,
                    onGroupClick = { g -> openGroupPicker(g) },
                    onLongClickGroup = { g -> toggleMultiGroup(g) }
                )
                recyclerView.layoutManager = LinearLayoutManager(requireContext())
                recyclerView.adapter = adapter
            } catch (e: Exception) {
                statusText.text = "Không tải được danh sách trận\n(kiểm tra kết nối mạng)"
                statusText.visibility = View.VISIBLE
                retryBtn.visibility = View.VISIBLE
                // tự thử lại 1 lần sau 15s (mạng chập chờn hay hồi nhanh)
                refreshHandler.postDelayed(autoRetry, 15_000)
            }
        }
    }

    private val autoRetry = Runnable {
        if (isAdded && retryBtn.visibility == View.VISIBLE) load()
    }

    // Mở dialog chọn phòng trong trận (nếu 1 phòng thì play luôn)
    private fun openGroupPicker(g: LiveMatchGroup) {
        if (g.count == 1) { openRoom(g.top, g.rooms); return }
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
            opt.setOnClickListener { openRoom(r, g.rooms); dialog?.dismiss() }
            list.addView(opt)
        }
        dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .setOnDismissListener { dialog = null }
            .create()
        dialog?.setOnCancelListener { dialog = null }
        dialog?.show()
    }

    private fun openRoom(room: LiveRoom, groupRooms: List<LiveRoom> = emptyList()) {
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

    /** Nhấn GIỮ card = mở multiview 2 trận, ô đầu là trận này. */
    private fun toggleMultiGroup(g: LiveMatchGroup) {
        startActivity(Intent(requireContext(), MultiViewActivity::class.java)
            .putExtra("initial_room", "${g.top.matchTitle} · ${g.top.blvName}"))
    }

    fun openMultiView() {
        startActivity(Intent(requireContext(), MultiViewActivity::class.java))
    }

    private var dialog: AlertDialog? = null
}
