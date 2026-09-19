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
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
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

        // TV 4 cột rộng rãi 16:9, Mobile 2 cột
        val spanCount = if (DeviceMode.isTv) 4 else 2
        gridList.layoutManager = GridLayoutManager(requireContext(), spanCount)
        groupList.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentKeyword = s?.toString()?.trim() ?: ""
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadData()
    }

    private fun loadData() {
        loadingView.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        lifecycleScope.launch {
            allChannels = IptvRepository.loadChannels(requireContext())
            loadingView.visibility = View.GONE

            if (allChannels.isEmpty()) {
                emptyText.visibility = View.VISIBLE
                countText.text = "0 kênh"
                setupGroups(emptyList())
                setupGrid(emptyList())
            } else {
                val vnCount = allChannels.count { it.isVn }
                val sportsCount = allChannels.size - vnCount
                countText.text = "Tổng: ${allChannels.size} kênh (${vnCount} VN, ${sportsCount} DAZN & Sky Sports)"

                val distinctGroups = mutableListOf("Việt Nam", "DAZN", "Sky Sports", "Tất cả")
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
        } else if (selectedGroup == "DAZN") {
            list = list.filter { it.group.equals("DAZN", ignoreCase = true) }
        } else if (selectedGroup == "Sky Sports") {
            list = list.filter { it.group.equals("Sky Sports", ignoreCase = true) }
        } else if (selectedGroup != "Tất cả") {
            list = list.filter { it.group.equals(selectedGroup, ignoreCase = true) }
        }

        if (currentKeyword.isNotEmpty()) {
            list = list.filter {
                it.name.contains(currentKeyword, ignoreCase = true) ||
                it.group.contains(currentKeyword, ignoreCase = true) ||
                it.country.contains(currentKeyword, ignoreCase = true)
            }
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
                holder.tvSub.text = if (ch.isVn) "Truyền hình Việt Nam" else "${ch.group} · Thể thao quốc tế"
                holder.badge.text = if (ch.isVn) "VIỆT NAM" else ch.group.uppercase()

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
