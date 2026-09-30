package com.kenhlive.tv.iptv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
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
import com.kenhlive.tv.Favorites
import kotlinx.coroutines.launch

class IptvFragment : Fragment() {

    private companion object {
        const val FAV_GROUP = "★ Yêu thích"
    }

    private lateinit var groupList: RecyclerView
    private lateinit var gridList: RecyclerView
    private lateinit var loadingView: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var countText: TextView
    private lateinit var searchInput: EditText

    // Hero Preview Views (Apple TV / Netflix style)
    private var heroPreviewBox: View? = null
    private var heroChannelTitle: TextView? = null
    private var heroChannelGroup: TextView? = null
    private var heroEpgNow: TextView? = null
    private var heroEpgNext: TextView? = null
    private var heroLogoImg: ImageView? = null

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

        heroPreviewBox = view.findViewById(R.id.heroPreviewBox)
        heroChannelTitle = view.findViewById(R.id.heroChannelTitle)
        heroChannelGroup = view.findViewById(R.id.heroChannelGroup)
        heroEpgNow = view.findViewById(R.id.heroEpgNow)
        heroEpgNext = view.findViewById(R.id.heroEpgNext)
        heroLogoImg = view.findViewById(R.id.heroLogoImg)

        // TV: 3 cột ngang dạng card bo góc thanh thoát, Phone: 1 hoặc 2 cột
        val spanCount = if (DeviceMode.isTv) 3 else 1
        gridList.layoutManager = GridLayoutManager(requireContext(), spanCount)
        gridList.clipChildren = false
        gridList.clipToPadding = false

