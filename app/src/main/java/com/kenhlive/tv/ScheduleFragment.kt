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

class ScheduleFragment : Fragment() {
    private lateinit var adapter: ScheduleAdapter
    private lateinit var statusText: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_matches, container, false)
        statusText = v.findViewById(R.id.statusText)
        adapter = ScheduleAdapter { anchor, match -> openAnchor(anchor, match) }
        v.findViewById<RecyclerView>(R.id.recyclerView).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ScheduleFragment.adapter
        }
        load()
        return v
    }

    private fun load() {
        statusText.visibility = View.VISIBLE
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
                    statusText.text = "Không có trận nào 7 ngày tới"
                    return@launch
                }
                statusText.visibility = View.GONE
                adapter.submitList(items)
            } catch (e: Exception) {
                statusText.text = "Lỗi tải lịch: ${e.message}"
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
