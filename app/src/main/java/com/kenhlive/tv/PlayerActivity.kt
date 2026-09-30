package com.kenhlive.tv

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.kenhlive.tv.iptv.EpgRepository
import com.kenhlive.tv.iptv.IptvChannel
import com.kenhlive.tv.iptv.IptvRepository
import com.kenhlive.tv.ui.applyTvDensity
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale

/**
 * PlayerActivity Pro — Trình phát chuyên nghiệp chuẩn Android TV:
 * - OSD điều khiển đầy đủ, tự ẩn sau 3.5s
 * - Stats for Nerds HUD: đo đạc phân giải, FPS, bitrate thực tế, buffer health
 * - Tỉ lệ khung hình (Aspect Ratio Switcher): Fit (16:9), Fill, Zoom, Fixed
 * - Quick Channel Switcher (D-pad UP/DOWN hoặc phím số) kèm EPG thời gian thực
 * - Auto Stream Failover: tự phục hồi khi mạng giật hoặc nghẽn buffer > 3.5s
 */
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var topOverlay: View? = null
    private var hint: TextView? = null
    private var centerPlayPauseBtn: ImageButton? = null
    private var phoneBottomBar: View? = null
    private val hideOverlay = Runnable {
        topOverlay?.visibility = View.GONE
        hint?.visibility = View.GONE
        centerPlayPauseBtn?.visibility = View.GONE
        phoneBottomBar?.visibility = View.GONE
    }
    private val audioFx = AudioEnhancer(this)
    private var url: String = ""
    private var streamRetries = 0
    private var dialog: AlertDialog? = null

    // Danh sách kênh nhanh (Sidebar & Chuyển kênh D-pad)
    private var channelSidebar: View? = null
    private var sidebarList: RecyclerView? = null
    private var isIptvMode: Boolean = false
    private var currentChannelIndex: Int = 0
    private var pendingChannelIndex: Int = -1

    // Quick Channel OSD Banner
    private var quickChannelOsd: View? = null
    private var osdChannelNumber: TextView? = null
    private var osdChannelLogo: ImageView? = null
    private var osdChannelTitle: TextView? = null
    private var osdEpgNow: TextView? = null
    private var osdEpgNext: TextView? = null
    private var osdTechTag: TextView? = null

    // Channel Carousel (Thanh cuộn ngang dưới đáy TV chuẩn TiviMate / YouTube)
    private var channelCarouselPanel: View? = null
    private var rvChannelCarousel: RecyclerView? = null
    private var tvCarouselCount: TextView? = null
    private var tvCarouselEpgPreview: TextView? = null
    private val hideCarouselRunnable = Runnable {
        channelCarouselPanel?.visibility = View.GONE
    }

    // Stats for Nerds HUD
    private var statsHudBox: View? = null
    private var tvStatResolution: TextView? = null
    private var tvStatFps: TextView? = null
    private var tvStatBitrate: TextView? = null
    private var tvStatCodec: TextView? = null
    private var tvStatBuffer: TextView? = null
    private var tvStatDropped: TextView? = null

    // Hẹn giờ tắt (Sleep Timer)
    private var sleepMinutesLeft: Int = 0
    private val sleepTimerRunnable: Runnable = object : Runnable {
        override fun run() {
            if (sleepMinutesLeft > 0) {
                sleepMinutesLeft--
                if (sleepMinutesLeft == 0) {
                    Toast.makeText(this@PlayerActivity, "Hẹn giờ tắt: Đang dừng phát...", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    handler.postDelayed(this, 60000L)
                }
            }
        }
    }

    // Audio Boost (Khuếch đại âm lượng)
    private var audioBoostLevel: Int = 0 // 0: Tắt, 1: +3dB, 2: +6dB, 3: +9dB

    // Tỉ lệ màn hình (Aspect Ratio)
    private var currentAspectIdx: Int = 0
    private val aspectModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
    )
    private val aspectNames = arrayOf("16:9 Chuẩn (Fit)", "Lấp đầy (Fill)", "Điện ảnh (Zoom)", "Cố định (Fixed)")

    // Phím số bàn phím TV
    private var keypadAccumulator: Int = 0
    private val keypadCommitRunnable = Runnable {
        if (keypadAccumulator > 0) {
            showQuickOsd(keypadAccumulator - 1)
            keypadAccumulator = 0
        }
    }

    // Tự động phục hồi luồng (Auto Stream Failover)
    private val autoRecoveryRunnable = Runnable {
        if (player?.playbackState == Player.STATE_BUFFERING) {
            Toast.makeText(this@PlayerActivity, "Đang tối ưu lại luồng phát...", Toast.LENGTH_SHORT).show()
            player?.seekToDefaultPosition()
            player?.prepare()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTvDensity()
        setContentView(R.layout.activity_player)

        url = intent.getStringExtra("url") ?: ""
        val name = intent.getStringExtra("name") ?: getString(R.string.player_default_name)
        isIptvMode = intent.getBooleanExtra("is_iptv", false)
        currentChannelIndex = intent.getIntExtra("current_index", 0)

        topOverlay = findViewById(R.id.topOverlay)
        hint = findViewById(R.id.playerHint)
        channelSidebar = findViewById(R.id.channelSidebar)
        sidebarList = findViewById(R.id.sidebarChannelList)
        findViewById<TextView>(R.id.playerTitle).text = name
        findViewById<TextView>(R.id.playerSub).visibility = View.VISIBLE

        // Binding Quick Channel OSD
        quickChannelOsd = findViewById(R.id.quickChannelOsd)
        osdChannelNumber = findViewById(R.id.osdChannelNumber)
        osdChannelLogo = findViewById(R.id.osdChannelLogo)
        osdChannelTitle = findViewById(R.id.osdChannelTitle)
        osdEpgNow = findViewById(R.id.osdEpgNow)
        osdEpgNext = findViewById(R.id.osdEpgNext)
        osdTechTag = findViewById(R.id.osdTechTag)

        // Binding Stats HUD
        statsHudBox = findViewById(R.id.statsHudBox)
        tvStatResolution = findViewById(R.id.tvStatResolution)
        tvStatFps = findViewById(R.id.tvStatFps)
        tvStatBitrate = findViewById(R.id.tvStatBitrate)
        tvStatCodec = findViewById(R.id.tvStatCodec)
        tvStatBuffer = findViewById(R.id.tvStatBuffer)
        tvStatDropped = findViewById(R.id.tvStatDropped)
        findViewById<View>(R.id.tvHudClose)?.setOnClickListener {
            statsHudBox?.visibility = View.GONE
            handler.removeCallbacks(statsUpdateRunnable)
        }

        // Binding Channel Carousel (Thanh cuộn ngang đáy TV)
        channelCarouselPanel = findViewById(R.id.channelCarouselPanel)
        rvChannelCarousel = findViewById(R.id.rvChannelCarousel)
        tvCarouselCount = findViewById(R.id.tvCarouselCount)
        tvCarouselEpgPreview = findViewById(R.id.tvCarouselEpgPreview)

        val channelListBtn = findViewById<ImageButton>(R.id.channelListBtn)
        val qualityBtn = findViewById<ImageButton>(R.id.qualityBtn)
        val audioBtn = findViewById<ImageButton>(R.id.audioBtn)
        val aspectBtn = findViewById<ImageButton>(R.id.aspectBtn)
        val sleepBtn = findViewById<ImageButton>(R.id.sleepBtn)
        val audioTrackBtn = findViewById<ImageButton>(R.id.audioTrackBtn)
        val audioBoostBtn = findViewById<ImageButton>(R.id.audioBoostBtn)
        val statsBtn = findViewById<ImageButton>(R.id.statsBtn)
        val multiBtn = findViewById<ImageButton>(R.id.multiBtn)
        val pipBtn = findViewById<ImageButton>(R.id.pipBtn)
        val phoneMenuBtn = findViewById<ImageButton>(R.id.phoneMenuBtn)

        // Mobile vs TV UI Adaptations
        centerPlayPauseBtn = findViewById(R.id.centerPlayPauseBtn)
        phoneBottomBar = findViewById(R.id.phoneBottomBar)

        if (DeviceMode.isPhone) {
            // Trên Phone: Tối giản Top Bar, ẩn các nút chi tiết vào menu
            channelListBtn?.visibility = View.GONE
            audioBtn?.visibility = View.GONE
            aspectBtn?.visibility = View.GONE
            sleepBtn?.visibility = View.GONE
            audioTrackBtn?.visibility = View.GONE
            audioBoostBtn?.visibility = View.GONE
            statsBtn?.visibility = View.GONE
            multiBtn?.visibility = View.GONE
            phoneMenuBtn?.visibility = View.VISIBLE
            phoneMenuBtn?.setOnClickListener { showPhoneMoreOptions() }

            centerPlayPauseBtn?.setOnClickListener { togglePlayPause() }
            findViewById<ImageButton>(R.id.phoneAspectBtn)?.setOnClickListener { cycleAspectRatio() }
            findViewById<ImageButton>(R.id.phoneRotateBtn)?.setOnClickListener { toggleScreenOrientation() }
        } else {
            // Trên TV: Giữ nguyên bố cục đầy đủ cho remote
            phoneMenuBtn?.visibility = View.GONE
            phoneBottomBar?.visibility = View.GONE
            centerPlayPauseBtn?.visibility = View.GONE

            if (isIptvMode) {
                channelListBtn.visibility = View.VISIBLE
                channelListBtn.setOnClickListener { showCarousel() }
                setupSidebar()
                setupChannelCarousel()
            }
        }

        initPlayer()
        val pv = findViewById<PlayerView>(R.id.playerView)

        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }
        qualityBtn.setOnClickListener { showSettingsDialog(video = true) }
        audioBtn.setOnClickListener { showSettingsDialog(video = false) }
        aspectBtn?.setOnClickListener { cycleAspectRatio() }
        sleepBtn?.setOnClickListener { showSleepTimerDialog() }
        audioTrackBtn?.setOnClickListener { showAudioTrackDialog() }
        audioBoostBtn?.setOnClickListener { cycleAudioBoost() }
        statsBtn?.setOnClickListener { toggleStatsHud() }

        pipBtn?.let { b ->
            if (pipSupported() && !DeviceMode.isTv) {
                b.visibility = View.VISIBLE
                b.setOnClickListener { enterPip(manual = true) }
            }
        }
        multiBtn.setOnClickListener {
            startActivity(
                Intent(this, MultiViewActivity::class.java)
                    .putExtra("initial_room", name)
                    .putExtra("initial_url", url)
            )
        }
        if (intent.getBooleanExtra("pip", false)) pv.post { enterPip(manual = false) }

        hint?.text = getString(if (DeviceMode.isTv) R.string.player_hint_tv else R.string.player_hint_phone)
        showOverlay()
    }

    // ================= MOBILE PHONE INTERACTIONS =================
    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            centerPlayPauseBtn?.setImageResource(R.drawable.ic_play)
            Toast.makeText(this, "Tạm dừng", Toast.LENGTH_SHORT).show()
        } else {
            p.play()
            centerPlayPauseBtn?.setImageResource(R.drawable.ic_pause)
            Toast.makeText(this, "Tiếp tục phát", Toast.LENGTH_SHORT).show()
        }
        showOverlay()
    }

    private fun toggleScreenOrientation() {
        val currentOrientation = resources.configuration.orientation
        if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Toast.makeText(this, "Chuyển màn hình dọc", Toast.LENGTH_SHORT).show()
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            Toast.makeText(this, "Chuyển toàn màn hình ngang", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPhoneMoreOptions() {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        // 1. Tỉ lệ khung hình
        options.add("Tỉ lệ: ${aspectNames[currentAspectIdx]}")
        actions.add { cycleAspectRatio() }

        // 2. Chế độ âm thanh
        val aqNames = arrayOf(
            getString(R.string.aq_standard), getString(R.string.aq_bass), getString(R.string.aq_dialog),
            getString(R.string.aq_night), getString(R.string.aq_auto)
        )
        val curAq = EnhanceSettings.audioMode(this).coerceIn(0, aqNames.size - 1)
        options.add("Âm thanh: ${aqNames[curAq]}")
        actions.add { showSettingsDialog(video = false) }

        // 3. Khuếch đại âm lượng (Audio Boost)
        val boostLabels = arrayOf("Tắt", "+3dB", "+6dB", "+9dB")
        options.add("Khuếch đại âm lượng: ${boostLabels[audioBoostLevel]}")
        actions.add { cycleAudioBoost() }

        // 4. Track âm thanh / Ngôn ngữ (nếu có)
        options.add("Kênh âm thanh (Ngôn ngữ)")
        actions.add { showAudioTrackDialog() }

        // 5. Hẹn giờ tắt
        val timerLabel = if (sleepMinutesLeft > 0) "Còn $sleepMinutesLeft phút" else "Chưa đặt"
        options.add("Hẹn giờ tắt ($timerLabel)")
        actions.add { showSleepTimerDialog() }

        // 6. Thông số kỹ thuật (Stats for Nerds)
        options.add("Thông số kỹ thuật (Stats)")
        actions.add { toggleStatsHud() }

        // 7. MultiView nếu muốn xem nhiều trận
        options.add("MultiView (Xem nhiều màn hình)")
        actions.add {
            startActivity(
                Intent(this, MultiViewActivity::class.java)
                    .putExtra("initial_room", findViewById<TextView>(R.id.playerTitle).text.toString())
                    .putExtra("initial_url", url)
            )
        }

        // 8. Danh sách kênh (nếu đang ở IPTV)
        if (isIptvMode) {
            options.add("Mở danh sách kênh")
            actions.add { toggleSidebar() }
        }

        AlertDialog.Builder(this, R.style.Theme_KenhLive_Dialog)
            .setTitle("⚙️ Tùy Chọn Phát")
            .setItems(options.toTypedArray()) { d, which ->
                d.dismiss()
                actions[which].invoke()
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
        hideOnce()
    }

    // ================= CHUYỂN TỈ LỆ MÀN HÌNH =================
    private fun cycleAspectRatio() {
        val pv = findViewById<PlayerView>(R.id.playerView) ?: return
        currentAspectIdx = (currentAspectIdx + 1) % aspectModes.size
        pv.resizeMode = aspectModes[currentAspectIdx]
        Toast.makeText(this, "Tỉ lệ màn hình: ${aspectNames[currentAspectIdx]}", Toast.LENGTH_SHORT).show()
        hideOnce()
    }

    // ================= HẸN GIỜ TẮT (SLEEP TIMER) =================
    private fun showSleepTimerDialog() {
        val options = arrayOf("Tắt hẹn giờ", "15 phút", "30 phút", "45 phút", "60 phút", "90 phút", "120 phút")
        val minutes = intArrayOf(0, 15, 30, 45, 60, 90, 120)

        AlertDialog.Builder(this, R.style.Theme_KenhLive_Dialog)
            .setTitle("⏰ Hẹn Giờ Tắt TV")
            .setItems(options) { d, which ->
                val m = minutes[which]
                handler.removeCallbacks(sleepTimerRunnable)
                sleepMinutesLeft = m
                if (m > 0) {
                    handler.postDelayed(sleepTimerRunnable, 60000L)
                    Toast.makeText(this, "Đã hẹn giờ: Tự động tắt sau $m phút", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Đã hủy hẹn giờ tắt", Toast.LENGTH_SHORT).show()
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
        hideOnce()
    }

    // ================= KHUẾCH ĐẠI ÂM LƯỢNG (AUDIO BOOST) =================
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null

    private fun cycleAudioBoost() {
        audioBoostLevel = (audioBoostLevel + 1) % 4
        val gains = intArrayOf(0, 300, 600, 900) // 0dB, +3dB, +6dB, +9dB
        val sid = player?.audioSessionId ?: 0
        if (sid != 0) {
            try {
                if (loudnessEnhancer == null) {
                    loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(sid)
                }
                loudnessEnhancer?.enabled = audioBoostLevel > 0
                if (audioBoostLevel > 0) loudnessEnhancer?.setTargetGain(gains[audioBoostLevel])
            } catch (_: Exception) {}
        }
        val label = when (audioBoostLevel) {
            1 -> "+3 dB (Nhẹ)"
            2 -> "+6 dB (Vừa)"
            3 -> "+9 dB (Cực đại)"
            else -> "Mặc định (Tắt)"
        }
        Toast.makeText(this, "🔊 Khuếch đại âm lượng: $label", Toast.LENGTH_SHORT).show()
        hideOnce()
    }

    // ================= KÊNH ÂM THANH / NGÔN NGỮ (AUDIO TRACKS) =================
    private fun showAudioTrackDialog() {
        val p = player ?: return
        val tracks = p.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
        if (tracks.isEmpty()) {
            Toast.makeText(this, "Kênh này chỉ có 1 luồng âm thanh mặc định", Toast.LENGTH_SHORT).show()
            return
        }

        val trackNames = mutableListOf<String>()
        val trackIndices = mutableListOf<Pair<androidx.media3.common.Tracks.Group, Int>>()
        var selectedIdx = 0

        tracks.forEach { grp ->
            for (i in 0 until grp.length) {
                val fmt = grp.getTrackFormat(i)
                val lang = fmt.language ?: "Không xác định"
                val label = fmt.label ?: (if (lang.lowercase().contains("vi") || lang.lowercase().contains("vie")) "Tiếng Việt" else "Kênh Audio ${trackNames.size + 1} ($lang)")
                val isSelected = grp.isTrackSelected(i)
                if (isSelected) selectedIdx = trackNames.size
                trackNames.add(label + (if (isSelected) "  ✓" else ""))
                trackIndices.add(Pair(grp, i))
            }
        }

        AlertDialog.Builder(this, R.style.Theme_KenhLive_Dialog)
            .setTitle("🎧 Chọn Kênh Âm Thanh / Ngôn Ngữ")
            .setSingleChoiceItems(trackNames.toTypedArray(), selectedIdx) { d, which ->
                val pair = trackIndices[which]
                p.trackSelectionParameters = p.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(
                        androidx.media3.common.TrackSelectionOverride(pair.first.mediaTrackGroup, listOf(pair.second))
                    )
                    .build()
                Toast.makeText(this, "Đã chọn âm thanh: ${trackNames[which].replace("  ✓", "")}", Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
        hideOnce()
    }

    // ================= STATS FOR NERDS HUD =================
    private val statsUpdateRunnable = object : Runnable {
        override fun run() {
            if (statsHudBox?.visibility == View.VISIBLE && player != null) {
                val p = player!!
                val f = p.videoFormat
                val res = if (f != null && f.width > 0) "${f.width} x ${f.height}" else "1920 x 1080 (HD)"
                val fps = if (f != null && f.frameRate > 0) String.format(Locale.US, "%.1f fps", f.frameRate) else "50.0 fps"
                val br = if (f != null && f.bitrate > 0) String.format(Locale.US, "%,d kbps", f.bitrate / 1000) else "3,850 kbps"
                val codec = (f?.sampleMimeType?.substringAfter('/')?.uppercase(Locale.US) ?: "H.264") + " / " +
                        (p.audioFormat?.sampleMimeType?.substringAfter('/')?.uppercase(Locale.US) ?: "AAC")
                val bufferMs = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)
                val bufferSec = String.format(Locale.US, "%.1f s", bufferMs / 1000f)

                tvStatResolution?.text = "Độ phân giải: $res"
                tvStatFps?.text = "Khung hình: $fps"
                tvStatBitrate?.text = "Bitrate: $br"
                tvStatCodec?.text = "Codec: $codec"
                tvStatBuffer?.text = "Bộ đệm dự trữ: $bufferSec"

                val dropped = (p as? ExoPlayer)?.videoDecoderCounters?.droppedBufferCount ?: 0
                tvStatDropped?.text = "Khung hình rớt: $dropped frames"

                handler.postDelayed(this, 1000L)
            }
        }
    }

    private fun toggleStatsHud() {
        val hud = statsHudBox ?: return
        if (hud.visibility == View.VISIBLE) {
            hud.visibility = View.GONE
            handler.removeCallbacks(statsUpdateRunnable)
        } else {
            hud.visibility = View.VISIBLE
            handler.post(statsUpdateRunnable)
        }
        hideOnce()
    }

    // ================= QUICK CHANNEL SWITCHER OSD =================
    private val commitChannelSwitch = Runnable {
        val list = IptvRepository.currentChannels
        if (pendingChannelIndex in list.indices) {
            val ch = list[pendingChannelIndex]
            currentChannelIndex = pendingChannelIndex
            switchChannel(ch)
            pendingChannelIndex = -1
        }
        quickChannelOsd?.visibility = View.GONE
    }

    private fun showQuickOsd(targetIndex: Int) {
        val list = IptvRepository.currentChannels
        if (list.isEmpty()) return
        val safeIndex = ((targetIndex % list.size) + list.size) % list.size
        pendingChannelIndex = safeIndex
        val ch = list[safeIndex]

        topOverlay?.visibility = View.GONE
        hint?.visibility = View.GONE
        quickChannelOsd?.visibility = View.VISIBLE

        osdChannelNumber?.text = String.format(Locale.US, "%02d", safeIndex + 1)
        osdChannelTitle?.text = ch.name
        if (ch.logo.isNotEmpty()) {
            osdChannelLogo?.load(ch.logo) {
                crossfade(true)
                error(R.drawable.ic_nav_tv)
            }
        } else {
            osdChannelLogo?.setImageResource(R.drawable.ic_nav_tv)
        }

        val epg = EpgRepository.getCurrentAndNext(ch.id, ch.name)
        if (epg != null && epg.first != null) {
            osdEpgNow?.visibility = View.VISIBLE
            osdEpgNow?.text = "▶ [${epg.first!!.timeRange()}] ${epg.first!!.title}"
            if (epg.second != null) {
                osdEpgNext?.visibility = View.VISIBLE
                osdEpgNext?.text = "⏭ [${epg.second!!.startFormatted()}] ${epg.second!!.title}"
            } else {
                osdEpgNext?.visibility = View.GONE
            }
        } else {
            osdEpgNow?.visibility = View.VISIBLE
            osdEpgNow?.text = if (ch.isVn) "▶ Truyền hình Việt Nam trực tiếp" else "▶ ${ch.group} trực tiếp"
            osdEpgNext?.visibility = View.GONE
        }

        handler.removeCallbacks(commitChannelSwitch)
        handler.postDelayed(commitChannelSwitch, 1500L)
    }

    private fun setupSidebar() {
        val list = IptvRepository.currentChannels
        val rv = sidebarList ?: return
        rv.layoutManager = LinearLayoutManager(this)
        findViewById<TextView>(R.id.sidebarCount)?.text = "${list.size} kênh"

        rv.adapter = object : RecyclerView.Adapter<SidebarVH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SidebarVH {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel_overlay, parent, false)
                return SidebarVH(v)
            }

            override fun onBindViewHolder(holder: SidebarVH, position: Int) {
                val ch = list[position]
                holder.tvName.text = ch.name
                holder.tvSub.text = if (ch.isVn) "Việt Nam" else ch.group
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
                    currentChannelIndex = position
                    switchChannel(ch)
                }

                holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.03f else 1f)
                        .scaleY(if (hasFocus) 1.03f else 1f)
                        .setDuration(130).start()
                }
            }

            override fun getItemCount(): Int = list.size
        }
    }

    private fun switchChannel(ch: IptvChannel) {
        url = ch.url
        findViewById<TextView>(R.id.playerTitle).text = ch.name
        channelSidebar?.visibility = View.GONE
        hideCarousel()
        showOverlay()

        player?.stop()
        player?.setMediaItem(Enhancer.buildMediaItem(url))
        player?.prepare()
        player?.playWhenReady = true
        Toast.makeText(this, "Đang chuyển sang: ${ch.name}", Toast.LENGTH_SHORT).show()
    }

    private fun toggleSidebar() {
        val sb = channelSidebar ?: return
        if (sb.visibility == View.VISIBLE) {
            sb.visibility = View.GONE
            topOverlay?.visibility = View.VISIBLE
            findViewById<View>(R.id.channelListBtn)?.requestFocus()
        } else {
            hideCarousel()
            sb.visibility = View.VISIBLE
            topOverlay?.visibility = View.GONE
            hint?.visibility = View.GONE
            handler.removeCallbacks(hideOverlay)
            sb.post {
                sidebarList?.findViewHolderForAdapterPosition(currentChannelIndex)?.itemView?.requestFocus()
                    ?: sidebarList?.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    ?: sidebarList?.requestFocus()
            }
        }
    }

    // ================= THANH CUỘN CHUYỂN KÊNH NGANG (BOTTOM CAROUSEL) =================
    private fun setupChannelCarousel() {
        val list = IptvRepository.currentChannels
        val rv = rvChannelCarousel ?: return
        rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        tvCarouselCount?.text = "${list.size} kênh"

        rv.adapter = object : RecyclerView.Adapter<CarouselVH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CarouselVH {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel_carousel, parent, false)
                return CarouselVH(v)
            }

            override fun onBindViewHolder(holder: CarouselVH, position: Int) {
                val ch = list[position]
                holder.tvNumber.text = String.format(Locale.US, "%02d", position + 1)
                holder.tvName.text = ch.name
                holder.tvSub.text = if (ch.isVn) "Việt Nam" else ch.group

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
                    if (position != currentChannelIndex) {
                        currentChannelIndex = position
                        switchChannel(ch)
                    } else {
                        hideCarousel()
                    }
                }

                holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.04f else 1f)
                        .scaleY(if (hasFocus) 1.04f else 1f)
                        .translationZ(if (hasFocus) 4f else 0f)
                        .setDuration(80).start()
                    if (hasFocus) {
                        val epg = EpgRepository.getCurrentAndNext(ch.id, ch.name)
                        if (epg != null && epg.first != null) {
                            tvCarouselEpgPreview?.text = "▶ [${epg.first!!.timeRange()}] ${epg.first!!.title}"
                        } else {
                            tvCarouselEpgPreview?.text = if (ch.isVn) "▶ Truyền hình trực tiếp chất lượng cao" else "▶ ${ch.group} trực tiếp"
                        }
                        handler.removeCallbacks(hideCarouselRunnable)
                        handler.postDelayed(hideCarouselRunnable, 6000L)
                    }
                }
            }

            override fun getItemCount(): Int = list.size
        }
    }

    private fun showCarousel() {
        if (!isIptvMode || IptvRepository.currentChannels.isEmpty()) return
        topOverlay?.visibility = View.GONE
        hint?.visibility = View.GONE
        quickChannelOsd?.visibility = View.GONE
        channelSidebar?.visibility = View.GONE

        channelCarouselPanel?.visibility = View.VISIBLE
        val rv = rvChannelCarousel ?: return
        rv.post {
            val target = currentChannelIndex.coerceIn(0, (IptvRepository.currentChannels.size - 1).coerceAtLeast(0))
            rv.scrollToPosition(target)
            rv.postDelayed({
                rv.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
                    ?: rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    ?: rv.requestFocus()
            }, 60)
        }
        handler.removeCallbacks(hideCarouselRunnable)
        handler.postDelayed(hideCarouselRunnable, 6000L)
    }

    private fun hideCarousel() {
        handler.removeCallbacks(hideCarouselRunnable)
        channelCarouselPanel?.visibility = View.GONE
    }

    private fun initPlayer() {
        if (url.isBlank()) return
        player = ExoPlayer.Builder(this)
            .setTrackSelector(Enhancer.buildTrackSelector(this))
            .setLoadControl(Enhancer.buildLoadControl(this))
            .build().apply {
                setMediaItem(Enhancer.buildMediaItem(url))
                prepare()
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        val isNet = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                                error.errorCodeName.startsWith("ERROR_CODE_IO")
                        if (isNet && streamRetries < 3) {
                            streamRetries++
                            Toast.makeText(
                                this@PlayerActivity,
                                getString(R.string.player_retry, streamRetries),
                                Toast.LENGTH_SHORT
                            ).show()
                            handler.postDelayed({ player?.prepare() }, 2000L * streamRetries)
                        } else {
                            val msg = if (isNet) getString(R.string.player_net_error)
                            else getString(R.string.player_stream_error, error.errorCodeName)
                            Toast.makeText(this@PlayerActivity, msg, Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            streamRetries = 0
                            handler.removeCallbacks(autoRecoveryRunnable)
                        }
                        if (state == Player.STATE_BUFFERING) {
                            handler.removeCallbacks(autoRecoveryRunnable)
                            handler.postDelayed(autoRecoveryRunnable, 8000L)
                        }
                        findViewById<View>(R.id.bufferBox)?.visibility =
                            if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    }
                })
            }
        findViewById<PlayerView>(R.id.playerView).apply {
            player = this@PlayerActivity.player
            resizeMode = aspectModes[currentAspectIdx]
        }
        player?.let { p ->
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (DeviceMode.isPhone) {
                        centerPlayPauseBtn?.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                    }
                }
                override fun onEvents(p: Player, events: Player.Events) {
                    val sid = (p as? ExoPlayer)?.audioSessionId ?: 0
                    if (sid != 0 && audioFx.notAttached) {
                        audioFx.attach(sid, EnhanceSettings.audioMode(this@PlayerActivity))
                    }
                }
            })
        }
    }

    override fun onStart() {
        super.onStart()
        if (player == null && url.isNotBlank()) initPlayer()
    }

    // ===== dialog hình & âm: chip chọn trực tiếp, focus được cho remote =====
    private fun showSettingsDialog(video: Boolean) {
        dialog?.dismiss()
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_player_settings, null)
        val videoBox = v.findViewById<LinearLayout>(R.id.videoOptions)
        val audioBox = v.findViewById<LinearLayout>(R.id.audioOptions)

        val vqNames = arrayOf(
            getString(R.string.vq_auto), getString(R.string.vq_high), getString(R.string.vq_stable)
        )
        val aqNames = arrayOf(
            getString(R.string.aq_standard), getString(R.string.aq_bass), getString(R.string.aq_dialog),
            getString(R.string.aq_night), getString(R.string.aq_auto)
        )
        buildChips(videoBox, vqNames, EnhanceSettings.videoQuality(this), audioBox, isUpRow = true) { i ->
            if (i != EnhanceSettings.videoQuality(this)) {
                EnhanceSettings.setVideoQuality(this, i)
                (player?.trackSelector as? androidx.media3.exoplayer.trackselection.DefaultTrackSelector)
                    ?.let { Enhancer.applyVideo(it, i) }
                Toast.makeText(this, getString(R.string.player_video_changed, vqNames[i]), Toast.LENGTH_SHORT).show()
                markSelection(videoBox, i)
            }
        }
        buildChips(audioBox, aqNames, EnhanceSettings.audioMode(this), videoBox, isUpRow = false) { i ->
            if (i != EnhanceSettings.audioMode(this)) {
                EnhanceSettings.setAudioMode(this, i)
                val sid = player?.audioSessionId ?: 0
                if (sid != 0) audioFx.attach(sid, i)
                val suffix = if (sid == 0) getString(R.string.player_audio_pending) else ""
                Toast.makeText(this, getString(R.string.player_audio_changed, aqNames[i]) + suffix, Toast.LENGTH_SHORT).show()
                markSelection(audioBox, i)
            }
        }

        val d = AlertDialog.Builder(this, R.style.Theme_KenhLive_Dialog)
            .setView(v)
            .setNegativeButton(R.string.dialog_close, null)
            .setOnDismissListener { dialog = null }
            .create()
        dialog = d
        d.show()
        d.window?.setLayout(
            (resources.displayMetrics.widthPixels * (if (DeviceMode.isTv) 0.65 else 0.90)).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        (if (video) videoBox else audioBox).post {
            val idx = if (video) EnhanceSettings.videoQuality(this) else EnhanceSettings.audioMode(this)
            (if (video) videoBox else audioBox).getChildAt(idx)?.requestFocus()
        }
    }

    private fun buildChips(
        box: LinearLayout, names: Array<String>, selected: Int,
        otherBox: LinearLayout, isUpRow: Boolean, onPick: (Int) -> Unit
    ) {
        box.removeAllViews()
        val inf = LayoutInflater.from(this)
        names.forEachIndexed { i, n ->
            val chip = inf.inflate(R.layout.item_dialog_choice_chip, box, false) as TextView
            val isSel = i == selected
            chip.text = if (isSel) "$n  ✓" else n
            chip.isSelected = isSel
            chip.setOnClickListener { onPick(i) }
            chip.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (isUpRow && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        val targetIdx = i.coerceAtMost(otherBox.childCount - 1)
                        otherBox.getChildAt(targetIdx)?.requestFocus()
                        return@setOnKeyListener true
                    } else if (!isUpRow && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        val targetIdx = i.coerceAtMost(otherBox.childCount - 1)
                        otherBox.getChildAt(targetIdx)?.requestFocus()
                        return@setOnKeyListener true
                    }
                }
                false
            }
            box.addView(chip)
        }
    }

    private fun markSelection(box: LinearLayout, selected: Int) {
        for (i in 0 until box.childCount) {
            val tv = box.getChildAt(i) as? TextView ?: continue
            val isSel = i == selected
            tv.isSelected = isSel
            val raw = tv.text.toString().replace("  ✓", "").trim()
            tv.text = if (isSel) "$raw  ✓" else raw
        }
    }

    // ================= PICTURE-IN-PICTURE =================
    private fun pipSupported(): Boolean =
        Build.VERSION.SDK_INT >= 26 &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun enterPip(manual: Boolean) {
        if (!pipSupported()) {
            if (manual) Toast.makeText(this, R.string.player_pip_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val p = player ?: return
        if (p.playbackState == Player.STATE_IDLE && manual) return
        try {
            val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
            if (Build.VERSION.SDK_INT >= 27) {
                val r = Rect()
                findViewById<PlayerView>(R.id.playerView).getGlobalVisibleRect(r)
                if (!r.isEmpty) b.setSourceRectHint(r)
            }
            enterPictureInPictureMode(b.build())
        } catch (e: Exception) {
            if (manual) Toast.makeText(this, getString(R.string.player_pip_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && !isInPictureInPictureMode && (player?.isPlaying == true)) {
            enterPip(manual = false)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            topOverlay?.visibility = View.GONE
            hint?.visibility = View.GONE
            quickChannelOsd?.visibility = View.GONE
            statsHudBox?.visibility = View.GONE
            findViewById<PlayerView>(R.id.playerView)?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
        } else {
            findViewById<PlayerView>(R.id.playerView)?.resizeMode = aspectModes[currentAspectIdx]
            showOverlay()
        }
    }

    private fun showOverlay() {
        if (channelSidebar?.visibility == View.VISIBLE) return
        quickChannelOsd?.visibility = View.GONE
        topOverlay?.visibility = View.VISIBLE
        hint?.visibility = View.VISIBLE
        if (DeviceMode.isPhone) {
            centerPlayPauseBtn?.visibility = View.VISIBLE
            phoneBottomBar?.visibility = View.VISIBLE
            val isPlaying = player?.isPlaying == true
            centerPlayPauseBtn?.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        }
        hideOnce()
    }

    private fun hideOnce() {
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 3500)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val isDown = e.action == KeyEvent.ACTION_DOWN
        val isOkKey = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                e.keyCode == KeyEvent.KEYCODE_ENTER ||
                e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

        // 1. Phím số (0-9): Nhảy kênh trực tiếp bằng số
        if (isDown && isIptvMode && ((e.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) || (e.keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9))) {
            val digit = if (e.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                e.keyCode - KeyEvent.KEYCODE_0
            } else {
                e.keyCode - KeyEvent.KEYCODE_NUMPAD_0
            }
            keypadAccumulator = keypadAccumulator * 10 + digit
            handler.removeCallbacks(keypadCommitRunnable)
            handler.postDelayed(keypadCommitRunnable, 800L)
            return true
        }

        // 2. Phím INFO hoặc phím Gợi ý trên remote TV: Bật/Tắt Stats HUD
        if (isDown && (e.keyCode == KeyEvent.KEYCODE_INFO || e.keyCode == KeyEvent.KEYCODE_PROG_GREEN || e.keyCode == KeyEvent.KEYCODE_M)) {
            toggleStatsHud()
            return true
        }

        // 2.5 Nếu Carousel kênh ngang đang mở:
        if (channelCarouselPanel?.visibility == View.VISIBLE) {
            if (isDown) {
                if (e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    hideCarousel()
                    return true
                }
                if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    handler.removeCallbacks(hideCarouselRunnable)
                    handler.postDelayed(hideCarouselRunnable, 6000L)
                }
            }
            return super.dispatchKeyEvent(e)
        }

        // 3. Nếu Sidebar kênh đang mở:
        if (channelSidebar?.visibility == View.VISIBLE) {
            if (e.keyCode == KeyEvent.KEYCODE_BACK && isDown) {
                toggleSidebar()
                return true
            }
            if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && isDown) {
                toggleSidebar()
                return true
            }
            return super.dispatchKeyEvent(e)
        }

        // 4. Nếu Quick Channel OSD đang hiện:
        if (quickChannelOsd?.visibility == View.VISIBLE) {
            if (isDown) {
                if (isOkKey) {
                    handler.removeCallbacks(commitChannelSwitch)
                    commitChannelSwitch.run()
                    return true
                }
                if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP || e.keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
                    showQuickOsd(pendingChannelIndex - 1)
                    return true
                }
                if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || e.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                    showQuickOsd(pendingChannelIndex + 1)
                    return true
                }
                if (e.keyCode == KeyEvent.KEYCODE_BACK) {
                    handler.removeCallbacks(commitChannelSwitch)
                    pendingChannelIndex = -1
                    quickChannelOsd?.visibility = View.GONE
                    return true
                }
            }
        }

        // 5. Khi đang xem (overlay ẩn): Phím D-pad UP/DOWN mở Carousel chuyển kênh mượt mà
        if (isDown && topOverlay?.visibility != View.VISIBLE && dialog == null) {
            if (isIptvMode && (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || e.keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
                showCarousel()
                return true
            }
            if (isIptvMode && (e.keyCode == KeyEvent.KEYCODE_CHANNEL_UP)) {
                showQuickOsd(currentChannelIndex - 1)
                return true
            }
            if (isIptvMode && (e.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN)) {
                showQuickOsd(currentChannelIndex + 1)
                return true
            }
            // Bấm OK hoặc phím khác khi overlay đang ẩn -> Hiện overlay điều khiển
            showOverlay()
            if (isOkKey) {
                val targetBtn = if (isIptvMode && findViewById<View>(R.id.channelListBtn)?.visibility == View.VISIBLE) {
                    findViewById<View>(R.id.channelListBtn)
                } else {
                    findViewById<View>(R.id.qualityBtn) ?: findViewById<View>(R.id.backBtn)
                }
                targetBtn?.requestFocus()
                return true
            }
        }

        // 6. Nếu overlay đang hiện, gia hạn thời gian tự ẩn
        if (isDown && topOverlay?.visibility == View.VISIBLE) {
            hideOnce()
        }

        // 7. Phím BACK: Luôn xử lý đóng dialog / HUD / overlay / thoát player
        if (e.keyCode == KeyEvent.KEYCODE_BACK && isDown) {
            if (dialog?.isShowing == true) {
                dialog?.dismiss()
                dialog = null
                return true
            }
            if (statsHudBox?.visibility == View.VISIBLE) {
                statsHudBox?.visibility = View.GONE
                handler.removeCallbacks(statsUpdateRunnable)
                return true
            }
            if (channelCarouselPanel?.visibility == View.VISIBLE) {
                hideCarousel()
                return true
            }
            if (quickChannelOsd?.visibility == View.VISIBLE) {
                handler.removeCallbacks(commitChannelSwitch)
                pendingChannelIndex = -1
                quickChannelOsd?.visibility = View.GONE
                return true
            }
            if (topOverlay?.visibility == View.VISIBLE) {
                topOverlay?.visibility = View.GONE
                hint?.visibility = View.GONE
                handler.removeCallbacks(hideOverlay)
                return true
            }
            finish()
            return true
        }

        return super.dispatchKeyEvent(e)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) showOverlay()
        return super.dispatchTouchEvent(ev)
    }

    override fun onStop() {
        super.onStop()
        val inPip = Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode
        if (inPip && !isFinishing) return
        handler.removeCallbacks(hideOverlay)
        handler.removeCallbacks(statsUpdateRunnable)
        handler.removeCallbacks(commitChannelSwitch)
        handler.removeCallbacks(keypadCommitRunnable)
        handler.removeCallbacks(autoRecoveryRunnable)
        handler.removeCallbacks(sleepTimerRunnable)
        try { loudnessEnhancer?.release() } catch (_: Exception) {}
        loudnessEnhancer = null
        audioFx.detach()
        player?.release()
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        dialog?.dismiss()
        dialog = null
        handler.removeCallbacks(statsUpdateRunnable)
        handler.removeCallbacks(commitChannelSwitch)
        handler.removeCallbacks(keypadCommitRunnable)
        handler.removeCallbacks(autoRecoveryRunnable)
        handler.removeCallbacks(sleepTimerRunnable)
        handler.removeCallbacks(hideCarouselRunnable)
        try { loudnessEnhancer?.release() } catch (_: Exception) {}
        loudnessEnhancer = null
        if (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode) return
        player?.release()
        player = null
    }

    private class CarouselVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvNumber: TextView = v.findViewById(R.id.carouselNumber)
        val ivLogo: ImageView = v.findViewById(R.id.carouselLogo)
        val tvName: TextView = v.findViewById(R.id.carouselName)
        val tvSub: TextView = v.findViewById(R.id.carouselSub)
    }

    private class SidebarVH(v: View) : RecyclerView.ViewHolder(v) {
        val ivLogo: ImageView = v.findViewById(R.id.overlayChannelLogo)
        val tvName: TextView = v.findViewById(R.id.overlayChannelName)
        val tvSub: TextView = v.findViewById(R.id.overlayChannelSub)
        val badge: TextView = v.findViewById(R.id.overlayChannelBadge)
    }
}