        groupList.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        groupList.clipChildren = false
        groupList.clipToPadding = false

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentKeyword = s?.toString()?.trim() ?: ""
                applyFilter(focusFirst = false)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadData()
    }

    private fun updateHeroPreview(ch: IptvChannel) {
        heroChannelTitle?.text = ch.name
        heroChannelGroup?.text = if (ch.isVn) "VIỆT NAM" else ch.group.uppercase()

        if (ch.logo.isNotEmpty()) {
            heroLogoImg?.load(ch.logo) {
                crossfade(true)
                error(R.drawable.ic_nav_tv)
                placeholder(R.drawable.ic_nav_tv)
            }
        } else {
            heroLogoImg?.setImageResource(R.drawable.ic_nav_tv)
        }

        val epg = EpgRepository.getCurrentAndNext(ch.id, ch.name)
        if (epg != null && epg.first != null) {
            val cur = epg.first!!
            heroEpgNow?.text = "▶ Đang phát: [${cur.timeRange()}] ${cur.title}"
            if (epg.second != null) {
                val nxt = epg.second!!
                heroEpgNext?.visibility = View.VISIBLE
                heroEpgNext?.text = "⏭ Tiếp theo: [${nxt.startFormatted()}] ${nxt.title}"
            } else {
                heroEpgNext?.visibility = View.GONE
            }
        } else {
            heroEpgNow?.text = if (ch.isVn) "▶ Đang phát trực tiếp từ Đài Truyền hình Việt Nam" else "▶ Đang phát trực tiếp từ ${ch.group}"
            heroEpgNext?.visibility = View.GONE
        }
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
                countText.text = "Tổng: ${allChannels.size} kênh (${vnCount} VN, ${sportsCount} Thể thao)"

                setupGroups(buildGroups())
                applyFilter(focusFirst = true)

                EpgRepository.initEpg(requireContext())
                if (isAdded) {
                    gridList.adapter?.notifyDataSetChanged()
                    if (displayedChannels.isNotEmpty()) {
                        updateHeroPreview(displayedChannels[0])
                    }
                }
            }
        }
    }

    /** Danh sách nhóm kênh; v7: "★ Yêu thích" luôn đứng đầu khi đã có kênh yêu thích. */
    private fun buildGroups(): List<String> {
        val distinctGroups = mutableListOf("Việt Nam", "Thể Thao", "DAZN", "Sky Sports", "beIN Sports", "ESPN", "FIFA+", "Tất cả")
        val otherGroups = allChannels.map { it.group }.distinct().filterNot { it in distinctGroups }
        distinctGroups.addAll(otherGroups)
        val groups = distinctGroups.filter { g ->
            g == "Tất cả" || allChannels.any {
                if (g == "Việt Nam") it.isVn || it.group.contains("Việt Nam", ignoreCase = true)
                else if (g == "Thể Thao") !it.isVn
                else it.group.equals(g, ignoreCase = true)
            }
        }
        val favIds = Favorites.iptvIds(requireContext())
        return if (allChannels.any { it.id in favIds }) listOf(FAV_GROUP) + groups else groups
    }

    private fun toggleFavorite(ch: IptvChannel) {
        val now = Favorites.toggleIptv(requireContext(), ch.id)
        android.widget.Toast.makeText(
            requireContext(),
            if (now) R.string.fav_added else R.string.fav_removed,
            android.widget.Toast.LENGTH_SHORT
        ).show()
        val groups = buildGroups()
        if (selectedGroup == FAV_GROUP && FAV_GROUP !in groups) selectedGroup = "Việt Nam"
        setupGroups(groups)
        applyFilter(focusFirst = false)
    }

    private fun applyFilter(focusFirst: Boolean = false) {
        var list = allChannels
        if (selectedGroup == FAV_GROUP) {
            val favIds = Favorites.iptvIds(requireContext())
            list = list.filter { it.id in favIds }
        } else if (selectedGroup == "Việt Nam") {
            list = list.filter { it.isVn || it.group.contains("Việt Nam", ignoreCase = true) }
        } else if (selectedGroup == "Thể Thao") {
            list = list.filter { !it.isVn }
        } else if (selectedGroup == "DAZN") {
            list = list.filter { it.group.equals("DAZN", ignoreCase = true) }
        } else if (selectedGroup == "Sky Sports") {
            list = list.filter { it.group.equals("Sky Sports", ignoreCase = true) }
        } else if (selectedGroup == "beIN Sports") {
            list = list.filter { it.group.equals("beIN Sports", ignoreCase = true) }
        } else if (selectedGroup == "ESPN") {
            list = list.filter { it.group.equals("ESPN", ignoreCase = true) }
        } else if (selectedGroup == "FIFA+") {
            list = list.filter { it.group.equals("FIFA+", ignoreCase = true) }
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

        if (displayedChannels.isNotEmpty()) {
            updateHeroPreview(displayedChannels[0])
        }

        if (focusFirst && displayedChannels.isNotEmpty() && DeviceMode.isTv &&
            (activity as? com.kenhlive.tv.MainActivity)?.focusInTabBar() != true) {
            gridList.post {
                val card0 = gridList.findViewHolderForAdapterPosition(0)?.itemView
                    ?: gridList.layoutManager?.findViewByPosition(0)
                if (card0?.requestFocus() != true) {
                    gridList.postDelayed({
                        val c2 = gridList.findViewHolderForAdapterPosition(0)?.itemView
                            ?: gridList.layoutManager?.findViewByPosition(0)
                        c2?.requestFocus()
                    }, 100)
                }
            }
        }
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
                    applyFilter(focusFirst = true)
                    notifyDataSetChanged()
                }

                holder.itemView.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (displayedChannels.isNotEmpty()) {
                            gridList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                                ?: gridList.scrollToPosition(0)
                            return@setOnKeyListener true
                        }
                    }
                    false
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
                holder.tvSub.text = if (ch.isVn) "Việt Nam" else ch.group
                val fav = Favorites.isIptv(requireContext(), ch.id)
                holder.badge.text = (if (fav) "♥ " else "") + (if (ch.isVn) "VIỆT NAM" else ch.group.uppercase())

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
                    openChannel(ch, position)
                }
                // v7: giữ OK (TV) / nhấn giữ (điện thoại) = thêm/bỏ Yêu thích
                holder.itemView.setOnLongClickListener { toggleFavorite(ch); true }

                holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.04f else 1f)
                        .scaleY(if (hasFocus) 1.04f else 1f)
                        .setDuration(120).start()
                    v.elevation = if (hasFocus) 12f else 0f
                    if (hasFocus) {
                        updateHeroPreview(ch)
                    }
                }
            }

            override fun getItemCount(): Int = channels.size
        }
    }

    private fun openChannel(ch: IptvChannel, index: Int) {
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra("url", ch.url)
            putExtra("name", ch.name)
            putExtra("pip", false)
            putExtra("is_iptv", true)
            putExtra("channel_id", ch.id)
            // index phải theo danh sách đầy đủ mà Player dùng (không phải danh sách đã lọc)
            putExtra("current_index", IptvRepository.currentChannels.indexOfFirst { it.url == ch.url }.coerceAtLeast(0))
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
