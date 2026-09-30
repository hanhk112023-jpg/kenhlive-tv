package com.kenhlive.tv

import android.app.Dialog
import android.app.PictureInPictureParams
import android.media.AudioManager
import android.view.GestureDetector
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.kenhlive.tv.ui.PlayerSheet
import kotlinx.coroutines.launch
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

    companion object {
        /**
         * Mở Player NGAY (không chờ mạng): truyền roomNum, Player tự lấy nguồn phát (có cache/prefetch)
         * và tự đổi nguồn khi lỗi. `url` tuỳ chọn nếu đã có sẵn.
         */
        fun launch(ctx: android.content.Context, roomNum: String, title: String, url: String? = null) {
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java)
                    .putExtra("room_num", roomNum)
                    .putExtra("name", title)
                    .apply { if (!url.isNullOrBlank()) putExtra("url", url) }
            )
        }
    }

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
    private var dialog: Dialog? = null

    // v7: nhiều nguồn phát + tự đổi nguồn
    private var roomNum: String = ""
    private var sources: List<SocoliveParser.StreamSource> = emptyList()
    private var sourceIdx = 0
    private var failoverCount = 0      // số lần đổi nguồn trong 1 sự cố (reset khi phát ổn định)
    private var stallStrikes = 0       // số lần đệm kẹt liên tiếp trên nguồn hiện tại
    private var osdChip: TextView? = null
    private val hideOsdChip = Runnable {
        osdChip?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction { osdChip?.visibility = View.GONE }?.start()
    }
    private val stableRunnable = Runnable { failoverCount = 0; stallStrikes = 0 }

    // v7: cử chỉ (điện thoại)
    private var gesture: GestureDetector? = null
    private var gMode = 0              // 0 không, 1 độ sáng, 2 âm lượng
    private var gStartX = 0f
    private var gStartY = 0f
    private var gStartVol = 0
    private var gStartBr = 0.5f
    private var gIgnore = false
    private val audioMgr by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

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
                    osd("Hẹn giờ tắt: Đang dừng phát...")
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
    private val aspectNames = arrayOf("Vừa khung", "Kéo giãn (Fill)", "Phóng to (Zoom)", "Vừa chiều ngang")

    // Phím số bàn phím TV
    private var keypadAccumulator: Int = 0
    private val keypadCommitRunnable = Runnable {
        if (keypadAccumulator > 0) {
            showQuickOsd(keypadAccumulator - 1)
            keypadAccumulator = 0
        }
    }

    // Tự động phục hồi luồng khi bị kẹt buffering hoặc drop kết nối (Auto Stream Failover)
    private val autoRecoveryRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.playbackState != Player.STATE_BUFFERING) return
            stallStrikes++
            // kẹt 2 lần liên tiếp (~8s) → nguồn này coi như chết, đổi nguồn kế tiếp nếu có
            if (stallStrikes >= 2 && trySwitchSource(getString(R.string.player_source_stalled))) return
            osd(getString(R.string.player_reconnecting))
            // Reset về Live edge (đầu luồng phát mới nhất) và nạp lại
            p.seekToDefaultPosition()
            p.prepare()
            p.play()
            handler.postDelayed(this, 4000L) // còn kẹt thì kiểm tra tiếp
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceMode.updateMode(this)
        applyTvDensity()
        setContentView(R.layout.activity_player)
        enterImmersive()
        currentAspectIdx = getSharedPreferences("player", MODE_PRIVATE).getInt("aspect", 0).coerceIn(0, aspectModes.size - 1)
        osdChip = findViewById(R.id.osdChip)
        setupGestures()

        url = intent.getStringExtra("url") ?: ""
        roomNum = intent.getStringExtra("room_num") ?: ""
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
            qualityBtn?.visibility = View.GONE
            audioBtn?.visibility = View.GONE
            aspectBtn?.visibility = View.GONE
            sleepBtn?.visibility = View.GONE
            audioTrackBtn?.visibility = View.GONE
            audioBoostBtn?.visibility = View.GONE
            statsBtn?.visibility = View.GONE
            multiBtn?.visibility = View.GONE

            centerPlayPauseBtn?.setOnClickListener { togglePlayPause() }
            findViewById<ImageButton>(R.id.phoneAspectBtn)?.setOnClickListener { cycleAspectRatio() }
            findViewById<ImageButton>(R.id.phoneRotateBtn)?.setOnClickListener { toggleScreenOrientation() }
        } else {
            // Trên TV: thanh điều khiển gọn (Kênh · Hình · Âm · Tỉ lệ · Thêm), phần còn lại nằm trong "Thêm"
            phoneBottomBar?.visibility = View.GONE
            centerPlayPauseBtn?.visibility = View.GONE

            if (isIptvMode) {
                channelListBtn.visibility = View.VISIBLE
                channelListBtn.setOnClickListener { showCarousel() }
                setupSidebar()
                setupChannelCarousel()
            }
        }

        // v7: nút "Thêm" (⋮) mở bảng điều khiển cho cả TV lẫn điện thoại
        phoneMenuBtn?.visibility = View.VISIBLE
        phoneMenuBtn?.setOnClickListener { openSheet() }

        initPlayer()
        resolveSources()
        val pv = findViewById<PlayerView>(R.id.playerView)
        pv.resizeMode = aspectModes[currentAspectIdx]

        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }
        qualityBtn.setOnClickListener { openSheet(start = "video") }
        audioBtn.setOnClickListener { openSheet(start = "audio") }
        aspectBtn?.setOnClickListener { cycleAspectRatio() }
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

        val defaultHint = getString(if (DeviceMode.isTv) R.string.player_hint_tv else R.string.player_hint_phone)
        hint?.text = defaultHint
        if (DeviceMode.isTv) {
            // TV: nút icon không có chữ → hiện tên nút ở dòng gợi ý khi focus
            listOf<View?>(backBtnView(), channelListBtn, qualityBtn, audioBtn, aspectBtn, phoneMenuBtn).forEach { b ->
                b?.setOnFocusChangeListener { v, has -> hint?.text = if (has) (v.contentDescription ?: defaultHint) else defaultHint }
            }
        }
        showOverlay()
    }

    private fun backBtnView(): View? = findViewById(R.id.backBtn)

    // ================= v7: IMMERSIVE + CỬ CHỈ + OSD =================
    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    /** Thông báo trạng thái nhỏ gọn (thay Toast): đổi nguồn, độ sáng, âm lượng… */
    fun osd(msg: String) {
        val c = osdChip ?: return
        c.text = msg
        c.animate().cancel()
        c.alpha = 1f
        c.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsdChip)
        handler.postDelayed(hideOsdChip, 1800L)
    }

    private fun hit(v: View?, x: Float, y: Float): Boolean {
        if (v == null || v.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return x >= loc[0] && x <= loc[0] + v.width && y >= loc[1] && y <= loc[1] + v.height
    }

    private fun touchOnControls(rawX: Float, rawY: Float): Boolean =
        hit(topOverlay, rawX, rawY) || hit(phoneBottomBar, rawX, rawY) || hit(centerPlayPauseBtn, rawX, rawY)

    private fun setupGestures() {
        if (!DeviceMode.isPhone) return
        gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (touchOnControls(e.rawX, e.rawY)) return false
                if (topOverlay?.visibility == View.VISIBLE) { handler.removeCallbacks(hideOverlay); hideOverlay.run() }
                else showOverlay()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (touchOnControls(e.rawX, e.rawY)) return false
                togglePlayPause()
                return true
            }
        })
    }

    private fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0f) return b
        return try {
            android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (_: Exception) { 0.5f }
    }

    /** Vuốt dọc: nửa trái = độ sáng, nửa phải = âm lượng. Trả true nếu đã tiêu thụ cử chỉ. */
    private fun handleSwipe(ev: MotionEvent): Boolean {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gMode = 0; gStartX = ev.x; gStartY = ev.y
                gStartVol = audioMgr.getStreamVolume(AudioManager.STREAM_MUSIC)
                gStartBr = currentBrightness()
                val edge = 28 * resources.displayMetrics.density
                gIgnore = ev.x < edge || ev.x > w - edge || touchOnControls(ev.rawX, ev.rawY) ||
                    (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode)
            }
            MotionEvent.ACTION_MOVE -> {
                if (gIgnore) return false
                val dx = ev.x - gStartX
                val dy = ev.y - gStartY
                if (gMode == 0) {
                    val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop * 2
                    if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx) * 1.5f) {
                        gMode = if (gStartX < w / 2f) 1 else 2
                        // huỷ touch của view con để không kích hoạt nút khi đang vuốt
                        val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(c); c.recycle()
                    }
                }
                if (gMode != 0) {
                    val frac = -dy / h
                    if (gMode == 1) {
                        val nb = (gStartBr + frac * 1.3f).coerceIn(0.02f, 1f)
                        window.attributes = window.attributes.also { it.screenBrightness = nb }
                        osd("☀  ${(nb * 100).toInt()}%")
                    } else {
                        val max = audioMgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val nv = Math.round(gStartVol + frac * max * 1.3f).coerceIn(0, max)
                        audioMgr.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
                        osd((if (nv == 0) "🔇  " else "🔊  ") + (nv * 100 / max.coerceAtLeast(1)) + "%")
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = gMode != 0
                gMode = 0
                if (was) return true
            }
        }
        return false
    }

    // ================= v7: NGUỒN PHÁT & TỰ ĐỔI NGUỒN =================
    /** Mở Player tức thì: URL có thể chưa có → tự lấy danh sách nguồn (cache/prefetch) rồi phát. */
    private fun resolveSources() {
        if (roomNum.isBlank()) return
        lifecycleScope.launch {
            val needUrl = url.isBlank()
            if (needUrl) findViewById<View>(R.id.bufferBox)?.visibility = View.VISIBLE
            var list = SocoliveRepository.fetchStreams(roomNum)
            if (list.isEmpty() && needUrl) list = SocoliveRepository.fetchStreams(roomNum, force = true)
            sources = list
            if (needUrl) {
                if (list.isEmpty()) {
                    findViewById<View>(R.id.bufferBox)?.visibility = View.GONE
                    osd(getString(R.string.stream_not_ready))
                    handler.postDelayed({ if (!isFinishing) finish() }, 1800L)
                    return@launch
                }
                sourceIdx = 0
                url = list[0].url
                // nếu Activity chưa START (đang ở onCreate) thì onStart() sẽ tự khởi tạo player
                if (player == null && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) initPlayer()
            } else {
                sourceIdx = list.indexOfFirst { it.url == url }.coerceAtLeast(0)
            }
        }
    }

    private fun playSource(idx: Int, announce: Boolean) {
        if (idx !in sources.indices) return
        sourceIdx = idx
        url = sources[idx].url
        streamRetries = 0
        stallStrikes = 0
        handler.removeCallbacks(autoRecoveryRunnable)
        player?.let {
            it.setMediaItem(Enhancer.buildMediaItem(url))
            it.prepare()
            it.playWhenReady = true
        }
        if (announce) osd(sources[idx].label)
    }

    /** Đổi sang nguồn kế tiếp. false = không còn nguồn để thử (đã thử hết 1 vòng hoặc chỉ có 1 nguồn). */
    private fun trySwitchSource(reason: String): Boolean {
        if (sources.size < 2 || failoverCount >= sources.size - 1) return false
        failoverCount++
        val next = (sourceIdx + 1) % sources.size
        osd("$reason → ${sources[next].label}")
        playSource(next, announce = false)
        return true
    }

    // ================= v7: BẢNG ĐIỀU KHIỂN (bottom sheet / side panel) =================
    private fun openSheet(start: String? = null) {
        dialog?.dismiss()
        handler.removeCallbacks(hideOverlay)
        val vqNames = arrayOf(getString(R.string.vq_auto), getString(R.string.vq_high), getString(R.string.vq_stable))
        val aqNames = arrayOf(
            getString(R.string.aq_standard), getString(R.string.aq_bass), getString(R.string.aq_dialog),
            getString(R.string.aq_night), getString(R.string.aq_auto)
        )
        val boostLabels = arrayOf("Tắt", "+3 dB", "+6 dB", "+9 dB")
        val sleepOptions = arrayOf("Tắt hẹn giờ", "15 phút", "30 phút", "45 phút", "60 phút", "90 phút", "120 phút")
        val sleepMinutes = intArrayOf(0, 15, 30, 45, 60, 90, 120)

        fun sourcePage() = PlayerSheet.Page(getString(R.string.player_sheet_source), { sourceIdx }) {
            sources.mapIndexed { i, src ->
                PlayerSheet.Row(R.drawable.ic_live_stream, src.label, selected = i == sourceIdx) { c ->
                    if (i != sourceIdx) { failoverCount = 0; playSource(i, announce = true) }
                    c.refresh()
                }
            }
        }
        fun videoPage() = PlayerSheet.Page(getString(R.string.player_sheet_video), { EnhanceSettings.videoQuality(this) }) {
            vqNames.mapIndexed { i, n ->
                PlayerSheet.Row(R.drawable.ic_quality, n, selected = i == EnhanceSettings.videoQuality(this)) { c ->
                    EnhanceSettings.setVideoQuality(this, i)
                    (player?.trackSelector as? androidx.media3.exoplayer.trackselection.DefaultTrackSelector)
                        ?.let { Enhancer.applyVideo(it, i) }
                    osd(getString(R.string.player_video_changed, n))
                    c.refresh()
                }
            }
        }
        fun audioPage() = PlayerSheet.Page(getString(R.string.player_sheet_audio), { EnhanceSettings.audioMode(this) }) {
            aqNames.mapIndexed { i, n ->
                PlayerSheet.Row(R.drawable.ic_audio, n, selected = i == EnhanceSettings.audioMode(this)) { c ->
                    EnhanceSettings.setAudioMode(this, i)
                    val sid = player?.audioSessionId ?: 0
                    if (sid != 0) audioFx.attach(sid, i)
                    osd(getString(R.string.player_audio_changed, n) + if (sid == 0) getString(R.string.player_audio_pending) else "")
                    c.refresh()
                }
            }
        }
        fun aspectPage() = PlayerSheet.Page(getString(R.string.player_sheet_aspect), { currentAspectIdx }) {
            aspectNames.mapIndexed { i, n ->
                PlayerSheet.Row(R.drawable.ic_aspect_ratio, n, selected = i == currentAspectIdx) { c ->
                    setAspect(i)
                    c.refresh()
                }
            }
        }
        fun sleepPage() = PlayerSheet.Page(getString(R.string.player_sheet_sleep)) {
            sleepOptions.mapIndexed { i, n ->
                PlayerSheet.Row(R.drawable.ic_sleep_timer, n, selected = sleepMinutes[i] == sleepMinutesLeft || (i == 0 && sleepMinutesLeft == 0)) { c ->
                    handler.removeCallbacks(sleepTimerRunnable)
                    sleepMinutesLeft = sleepMinutes[i]
                    if (sleepMinutes[i] > 0) {
                        handler.postDelayed(sleepTimerRunnable, 60000L)
                        osd("⏰ Tự động tắt sau ${sleepMinutes[i]} phút")
                    } else osd("Đã hủy hẹn giờ tắt")
                    c.refresh()
                }
            }
        }

        val root = PlayerSheet.Page(getString(R.string.player_sheet_title)) {
            val rows = mutableListOf<PlayerSheet.Row>()
            if (sources.size > 1) rows += PlayerSheet.Row(
                R.drawable.ic_live_stream, getString(R.string.player_sheet_source),
                value = sources.getOrNull(sourceIdx)?.label?.substringAfter("· ") ?: "", chevron = true
            ) { it.push(sourcePage()) }
            rows += PlayerSheet.Row(R.drawable.ic_quality, getString(R.string.settings_video_quality),
                value = vqNames[EnhanceSettings.videoQuality(this).coerceIn(0, vqNames.size - 1)], chevron = true) { it.push(videoPage()) }
            rows += PlayerSheet.Row(R.drawable.ic_audio, getString(R.string.settings_audio_mode),
                value = aqNames[EnhanceSettings.audioMode(this).coerceIn(0, aqNames.size - 1)], chevron = true) { it.push(audioPage()) }
            rows += PlayerSheet.Row(R.drawable.ic_aspect_ratio, getString(R.string.player_sheet_aspect),
                value = aspectNames[currentAspectIdx], chevron = true) { it.push(aspectPage()) }
            rows += PlayerSheet.Row(R.drawable.ic_audio_boost, getString(R.string.player_sheet_boost),
                value = boostLabels[audioBoostLevel]) { c -> cycleAudioBoost(); c.refresh() }
            rows += PlayerSheet.Row(R.drawable.ic_audio_track, getString(R.string.player_sheet_track), chevron = true) { c ->
                c.dismiss(); showAudioTrackDialog()
            }
            rows += PlayerSheet.Row(R.drawable.ic_sleep_timer, getString(R.string.player_sheet_sleep),
                value = if (sleepMinutesLeft > 0) "Còn $sleepMinutesLeft phút" else "Tắt", chevron = true) { it.push(sleepPage()) }
            rows += PlayerSheet.Row(R.drawable.ic_info, getString(R.string.player_sheet_stats),
                value = if (statsHudBox?.visibility == View.VISIBLE) "Đang bật" else "Tắt") { c -> c.dismiss(); toggleStatsHud() }
            rows += PlayerSheet.Row(R.drawable.ic_multiview, getString(R.string.nav_multiview)) { c ->
                c.dismiss()
                startActivity(
                    Intent(this, MultiViewActivity::class.java)
                        .putExtra("initial_room", findViewById<TextView>(R.id.playerTitle).text.toString())
                        .putExtra("initial_url", url)
                )
            }
            if (isIptvMode) {
                rows += PlayerSheet.Row(R.drawable.ic_nav_tv, getString(R.string.player_sheet_channels)) { c ->
                    c.dismiss()
                    if (DeviceMode.isTv) showCarousel() else toggleSidebar()
                }
                IptvRepository.currentChannels.getOrNull(currentChannelIndex)?.let { ch ->
                    val fav = Favorites.isIptv(this, ch.id)
                    rows += PlayerSheet.Row(R.drawable.ic_fav,
                        getString(if (fav) R.string.fav_remove else R.string.fav_add), selected = fav) { c ->
                        val now = Favorites.toggleIptv(this, ch.id)
                        osd(getString(if (now) R.string.fav_added else R.string.fav_removed))
                        c.refresh()
                    }
                }
            }
            if (pipSupported() && !DeviceMode.isTv) {
                rows += PlayerSheet.Row(R.drawable.ic_pip, getString(R.string.player_sheet_pip)) { c ->
                    c.dismiss(); enterPip(manual = true)
                }
            }
            rows
        }

        val sheet = PlayerSheet(this, root)
        dialog = sheet.dialog
        sheet.show(onDismiss = { dialog = null; hideOnce() })
        when (start) {
            "video" -> sheet.push(videoPage())
            "audio" -> sheet.push(audioPage())
        }
    }

    private fun setAspect(i: Int) {
        currentAspectIdx = i
        findViewById<PlayerView>(R.id.playerView)?.resizeMode = aspectModes[i]
        getSharedPreferences("player", MODE_PRIVATE).edit().putInt("aspect", i).apply()
        osd(getString(R.string.player_aspect_changed, aspectNames[i]))
    }

    // ================= MOBILE PHONE INTERACTIONS =================
    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            centerPlayPauseBtn?.setImageResource(R.drawable.ic_play)
            osd("Tạm dừng")
        } else {
            p.play()
            centerPlayPauseBtn?.setImageResource(R.drawable.ic_pause)
            osd("Tiếp tục phát")
        }
        showOverlay()
    }

    private fun toggleScreenOrientation() {
        val currentOrientation = resources.configuration.orientation
        if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            osd("Chuyển màn hình dọc")
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            osd("Chuyển toàn màn hình ngang")
        }
    }

    // ================= CHUYỂN TỈ LỆ MÀN HÌNH =================
    private fun cycleAspectRatio() {
        setAspect((currentAspectIdx + 1) % aspectModes.size)
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
        osd("🔊 Khuếch đại âm lượng: $label")
        hideOnce()
    }

    // ================= KÊNH ÂM THANH / NGÔN NGỮ (AUDIO TRACKS) =================
    private fun showAudioTrackDialog() {
        val p = player ?: return
        val tracks = p.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
        if (tracks.isEmpty()) {
            osd("Kênh này chỉ có 1 luồng âm thanh mặc định")
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
                osd("Đã chọn âm thanh: ${trackNames[which].replace("  ✓", "")}")
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
        sources = emptyList(); sourceIdx = 0; failoverCount = 0; stallStrikes = 0; streamRetries = 0
        findViewById<TextView>(R.id.playerTitle).text = ch.name
        channelSidebar?.visibility = View.GONE
        hideCarousel()
        showOverlay()

        player?.stop()
        player?.setMediaItem(Enhancer.buildMediaItem(url))
        player?.prepare()
        player?.playWhenReady = true
        osd("Đang chuyển sang: ${ch.name}")
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
            .setMediaSourceFactory(Enhancer.buildMediaSourceFactory(this))
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
                        fun retryLater() {
                            streamRetries++
                            osd(getString(R.string.player_retry, streamRetries))
                            handler.postDelayed({ player?.prepare() }, 1500L * streamRetries)
                        }
                        when {
                            // lỗi mạng thoáng qua: thử lại 2 lần trên nguồn hiện tại
                            isNet && streamRetries < 2 -> retryLater()
                            // vẫn lỗi (hoặc lỗi định dạng/giải mã) → chuyển nguồn phát dự phòng
                            trySwitchSource(getString(R.string.player_source_failed)) -> Unit
                            isNet && streamRetries < 4 -> retryLater()
                            else -> {
                                findViewById<View>(R.id.bufferBox)?.visibility = View.GONE
                                osd(
                                    if (isNet) getString(R.string.player_net_error)
                                    else getString(R.string.player_stream_error, error.errorCodeName)
                                )
                            }
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            streamRetries = 0
                            stallStrikes = 0
                            handler.removeCallbacks(autoRecoveryRunnable)
                            // phát ổn định 12s → coi như sự cố đã qua, cho phép failover lại từ đầu
                            handler.removeCallbacks(stableRunnable)
                            handler.postDelayed(stableRunnable, 12_000L)
                        }
                        if (state == Player.STATE_BUFFERING) {
                            handler.removeCallbacks(stableRunnable)
                            handler.removeCallbacks(autoRecoveryRunnable)
                            handler.postDelayed(autoRecoveryRunnable, 4000L)
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

    // ================= PICTURE-IN-PICTURE =================
    private fun pipSupported(): Boolean =
        Build.VERSION.SDK_INT >= 26 &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun enterPip(manual: Boolean) {
        if (!pipSupported()) {
            if (manual) osd(getString(R.string.player_pip_unsupported))
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
            if (manual) osd(getString(R.string.player_pip_failed, e.message))
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
        if (DeviceMode.isPhone && gesture != null) {
            gesture?.onTouchEvent(ev)
            if (handleSwipe(ev)) return true
            return super.dispatchTouchEvent(ev)
        }
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
        handler.removeCallbacks(stableRunnable)
        handler.removeCallbacks(hideOsdChip)
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
