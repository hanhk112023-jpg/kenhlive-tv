package com.kenhlive.tv.iptv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
    private lateinit var searchInput: EditText
    private lateinit var btnRefresh: Button
    private lateinit var btnManage: Button

    private var allChannels: List<IptvChannel> = emptyList()
    private var displayedChannels: List<IptvChannel> = emptyList()
    private var selectedGroup: String = "Việt Nam"
    private var currentKeyword: String = ""

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
        searchInput = view.findViewById(R.id.searchChannelInput)
        btnRefresh = view.findViewById(R.id.btnRefreshIptv)
        btnManage = view.findViewById(R.id.btnManageM3u)

        // TV 4-5 cột rộng rãi chuẩn 16:9, Mobile 2-3 cột
        val spanCount = if (DeviceMode.isTv) 4 else 2
        gridList.layoutManager = GridLayoutManager(requireContext(), spanCount)
        groupList.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)

        btnRefresh.setOnClickListener {
            loadData(forceRefresh = true)
        }

        btnManage.setOnClickListener {
            showManageM3uDialog()
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentKeyword = s?.toString()?.trim() ?: ""
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

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
                countText.text = "0 kênh"
                setupGroups(emptyList())
                setupGrid(emptyList())
            } else {
                val vnCount = allChannels.count { it.isVn }
                val sportsCount = allChannels.size - vnCount
                countText.text = "Tổng: ${allChannels.size} kênh (${vnCount} kênh VN, ${sportsCount} kênh Thể thao)"

                val distinctGroups = mutableListOf("Việt Nam", "Thể thao", "Tất cả")
                val otherGroups = allChannels.map { it.group }.distinct().filterNot { it in distinctGroups }
                distinctGroups.addAll(otherGroups)

                setupGroups(distinctGroups)
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        var list = allChannels
        if (selectedGroup == "Việt Nam") {
            list = list.filter { it.isVn || it.group.contains("Việt Nam", ignoreCase = true) }
        } else if (selectedGroup == "Thể thao") {
            list = list.filter { !it.isVn && (it.group.contains("Thể thao", ignoreCase = true) || it.group.contains("Sport", ignoreCase = true)) }
        } else if (selectedGroup != "Tất cả") {
            list = list.filter { it.group.equals(selectedGroup, ignoreCase = true) }
        }

        if (currentKeyword.isNotEmpty()) {
            list = list.filter { it.name.contains(currentKeyword, ignoreCase = true) || it.group.contains(currentKeyword, ignoreCase = true) }
        }

        displayedChannels = list
        emptyText.visibility = if (displayedChannels.isEmpty()) View.VISIBLE else View.GONE
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
                    selectedGroup = g
                    applyFilter()
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
                holder.tvSub.text = if (ch.isVn) "Truyền hình Việt Nam" else ch.group
                holder.badge.text = if (ch.isVn) "VIỆT NAM" else "THỂ THAO"

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
        val curUrl = IptvRepository.getCustomUrl(ctx)

        val input = EditText(ctx).apply {
            setText(curUrl)
            setHint("Dán link M3U / M3U8 cá nhân...")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(40, 30, 40, 30)
            setBackgroundColor(Color.parseColor("#1C1D26"))
        }

        AlertDialog.Builder(ctx)
            .setTitle("Thêm Playlist M3U / M3U8 Riêng")
            .setMessage("Mặc định ứng dụng đã tự động tải 82 kênh Việt Nam và 430 kênh Thể thao quốc tế. Bạn có thể nhập thêm link cá nhân tại đây:")
            .setView(input)
            .setPositiveButton("LƯU & NẠP KÊNH") { _, _ ->
                val newUrl = input.text.toString().trim()
                IptvRepository.saveCustomUrl(ctx, newUrl)
                Toast.makeText(ctx, "Đang tải dữ liệu kênh...", Toast.LENGTH_SHORT).show()
                loadData(forceRefresh = true)
            }
            .setNeutralButton("XÓA LINK RIÊNG") { _, _ ->
                IptvRepository.saveCustomUrl(ctx, "")
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
        val badge: TextView = v.findViewById(R.id.channelBadge)
        val tvName: TextView = v.findViewById(R.id.channelName)
        val tvSub: TextView = v.findViewById(R.id.channelSub)
    }
}
