package com.kenhlive.tv

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChannelsFragment : Fragment() {
    private var channels = mutableListOf<Channel>()
    private var currentGroup = "Tất cả"
    private lateinit var adapter: ChannelAdapter
    private lateinit var chipRow: LinearLayout
    private lateinit var statusText: TextView
    private val chipViews = mutableMapOf<String, TextView>()
    private val isTvMode by lazy { DeviceMode.usePhoneLayout(requireActivity()).not() }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_channels, container, false)
        chipRow = v.findViewById(R.id.chipRow)
        statusText = v.findViewById(R.id.statusText)
        adapter = ChannelAdapter(gridMode = !isTvMode) { channel -> openChannel(channel) }
        v.findViewById<RecyclerView>(R.id.recyclerView).apply {
            layoutManager = if (isTvMode)
                LinearLayoutManager(requireContext())
            else
                GridLayoutManager(requireContext(), 2)
            adapter = this@ChannelsFragment.adapter
        }
        load()
        return v
    }

    private fun load() {
        statusText.visibility = View.VISIBLE
        statusText.text = "Đang tải kênh từ Socolive..."
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = SocoliveRepository.fetchRooms()
                if (result.isEmpty()) {
                    statusText.text = "Không có kênh nào đang live"
                    return@launch
                }
                channels = result.toMutableList()
                buildChips()
                applyFilter(currentGroup)
                statusText.visibility = View.GONE
            } catch (e: Exception) {
                statusText.text = "Lỗi tải: ${e.message} — thử lại sau"
                Toast.makeText(context, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildChips() {
        chipRow.removeAllViews()
        chipViews.clear()
        val groups = listOf("Tất cả") + channels.map { it.group }.distinct().sorted()
        groups.forEach { g ->
            val tv = TextView(requireContext()).apply {
                text = if (g == "Tất cả") "Tất cả (${channels.size})"
                       else "$g (${channels.count { it.group == g }})"
                textSize = 14f
                setPadding(34, 16, 34, 16)
                setTextColor(Color.WHITE)
                background = requireContext().getDrawable(R.drawable.chip_bg)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 10.dp() }
                isFocusable = true
                setOnClickListener { applyFilter(g) }
            }
            chipRow.addView(tv)
            chipViews[g] = tv
        }
        selectChip(currentGroup)
    }

    private fun selectChip(g: String) {
        chipViews.forEach { (name, tv) ->
            tv.isSelected = name == g
            tv.setTextColor(if (name == g) Color.parseColor("#4CC9F0") else Color.WHITE)
        }
    }

    private fun applyFilter(group: String) {
        currentGroup = group
        selectChip(group)
        val list = if (group == "Tất cả") channels
                   else channels.filter { it.group == group }
        adapter.submitList(list)
    }

    private fun openChannel(channel: Channel) {
        viewLifecycleOwner.lifecycleScope.launch {
            val url = if (channel.url.isNotBlank()) channel.url
                      else SocoliveRepository.fetchStream(channel.roomNum)
            if (url == null) {
                Toast.makeText(context, "Stream chưa sẵn sàng — thử lại", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val label = if (channel.anchor.isNotBlank()) "${channel.name} · BLV ${channel.anchor}"
                        else channel.name
            startActivity(Intent(requireContext(), PlayerActivity::class.java)
                .putExtra("url", url)
                .putExtra("name", label))
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
