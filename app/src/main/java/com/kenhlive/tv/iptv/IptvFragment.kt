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
import java.util.Locale
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.kenhlive.tv.DeviceMode
import com.kenhlive.tv.Enhancer
import com.kenhlive.tv.PlayerActivity
import com.kenhlive.tv.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class IptvFragment : Fragment() {

    private lateinit var groupList: RecyclerView
    private lateinit var gridList: RecyclerView
    private lateinit var loadingView: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var countText: TextView
    private lateinit var searchInput: EditText

    // Hero Preview Views (Apple TV / Netflix style)
    private var heroPreviewBox: View? = null
    private var heroPlayerView: PlayerView? = null
    private var heroPlayer: ExoPlayer? = null
    private var heroChannelTitle: TextView? = null
    private var heroChannelGroup: TextView? = null
    private var heroEpgNow: TextView? = null
    private var heroEpgProgress: ProgressBar? = null
    private var heroEpgNext: TextView? = null
    private var heroLogoImg: ImageView? = null

    private var previewJob: Job? = null
    private var currentPreviewUrl: String? = null
    private var currentlyFocusedChannel: IptvChannel? = null

    private var allChannels: List<IptvChannel> = emptyList()
    private var displayedChannels: List<IptvChannel> = emptyList()
    private var selectedGroup: String = "VTV"
    private var currentKeyword: String = ""
    private var lastFocusedChannelIndex: Int = 0

    private var groupsAdapter: GroupsAdapter? = null
    private var channelsAdapter: ChannelsAdapter? = null

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
        heroPlayerView = view.findViewById(R.id.heroPlayerView)
        heroChannelTitle = view.findViewById(R.id.heroChannelTitle)
        heroChannelGroup = view.findViewById(R.id.heroChannelGroup)
        heroEpgNow = view.findViewById(R.id.heroEpgNow)
        heroEpgProgress = view.findViewById(R.id.heroEpgProgress)
        heroEpgNext = view.findViewById(R.id.heroEpgNext)
        heroLogoImg = view.findViewById(R.id.heroLogoImg)

        heroPreviewBox?.setOnClickListener {
            currentlyFocusedChannel?.let { ch ->
                val idx = displayedChannels.indexOf(ch).coerceAtLeast(0)
                openChannel(ch, idx)
            }
        }

        // Lưới thẻ TV chuẩn Leanback: Tự động phân chia 5 cột (TV 1080p), 4 cột (Tablet ngang) hoặc 2 cột (Phone)
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        val spanCount = when {
            widthDp >= 800 || DeviceMode.isTv -> 5
            widthDp >= 600 -> 4
            widthDp >= 400 -> 3
            else -> 2
        }
        gridList.layoutManager = GridLayoutManager(requireContext(), spanCount)
        gridList.clipChildren = false
        gridList.clipToPadding = false
        gridList.setHasFixedSize(true)
        gridList.setItemViewCacheSize(10)
        channelsAdapter = ChannelsAdapter()
        gridList.adapter = channelsAdapter

        groupList.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        groupList.clipChildren = false
        groupList.clipToPadding = false
        groupList.setHasFixedSize(true)
        groupsAdapter = GroupsAdapter()
        groupList.adapter = groupsAdapter

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
        heroChannelGroup?.text = when {
            ch.name.contains("VTV", ignoreCase = true) -> "ĐÀI TRUYỀN HÌNH VIỆT NAM (VTV)"
            ch.name.contains("HTV", ignoreCase = true) -> "ĐÀI TRUYỀN HÌNH TP.HCM (HTV)"
            ch.name.contains("VTC", ignoreCase = true) -> "TRUYỀN HÌNH KỸ THUẬT SỐ (VTC)"
            ch.isVn -> "VIỆT NAM"
            else -> ch.group.uppercase()
        }

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
            val pct = cur.progressPercent()
            heroEpgProgress?.visibility = View.VISIBLE
            heroEpgProgress?.progress = pct

            if (epg.second != null) {
                val nxt = epg.second!!
                heroEpgNext?.visibility = View.VISIBLE
                heroEpgNext?.text = "⏭ Tiếp theo: [${nxt.startFormatted()}] ${nxt.title}"
            } else {
                heroEpgNext?.visibility = View.GONE
            }
        } else {
            heroEpgNow?.text = if (ch.isVn) "▶ Đang phát trực tiếp từ Đài Truyền hình Quốc gia" else "▶ Đang phát trực tiếp từ ${ch.group}"
            heroEpgProgress?.visibility = View.GONE
            heroEpgNext?.visibility = View.GONE
        }

        currentlyFocusedChannel = ch
        scheduleHeroPreview(ch)
    }

    private fun loadData() {
        val cached = IptvRepository.getCachedChannelsDisk(requireContext())
        if (!cached.isNullOrEmpty()) {
            allChannels = cached
            loadingView.visibility = View.GONE
            emptyText.visibility = View.GONE
            populateUi(cached, focusFirst = true)
        } else {
            loadingView.visibility = View.VISIBLE
            emptyText.visibility = View.GONE
        }

        lifecycleScope.launch {
            val fresh = IptvRepository.loadChannels(requireContext())
            loadingView.visibility = View.GONE

            if (fresh.isEmpty() && allChannels.isEmpty()) {
                emptyText.visibility = View.VISIBLE
                countText.text = "0 kênh"
                setupGroups(emptyList())
                setupGrid(emptyList())
            } else if (fresh.isNotEmpty() && fresh != allChannels) {
                allChannels = fresh
                emptyText.visibility = View.GONE
                populateUi(fresh, focusFirst = allChannels.isEmpty())
            }

            EpgRepository.initEpg(requireContext())
            if (isAdded) {
                gridList.adapter?.notifyDataSetChanged()
                if (displayedChannels.isNotEmpty()) {
                    updateHeroPreview(displayedChannels[0])
                }
            }
        }
    }

    private fun populateUi(channels: List<IptvChannel>, focusFirst: Boolean) {
        val vnCount = channels.count { it.isVn }
        val sportsCount = channels.size - vnCount
        countText.text = "Tổng: ${channels.size} kênh (${vnCount} VN, ${sportsCount} Thể thao)"

        // Phân nhóm EPG: VTV -> TV360 -> HTV / VTC -> VTVcab / SCTV -> Thể Thao -> Giải Trí / Phim -> Tin Tức / Tỉnh -> Tất cả
        val distinctGroups = mutableListOf("VTV", "TV360", "HTV / VTC", "VTVcab / SCTV", "Thể Thao", "Giải Trí & Phim", "Tin Tức / Địa Phương", "Tất cả")
        val sportsBrandGroups = listOf("DAZN", "Sky Sports", "beIN Sports", "ESPN")
        distinctGroups.addAll(sportsBrandGroups)

        setupGroups(distinctGroups)
        applyFilter(focusFirst = focusFirst)
    }

    private fun applyFilter(focusFirst: Boolean = false) {
        var list = allChannels
        when (selectedGroup) {
            "VTV" -> list = list.filter { it.isVn && it.name.contains("VTV", ignoreCase = true) }
            "TV360" -> list = list.filter {
                it.url.contains("tv360", ignoreCase = true) || it.group.contains("TV360", ignoreCase = true) || it.name.contains("360", ignoreCase = true)
            }
            "HTV / VTC" -> list = list.filter { it.isVn && (it.name.contains("HTV", ignoreCase = true) || it.name.contains("VTC", ignoreCase = true) || it.name.contains("THVL", ignoreCase = true) || it.group.contains("HTV", ignoreCase = true)) }
            "VTVcab / SCTV" -> list = list.filter {
                it.name.contains("VTVcab", ignoreCase = true) || it.name.contains("SCTV", ignoreCase = true) || it.group.contains("VTVcab", ignoreCase = true) || it.group.contains("SCTV", ignoreCase = true)
            }
            "Thể Thao" -> list = list.filter {
                !it.isVn || it.name.contains("Sport", ignoreCase = true) || it.name.contains("Thể Thao", ignoreCase = true) || it.name.contains("On Sports", ignoreCase = true) || it.group.contains("Thể Thao", ignoreCase = true)
            }
            "Giải Trí & Phim" -> list = list.filter {
                val n = it.name.lowercase(Locale.ROOT)
                val g = it.group.lowercase(Locale.ROOT)
                n.contains("movie") || n.contains("cinema") || n.contains("phim") || n.contains("drama") ||
                n.contains("hbo") || n.contains("axn") || n.contains("music") || n.contains("nhạc") ||
                n.contains("cartoon") || n.contains("hoạt hình") || n.contains("entertainment") || n.contains("giải trí") ||
                g.contains("giải trí") || g.contains("phim")
            }
            "Tin Tức / Địa Phương" -> list = list.filter {
                it.isVn && !it.name.contains("VTV", ignoreCase = true) && !it.name.contains("HTV", ignoreCase = true) && !it.name.contains("VTC", ignoreCase = true) && !it.group.contains("TV360", ignoreCase = true)
            }
            "DAZN" -> list = list.filter { it.group.equals("DAZN", ignoreCase = true) || it.name.contains("DAZN", ignoreCase = true) }
            "Sky Sports" -> list = list.filter { it.group.equals("Sky Sports", ignoreCase = true) || it.name.contains("Sky", ignoreCase = true) }
            "beIN Sports" -> list = list.filter { it.group.equals("beIN Sports", ignoreCase = true) || it.name.contains("beIN", ignoreCase = true) }
            "ESPN" -> list = list.filter { it.group.equals("ESPN", ignoreCase = true) || it.name.contains("ESPN", ignoreCase = true) }
            "Tất cả" -> { /* giữ nguyên toàn bộ */ }
            else -> list = list.filter { it.group.equals(selectedGroup, ignoreCase = true) }
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

        if (focusFirst && displayedChannels.isNotEmpty() && DeviceMode.isTv) {
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
        groupsAdapter?.groups = groups
        groupsAdapter?.notifyDataSetChanged()
    }

    private fun setupGrid(channels: List<IptvChannel>) {
        channelsAdapter?.channels = channels
        channelsAdapter?.notifyDataSetChanged()
    }

    private inner class GroupsAdapter : RecyclerView.Adapter<GroupViewHolder>() {
        var groups: List<String> = emptyList()

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
                holder.tv.setTextColor(Color.parseColor("#FFFF6500"))
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

    private inner class ChannelsAdapter : RecyclerView.Adapter<ChannelViewHolder>() {
        var channels: List<IptvChannel> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_iptv_channel, parent, false)
            return ChannelViewHolder(v)
        }

        override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
            val ch = channels[position]
            holder.tvName.text = ch.name
            holder.tvSub.text = if (ch.isVn) "Việt Nam" else ch.group
            holder.badge.text = if (ch.isVn) "VIỆT NAM" else ch.group.uppercase()

            if (ch.logo.isNotEmpty()) {
                holder.ivLogo.load(ch.logo) {
                    crossfade(false)
                    error(R.drawable.ic_nav_tv)
                    placeholder(R.drawable.ic_nav_tv)
                }
            } else {
                holder.ivLogo.setImageResource(R.drawable.ic_nav_tv)
            }

            holder.itemView.setOnClickListener {
                openChannel(ch, position)
            }

            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                v.animate().cancel()
                v.animate()
                    .scaleX(if (hasFocus) 1.08f else 1f)
                    .scaleY(if (hasFocus) 1.08f else 1f)
                    .translationZ(if (hasFocus) 16f else 0f)
                    .setDuration(140).start()
                v.elevation = if (hasFocus) 16f else 0f
                if (hasFocus) {
                    lastFocusedChannelIndex = position
                    updateHeroPreview(ch)
                }
            }
        }

        override fun getItemCount(): Int = channels.size
    }

    private fun scheduleHeroPreview(ch: IptvChannel) {
        previewJob?.cancel()
        if (ch.url.isBlank() || DeviceMode.lowRam) {
            stopHeroPreview()
            return
        }
        if (ch.url == currentPreviewUrl && heroPlayer != null) return

        previewJob = lifecycleScope.launch {
            // Chờ 700ms để người dùng dừng lại ở kênh này trước khi khởi tạo stream nền
            delay(700)
            if (!isAdded) return@launch
            startHeroPreview(ch)
        }
    }

    private fun startHeroPreview(ch: IptvChannel) {
        val pv = heroPlayerView ?: return
        val ctx = context ?: return
        currentPreviewUrl = ch.url

        try {
            if (heroPlayer == null) {
                heroPlayer = ExoPlayer.Builder(ctx, Enhancer.buildRenderersFactory(ctx))
                    .setMediaSourceFactory(Enhancer.buildMediaSourceFactory(ctx))
                    .setTrackSelector(Enhancer.buildPreviewTrackSelector(ctx))
                    .setLoadControl(Enhancer.buildLoadControl(ctx))
                    .build().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false
                        )
                        volume = 0f // Tắt tiếng hoàn toàn cho video preview nền
                        playWhenReady = true
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) {
                                if (state == Player.STATE_READY) {
                                    // Hiện video với hiệu ứng mờ mờ mượt mà (alpha 0.72)
                                    pv.animate()?.alpha(0.72f)?.setDuration(350)?.start()
                                }
                            }
                            override fun onPlayerError(error: PlaybackException) {
                                // Nếu stream lỗi hoặc geoblock, ẩn nhẹ nhàng để lộ logo
                                pv.animate()?.alpha(0f)?.setDuration(200)?.start()
                            }
                        })
                    }
                pv.player = heroPlayer
            }

            pv.alpha = 0f
            val playableUrl = if (Tv360Resolver.isTv360(ch.url)) {
                Tv360Resolver.resolve(ch.url) ?: ch.url
            } else {
                ch.url
            }
            heroPlayer?.setMediaItem(Enhancer.buildMediaItem(playableUrl))
            heroPlayer?.prepare()
        } catch (_: Exception) {
            pv.alpha = 0f
        }
    }

    private fun stopHeroPreview() {
        previewJob?.cancel()
        currentPreviewUrl = null
        heroPlayerView?.animate()?.cancel()
        heroPlayerView?.alpha = 0f
        heroPlayer?.stop()
    }

    override fun onPause() {
        super.onPause()
        stopHeroPreview()
    }

    override fun onResume() {
        super.onResume()
        currentlyFocusedChannel?.let { scheduleHeroPreview(it) }
        if (DeviceMode.isTv && displayedChannels.isNotEmpty()) {
            gridList.post {
                if (gridList.findFocus() == null) {
                    val targetPos = if (lastFocusedChannelIndex in displayedChannels.indices) lastFocusedChannelIndex else 0
                    val vh = gridList.findViewHolderForAdapterPosition(targetPos)
                    if (vh?.itemView?.requestFocus() != true) {
                        gridList.scrollToPosition(targetPos)
                        gridList.postDelayed({
                            gridList.findViewHolderForAdapterPosition(targetPos)?.itemView?.requestFocus()
                        }, 60)
                    }
                }
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            stopHeroPreview()
        } else {
            currentlyFocusedChannel?.let { scheduleHeroPreview(it) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        previewJob?.cancel()
        heroPlayer?.release()
        heroPlayer = null
        heroPlayerView = null
    }

    private fun openChannel(ch: IptvChannel, index: Int) {
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra("url", ch.url)
            putExtra("name", ch.name)
            putExtra("pip", false)
            putExtra("is_iptv", true)
            putExtra("channel_id", ch.id)
            putExtra("current_index", index)
        }
        startActivity(intent)
    }

    class GroupViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(R.id.groupTitle)
    }

    class ChannelViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val ivLogo: ImageView = v.findViewById(R.id.channelLogo)
        val badge: TextView = v.findViewById(R.id.channelBadge)
        val tvName: TextView = v.findViewById(R.id.channelName)
        val tvSub: TextView = v.findViewById(R.id.channelSub)
    }
}
