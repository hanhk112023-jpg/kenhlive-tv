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

class MatchesFragment : Fragment() {
    private lateinit var adapter: MatchAdapter
    private lateinit var statusText: TextView
    private lateinit var recyclerView: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_matches, container, false)
        statusText = v.findViewById(R.id.statusText)
        recyclerView = v.findViewById(R.id.recyclerView)
        adapter = MatchAdapter { match -> openMatch(match) }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        return v
    }

    override fun onResume() {
        super.onResume()
        if (adapter.itemCount == 0) load()
    }

    private fun load() {
        statusText.visibility = View.VISIBLE
        statusText.text = "Đang tải lịch thi đấu..."
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val days = MatchRepository.fetchWeek()
                val items = mutableListOf<Any>()
                for (d in days) {
                    if (d.matches.isEmpty()) continue
                    items.add(MatchRepository.dayLabel(d.date))
                    d.matches.sortedWith(
                        compareBy({ it.hasRoom.not() }, { MatchRepository.leagueWeight(it.league) }, { it.matchTime })
                    ).forEach { items.add(it) }
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

    private fun openMatch(match: Match) {
        viewLifecycleOwner.lifecycleScope.launch {
            val url = MatchRepository.fetchStream(match.roomNum)
            if (url == null) {
                Toast.makeText(context, "Chưa có stream cho trận này", Toast.LENGTH_SHORT).show()
                return@launch
            }
            startActivity(Intent(requireContext(), PlayerActivity::class.java)
                .putExtra("url", url)
                .putExtra("name", "${match.title} (${match.league})"))
        }
    }
}
