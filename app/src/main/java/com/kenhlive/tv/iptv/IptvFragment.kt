package com.kenhlive.tv.iptv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.kenhlive.tv.DeviceMode
import com.kenhlive.tv.PlayerActivity
import com.kenhlive.tv.R
import kotlinx.coroutines.launch

class IptvFragment : Fragment() {

    private lateinit var groupList: RecyclerView
    private lateinit var gridList: RecyclerView
    private lateinit var loadingView: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var countText: TextView
    private lateinit var btnRefresh: Button
    private lateinit var btnManage: Button

    private var allChannels: List<IptvChannel> = emptyList()
    private var displayedChannels: List<IptvChannel> = emptyList()
    private var selectedGroup: String = "Tất cả"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_iptv, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        groupList = view.findViewById(R.id.iptvGroupList)
        gridList = view.findViewById(R.id.iptvGrid)
        loadingView = view.findViewById(R.id.iptvLoading)
        emptyText = view.findViewById(R.id.iptvEmptyText)
        countText = view.findViewById(R.id.channelCountText)
        btnRefresh = view.findViewById(R.id.btnRefreshIptv)
        btnManage = view.findViewById(R.id.btnManageM3u)

        // Cột hiển thị: TV 5-6 cột, Điện thoại 3 cột
        val spanCount = if (DeviceMode.isTv) 5 else 3
        gridList.layoutManager = GridLayoutManager(requireContext(), spanCount)
        groupList.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)

        btnRefresh.setOnClickListener {
            loadData(forceRefresh = true)
        }

        btnManage.setOnClickListener {
            showManageM3uDialog()
        }

        loadData(forceRefresh = false)
    }

    private fun loadData(forceRefresh: Boolean) {
        loadingView.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        lifecycleScope.launch {
            allChannels = IptvRepository.loadChannels(requireContext(), forceRefresh)
            loadingView.visibility = View.GONE

            if (allChannels.isEmpty()) {
                emptyText.visibility = View.VISIBLE
                countText.text = ""
                setupGroups(emptyList())
                setupGrid(emptyList())
            } else {
                countText.text = "(${allChannels.size} kênh)"
                val groups = listOf("Tất cả") + allChannels.map { it.group }.distinct()
                setupGroups(groups)
                filterByGroup(selectedGroup)
            }
        }
    }

    private fun filterByGroup(group: String) {
        selectedGroup = group
        displayedChannels = if (group == "Tất cả") {
            allChannels
        } else {
            allChannels.filter { it.group.equals(group, ignoreCase = true) }
        }
        setupGrid(displayedChannels)
    }

    private fun setupGroups(groups: List<String>) {
        groupList.adapter = object : RecyclerView.Adapter<GroupViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_iptv_group, parent, false)
                return GroupViewHolder(v)
            }

            override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
                val g = groups[position]
                holder.tv.text = g
                val isSel = g.equals(selectedGroup, ignoreCase = true)
                holder.tv.isSelected = isSel
                if (isSel) {
                    holder.tv.setTextColor(Color.parseColor("#FF00E5FF"))
                    holder.tv.setBackgroundResource(R.drawable.bg_badge_league_cyan)
                } else {
                    holder.tv.setTextColor(Color.parseColor("#FFCBD5E1"))
                    holder.tv.setBackgroundResource(R.drawable.bg_chip)
                }

                holder.itemView.setOnClickListener {
                    filterByGroup(g)
                    notifyDataSetChanged()
                }
            }

            override fun getItemCount(): Int = groups.size
        }
    }

    private fun setupGrid(channels: List<IptvChannel>) {
        gridList.adapter = object : RecyclerView.Adapter<ChannelViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_iptv_channel, parent, false)
                return ChannelViewHolder(v)
            }

            override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
                val ch = channels[position]
                holder.tvName.text = ch.name
                holder.tvGroup.text = ch.group

                if (ch.logo.isNotEmpty()) {
                    holder.ivLogo.load(ch.logo) {
                        crossfade(true)
                        error(R.drawable.ic_nav_tv)
                        placeholder(R.drawable.ic_nav_tv)
                    }
                } else {
                    holder.ivLogo.setImageResource(R.drawable.ic_nav_tv)
                }

                holder.itemView.setOnClickListener {
                    openChannel(ch)
                }
            }

            override fun getItemCount(): Int = channels.size
        }
    }

    private fun openChannel(ch: IptvChannel) {
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra("url", ch.url)
            putExtra("name", ch.name)
            putExtra("pip", false)
        }
        startActivity(intent)
    }

    private fun showManageM3uDialog() {
        val ctx = requireContext()
        val curUrl = IptvRepository.getM3uUrl(ctx)

        val input = EditText(ctx).apply {
            setText(curUrl)
            setHint("Nhập link playlist m3u / m3u8...")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(40, 30, 40, 30)
            setBackgroundColor(Color.parseColor("#1C1D26"))
        }

        AlertDialog.Builder(ctx)
            .setTitle("Cấu hình Playlist M3U / M3U8")
            .setMessage("Hỗ trợ định dạng .m3u, .m3u8 trực tuyến. Hệ thống sẽ tự động cập nhật danh sách kênh theo chu kỳ.")
            .setView(input)
            .setPositiveButton("LƯU & TẢI KÊNH") { _, _ ->
                val newUrl = input.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    IptvRepository.saveM3uUrl(ctx, newUrl)
                    Toast.makeText(ctx, "Đã lưu! Đang tải danh mục kênh mới...", Toast.LENGTH_SHORT).show()
                    loadData(forceRefresh = true)
                }
            }
            .setNeutralButton("MẶC ĐỊNH") { _, _ ->
                IptvRepository.saveM3uUrl(ctx, IptvRepository.DEFAULT_M3U_URL)
                loadData(forceRefresh = true)
            }
            .setNegativeButton("HỦY", null)
            .show()
    }

    private class GroupViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(R.id.groupTitle)
    }

    private class ChannelViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val ivLogo: ImageView = v.findViewById(R.id.channelLogo)
        val tvName: TextView = v.findViewById(R.id.channelName)
        val tvGroup: TextView = v.findViewById(R.id.channelGroup)
    }
}
