package com.kenhlive.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class ScheduleFragment : Fragment() {
    private lateinit var adapter: ScheduleAdapter
    private lateinit var statusText: TextView
    private lateinit var emptyTitle: TextView
    private lateinit var emptyState: View
    private lateinit var retryBtn: TextView
    private var loadedOnce = false
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshing = false
    private val autoRefresh = object : Runnable {
        override fun run() {
            silentRefresh()
            refreshHandler.postDelayed(this, 10 * 60_000L) // lịch đổi chậm — 10 phút
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.removeCallbacks(autoRefresh)
        refreshHandler.postDelayed(autoRefresh, 10 * 60_000L)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(autoRefresh)
    }

    /** Fetch lại lịch âm thầm, không hiện "Đang tải" — giữ nguyên vị trí cuộn. */
    private fun silentRefresh() {
        if (refreshing || !isAdded || !::adapter.isInitialized || !loadedOnce) return
        refreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val days = SocoliveRepository.fetchSchedule(7)
                val items = mutableListOf<Any>()
                for (d in days) {
                    if (d.matches.isEmpty()) continue
                    items.add(SocoliveRepository.dayLabel(d.date))
                    items.addAll(d.matches)
                }
                if (items.isNotEmpty() && isAdded) {
                    emptyState.visibility = View.GONE
                    statusText.visibility = View.GONE
                    adapter.submitList(items)
                }
            } catch (e: Exception) { /* giữ dữ liệu cũ */ }
            finally { refreshing = false }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_matches, container, false)
        statusText = v.findViewById(R.id.statusText)
        emptyTitle = v.findViewById(R.id.emptyTitle)
        emptyState = v.findViewById(R.id.emptyState)
        retryBtn = v.findViewById(R.id.retryBtn)
        retryBtn.setOnClickListener { load() }
        adapter = ScheduleAdapter { anchor, match -> openAnchor(anchor, match) }
        v.findViewById<RecyclerView>(R.id.recyclerView).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ScheduleFragment.adapter
        }
        load()
        return v
    }

    private fun load() {
        emptyState.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        retryBtn.visibility = View.GONE
        statusText.text = "Đang tải lịch thi đấu..."
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val days = SocoliveRepository.fetchSchedule(7)
                val items = mutableListOf<Any>()
                for (d in days) {
                    if (d.matches.isEmpty()) continue
                    items.add(SocoliveRepository.dayLabel(d.date))
                    items.addAll(d.matches)
                }
                if (items.isEmpty()) {
                    emptyTitle.text = "Sân vắng bóng"
                    statusText.text = "Không có trận nào 7 ngày tới"
                    retryBtn.visibility = View.GONE
                    emptyState.visibility = View.VISIBLE
                    return@launch
                }
                emptyState.visibility = View.GONE
                statusText.visibility = View.GONE
                adapter.submitList(items)
                loadedOnce = true
            } catch (e: Exception) {
                emptyState.visibility = View.VISIBLE
                emptyTitle.text = "Không tải được lịch"
                statusText.text = "Kiểm tra kết nối mạng rồi thử lại"
                retryBtn.visibility = View.VISIBLE
            }
        }
    }

    private fun openAnchor(anchor: AnchorInfo, match: ScheduleMatch) {
        viewLifecycleOwner.lifecycleScope.launch {
            val url = SocoliveRepository.fetchStream(anchor.roomNum)
            if (url == null) {
                Toast.makeText(context, "Stream chưa sẵn sàng — thử lại", Toast.LENGTH_SHORT).show()
                return@launch
            }
            startActivity(Intent(requireContext(), PlayerActivity::class.java)
                .putExtra("url", url)
                .putExtra("name", "${match.host} vs ${match.guest} · ${anchor.nickName}"))
        }
    }
}
