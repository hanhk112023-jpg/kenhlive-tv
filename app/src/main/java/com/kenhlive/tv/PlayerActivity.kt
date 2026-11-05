package com.kenhlive.tv

import android.app.PictureInPictureParams
import android.content.Intent
import android.view.GestureDetector
import android.media.AudioManager
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.SeekBar
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
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil.load
import com.kenhlive.tv.iptv.EpgRepository
import com.kenhlive.tv.iptv.IptvChannel
import com.kenhlive.tv.iptv.IptvRepository
import com.kenhlive.tv.iptv.Tv360Resolver
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
/**
 * Cài đặt trình phát dùng chung: PlayerActivity + WebPlayerActivity đọc,
 * màn Cài đặt (SettingsFragment) ghi. Lưu trong SharedPreferences "player_prefs".
 */
object PlayerPrefs {
    const val PREFS = "player_prefs"
    const val KEY_SEEK_STEP_SEC = "seek_step_sec"
    const val DEFAULT_SEEK_STEP_SEC = 10
    val SEEK_STEP_OPTIONS_SEC = intArrayOf(10, 20, 30)

    fun seekStepSec(ctx: android.content.Context): Int {
        val v = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getInt(KEY_SEEK_STEP_SEC, DEFAULT_SEEK_STEP_SEC)
        return if (v in SEEK_STEP_OPTIONS_SEC) v else DEFAULT_SEEK_STEP_SEC
    }

    fun setSeekStepSec(ctx: android.content.Context, sec: Int) {
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putInt(KEY_SEEK_STEP_SEC, sec).apply()
    }
}

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null

    companion object {
        private const val PREFS_PLAYER = "player_prefs"
        private const val KEY_ASPECT_IDX = "aspect_idx"
        // Độ trễ chốt seek khi scrub bằng D-pad trên seekbar (chờ lần bấm cuối)
        private const val SCRUB_COMMIT_DELAY_MS = 500L
    }
    private val handler = Handler(Looper.getMainLooper())
    private var topOverlay: View? = null
    private var centerPlayPauseBtn: ImageButton? = null
    private var gestureFeedbackBox: View? = null
    private var gestureIcon: ImageView? = null
    private var gestureProgress: ProgressBar? = null
    private var gestureText: TextView? = null
    private var gestureDetector: GestureDetector? = null
    private var audioManager: AudioManager? = null
    private val hideGestureRunnable = Runnable {
        gestureFeedbackBox?.visibility = View.GONE
    }
    private var phoneBottomBar: View? = null
    // Thanh tua VOD trên điện thoại (chỉ hiện khi xem phim + overlay đang mở)
    private var phoneSeekRow: View? = null
    private var seekVod: SeekBar? = null
    private var tvSeekCurrent: TextView? = null
    private var tvSeekTotal: TextView? = null
    private var seekDragging: Boolean = false
    // Đang kéo bằng cảm ứng (phone) — phân biệt với scrub bằng D-pad trên TV: D-pad chỉ
    // bắn onProgressChanged(fromUser=true), KHÔNG có start/stop tracking, nên phải chốt
    // seek bằng debounce; cảm ứng thì chốt ở onStopTrackingTouch như cũ.
    private var seekTouchTracking: Boolean = false
    private var pendingScrubTargetMs: Long = -1L
    private val scrubCommitRunnable = Runnable {
        if (seekTouchTracking) return@Runnable
        val target = pendingScrubTargetMs
        seekDragging = false
        pendingScrubTargetMs = -1L
        if (target >= 0L) {
            noteUserSeek(target)
            player?.seekTo(target)
            tvSeekCurrent?.text = fmtPlaybackTime(target)
        }
        hideOnce()
    }
    // Guard chống "tua tới giật lại" trên máy yếu: sau seekTo(), player còn báo vị trí CŨ
    // một lúc (đang buffering). Ghi lại đích + thời điểm của lần tua do người dùng để
    // (a) lần tua tiếp theo lấy đích cũ làm gốc thay vì vị trí cũ player báo về,
    // (b) runnable cập nhật thanh tua không kéo núm ngược về vị trí cũ trong lúc chờ.
    private var lastSeekTargetMs: Long = -1L
    private var lastSeekAtMs: Long = 0L
    // Thanh điều khiển VOD trên TV (hiện cùng overlay khi bấm OK)
    private var vodControlBar: View? = null
    private var btnBarSettings: Button? = null
    private var btnBarPrevEp: Button? = null
    private var btnBarSeekBack: Button? = null
    private var btnBarPlayPause: Button? = null
    private var btnBarSeekFwd: Button? = null
    private var btnBarNextEp: Button? = null
    private var btnBarEpisodes: Button? = null
    private val seekUpdateRunnable = object : Runnable {
        override fun run() {
            val row = phoneSeekRow ?: return
            val p = player ?: return
            if (row.visibility != View.VISIBLE) return
            if (!seekDragging) {
                val dur = p.duration
                var pos = p.currentPosition.coerceAtLeast(0L)
                // Guard chống giật ngược: trong 2s sau lần tua của người dùng, nếu player
                // vẫn báo vị trí xa đích (>1.5s) thì giữ hiển thị tại đích đã chọn — không
                // kéo núm seek ngược. Player bắt kịp (|pos-đích| <= 1.5s) hoặc hết cửa sổ
                // thì hiển thị vị trí thật và xoá guard để không ảnh hưởng lần sau.
                if (lastSeekTargetMs >= 0L) {
                    val inWindow = android.os.SystemClock.uptimeMillis() - lastSeekAtMs < 2000L
                    if (inWindow && kotlin.math.abs(pos - lastSeekTargetMs) > 1500L) {
                        pos = lastSeekTargetMs
                    } else {
                        lastSeekTargetMs = -1L
                    }
                }
                if (dur > 0L) {
                    seekVod?.max = (dur / 1000L).toInt().coerceAtLeast(1)
                    seekVod?.progress = (pos / 1000L).toInt()
                    tvSeekTotal?.text = fmtPlaybackTime(dur)
                }
                tvSeekCurrent?.text = fmtPlaybackTime(pos)
            }
            handler.postDelayed(this, 500L)
        }
    }
    private var phoneUnlockContainer: View? = null
    private var isScreenLocked: Boolean = false
    private val hideUnlockBtnTask = Runnable {
        phoneUnlockContainer?.visibility = View.GONE
    }
    private val hideOverlay = Runnable {
        topOverlay?.visibility = View.GONE
        centerPlayPauseBtn?.visibility = View.GONE
        phoneBottomBar?.visibility = View.GONE
        phoneSeekRow?.visibility = View.GONE
        vodControlBar?.visibility = View.GONE
    }
    private val audioFx = AudioEnhancer(this)
    private var url: String = ""
    private var streamRetries = 0
    private var dialog: AlertDialog? = null

    // Sports Multi-Source State (Socolive / ColaTV / Gà Vàng / Khán Đài)
    private var currentRoomNum: String = ""
    private var currentSportsSource: com.kenhlive.tv.sports.SportsSource = com.kenhlive.tv.sports.SportsSource.SOCOLIVE
    private var sportsFailoverCount: Int = 0
    private var iptvFailoverCount: Int = 0

    // Danh sách kênh nhanh (Sidebar & Chuyển kênh D-pad)
    private var channelSidebar: View? = null
    private var sidebarList: RecyclerView? = null
    private var isIptvMode: Boolean = false
    private var currentChannelIndex: Int = 0
    private var pendingChannelIndex: Int = -1

    // VOD Cinema State (Direct HLS 4K)
    private var isVodMode: Boolean = false
    private var filmSlug: String = ""
    private var filmTitle: String = ""
    private var episodeSlug: String = ""
    private var episodeTitle: String = ""
    private var posterUrl: String = ""
    private var startPositionMs: Long = 0L
    private var hasSeekedToStart: Boolean = false
    private var autoNextBusy: Boolean = false
    // Server đang phát (để auto-next chỉ nhảy tập kế của CÙNG server). Nơi mở có thể truyền
    // extra "server_name"; nếu không có, player tự nhận diện bằng URL luồng đang phát.
    private var currentServerName: String = ""
    // Vị trí startPositionMs hiện tại là do app xuống nền giữa chừng (không phải mở từ lịch sử):
    // trường hợp này luôn seek lại đúng chỗ, kể cả khi đã qua 90% (xem nhánh STATE_READY).
    private var positionFromBackground: Boolean = false

    // Quick Channel OSD Banner
    private var quickChannelOsd: View? = null
    private var osdChannelNumber: TextView? = null
    private var osdChannelLogo: ImageView? = null
    private var osdChannelTitle: TextView? = null
    private var osdEpgNow: TextView? = null
    private var osdEpgProgress: ProgressBar? = null
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
            val list = IptvRepository.currentChannels
            val targetIdx = (keypadAccumulator - 1).coerceIn(0, (list.size - 1).coerceAtLeast(0))
            showQuickOsd(targetIdx)
            keypadAccumulator = 0
        }
    }

    private fun showKeypadFeedback(num: Int) {
        topOverlay?.visibility = View.GONE
        quickChannelOsd?.visibility = View.VISIBLE
        osdChannelNumber?.text = String.format(Locale.US, "%02d", num)
        osdChannelTitle?.text = "Kênh số $num • Bấm OK để chuyển ngay"
        osdEpgNow?.visibility = View.GONE
        osdEpgProgress?.visibility = View.GONE
        osdEpgNext?.visibility = View.GONE
    }

    // Tự động phục hồi luồng khi bị kẹt buffering hoặc drop kết nối (Auto Stream Failover)
    private val autoRecoveryRunnable = Runnable {
        val p = player ?: return@Runnable
        if (p.playbackState == Player.STATE_BUFFERING) {
            if (isVodMode) {
                // Với phim VOD: Giữ nguyên vị trí hiện tại, chuẩn bị lại luồng, TUYỆT ĐỐI không reset về 00:00!
                val curPos = p.currentPosition
                p.prepare()
                p.seekTo(curPos)
                p.play()
            } else {
                Toast.makeText(this@PlayerActivity, "Mạng chập chờn: Đang tự kết nối lại luồng...", Toast.LENGTH_SHORT).show()
                // Reset về Live edge (đầu luồng phát mới nhất) và nạp lại
                p.seekToDefaultPosition()
                p.prepare()
                p.play()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceMode.updateMode(this)
        applyTvDensity()
        setContentView(R.layout.activity_player)

        url = intent.getStringExtra("url") ?: ""
        val name = intent.getStringExtra("name") ?: getString(R.string.player_default_name)
        isIptvMode = intent.getBooleanExtra("is_iptv", false)
        currentChannelIndex = intent.getIntExtra("current_index", 0)
        currentRoomNum = intent.getStringExtra("roomNum") ?: ""
        currentSportsSource = com.kenhlive.tv.sports.SportsSource.detectSource(currentRoomNum)

        // Read VOD Extras
        filmSlug = intent.getStringExtra("film_slug").orEmpty()
        filmTitle = intent.getStringExtra("film_title").orEmpty()
        episodeSlug = intent.getStringExtra("episode_slug").orEmpty()
        episodeTitle = intent.getStringExtra("episode_title").orEmpty()
        posterUrl = intent.getStringExtra("poster_url").orEmpty()
        currentServerName = intent.getStringExtra("server_name").orEmpty()
        startPositionMs = intent.getLongExtra("start_position_ms", 0L)
        if (filmSlug.isNotBlank()) {
            isVodMode = true
        }

        // v7.9: khôi phục tỉ lệ khung hình người dùng đã chọn lần trước
        currentAspectIdx = getSharedPreferences(PREFS_PLAYER, MODE_PRIVATE)
            .getInt(KEY_ASPECT_IDX, 0).coerceIn(aspectModes.indices)

        topOverlay = findViewById(R.id.topOverlay)
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
        osdEpgProgress = findViewById(R.id.osdEpgProgress)
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
        gestureFeedbackBox = findViewById(R.id.gestureFeedbackBox)
        gestureIcon = findViewById(R.id.gestureIcon)
        gestureProgress = findViewById(R.id.gestureProgress)
        gestureText = findViewById(R.id.gestureText)
        audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager

        if (DeviceMode.isPhone) {
            setupMobileGestures()
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
            phoneUnlockContainer = findViewById(R.id.phoneUnlockContainer)
            findViewById<ImageButton>(R.id.phoneLockBtn)?.setOnClickListener { setScreenLock(true) }
            phoneUnlockContainer?.setOnClickListener { setScreenLock(false) }

            // Thanh tua VOD được nối ở wireVodSeekBar() phía dưới (dùng chung phone + TV)
        } else {
            // Trên TV: thanh trên chỉ còn Quay lại + Danh sách kênh (IPTV) + 1 nút
            // Tùy chọn phát. Các nút chi tiết (chất lượng, âm thanh, tỉ lệ, hẹn giờ,
            // kênh âm thanh, tăng âm, thống kê) gom vào menu showTvMoreOptions(),
            // theo đúng mẫu menu đã có trên điện thoại, để remote không phải đi
            // qua 9-10 icon không chữ.
            phoneMenuBtn?.visibility = View.VISIBLE
            phoneMenuBtn?.setOnClickListener { showTvMoreOptions() }
            phoneBottomBar?.visibility = View.GONE
            centerPlayPauseBtn?.visibility = View.GONE

            if (isIptvMode) {
                channelListBtn.visibility = View.VISIBLE
                channelListBtn.setOnClickListener { showCarousel() }
                setupSidebar()
                setupChannelCarousel()
            }
            // TV: nút Play/Pause giữa màn hình cho phim VOD (trước đây bị ẩn hẳn trên TV)
            centerPlayPauseBtn?.setOnClickListener { togglePlayPause() }
        }

        if (isVodMode && !DeviceMode.isPhone) {
            // TV + VOD: gom các nút chỉnh thông số rời rạc trên topOverlay (Chất lượng /
            // Âm thanh / Tỷ lệ / Kênh âm thanh) vào nút Cài Đặt trong thanh điều khiển
            // VOD -> ẩn khỏi topOverlay cho thoáng màn hình. Hẹn giờ / Tăng âm /
            // Thống kê cũng ẩn khỏi thanh trên và chuyển vào cùng menu Cài Đặt đó
            // (showVodSettingsDialog), nên thanh trên VOD chỉ còn Quay lại + tiêu đề.
            // Phone giữ nguyên như cũ vì thanh điều khiển VOD (nơi chứa nút Cài Đặt)
            // chỉ có trên TV.
            qualityBtn.visibility = View.GONE
            audioBtn?.visibility = View.GONE
            aspectBtn?.visibility = View.GONE
            audioTrackBtn?.visibility = View.GONE
            sleepBtn?.visibility = View.GONE
            audioBoostBtn?.visibility = View.GONE
            statsBtn?.visibility = View.GONE
            // VOD không cần nút menu trên thanh trên nữa: Hẹn giờ / Tăng âm /
            // Thống kê đã vào menu Cài Đặt của thanh dưới, thanh trên VOD chỉ
            // còn Quay lại + tiêu đề.
            phoneMenuBtn?.visibility = View.GONE
        }

        if (!isVodMode && !DeviceMode.isPhone) {
            // TV + Live (thể thao / IPTV): gom toàn bộ nút chi tiết trên topOverlay
            // vào 1 nút Tùy chọn phát ở trên. Thanh trên live chỉ còn Quay lại +
            // Danh sách kênh (IPTV) + Tùy chọn phát.
            qualityBtn.visibility = View.GONE
            audioBtn?.visibility = View.GONE
            aspectBtn?.visibility = View.GONE
            sleepBtn?.visibility = View.GONE
            audioTrackBtn?.visibility = View.GONE
            audioBoostBtn?.visibility = View.GONE
            statsBtn?.visibility = View.GONE
        }

        // Thanh tua VOD dùng chung cho cả phone và TV
        wireVodSeekBar()

        // Thanh điều khiển VOD trên TV (bấm OK để mở): Cài Đặt / Tập trước / Tua / Phát / Tập tiếp / Danh sách tập
        wireVodControlBar()

        initPlayer()
        val pv = findViewById<PlayerView>(R.id.playerView)

        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnPlayerRetry)?.setOnClickListener {
            findViewById<View>(R.id.playerErrorBox)?.visibility = View.GONE
            findViewById<View>(R.id.bufferBox)?.visibility = View.VISIBLE
            streamRetries = 0
            if (isVodMode) {
                // Nạp lại luồng nhưng giữ nguyên vị trí đang xem dở (tránh giật về đầu phim)
                prepareChannelUrl(url, keepPosition = true)
            } else {
                player?.prepare()
            }
        }
        findViewById<Button>(R.id.btnPlayerBack)?.setOnClickListener {
            finish()
        }
        findViewById<Button>(R.id.btnPlayerReport)?.setOnClickListener {
            com.kenhlive.tv.ui.FeedbackDialog.show(this@PlayerActivity)
        }
        qualityBtn.setOnClickListener { showSettingsDialog() }
        audioBtn.setOnClickListener { showSettingsDialog() }
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
        multiBtn.visibility = View.GONE
        if (intent.getBooleanExtra("pip", false)) pv.post { enterPip(manual = false) }

        showOverlay()
    }

    // ================= MOBILE GESTURES & INTERACTIONS =================
    private var isDraggingGesture = false
    private var isBrightnessGesture = false

    private fun setupMobileGestures() {
        val pv = findViewById<PlayerView>(R.id.playerView) ?: return
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isScreenLocked) {
                    val unlock = phoneUnlockContainer ?: return true
                    if (unlock.visibility == View.VISIBLE) {
                        unlock.visibility = View.GONE
                        handler.removeCallbacks(hideUnlockBtnTask)
                    } else {
                        unlock.visibility = View.VISIBLE
                        unlock.alpha = 1f
                        handler.removeCallbacks(hideUnlockBtnTask)
                        handler.postDelayed(hideUnlockBtnTask, 3000L)
                    }
                    return true
                }
                if (topOverlay?.visibility == View.VISIBLE) {
                    hideOverlay.run()
                } else {
                    showOverlay()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isScreenLocked) return true
                val screenW = resources.displayMetrics.widthPixels
                val p = player ?: return false
                if (e.x < screenW / 2f) {
                    // Chạm đúp bên trái: Tua lùi theo bước đã cài đặt (mặc định 10s)
                    val target = (guardedSeekBase(p) - seekStepMs()).coerceAtLeast(0L)
                    noteUserSeek(target)
                    p.seekTo(target)
                    showGestureFeedback(R.drawable.ic_back, 0, "-${seekStepMs() / 1000}s")
                } else {
                    // Chạm đúp bên phải: Phim VOD tua tới theo bước cài đặt; luồng LIVE nhảy tới Live Edge.
                    // (Trước đây VOD cũng seekToDefaultPosition() = quay về 00:00, mất vị trí đang xem.)
                    if (isVodMode || !p.isCurrentMediaItemLive) {
                        val dur = p.duration
                        val base = guardedSeekBase(p)
                        val target = if (dur > 0L) {
                            (base + seekStepMs()).coerceAtMost(dur)
                        } else {
                            base + seekStepMs()
                        }
                        noteUserSeek(target)
                        p.seekTo(target)
                        showGestureFeedback(R.drawable.kl_p_play, 100, "+${seekStepMs() / 1000}s")
                    } else {
                        p.seekToDefaultPosition()
                        showGestureFeedback(R.drawable.kl_p_play, 100, "TRỰC TIẾP")
                    }
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (isScreenLocked || e1 == null) return false
                val screenW = resources.displayMetrics.widthPixels
                val screenH = resources.displayMetrics.heightPixels

                if (!isDraggingGesture) {
                    if (Math.abs(distanceY) > Math.abs(distanceX)) {
                        isDraggingGesture = true
                        isBrightnessGesture = e1.x < screenW / 2f
                    } else {
                        return false
                    }
                }

                val deltaPercent = (distanceY / screenH.toFloat()) * 1.5f
                if (isBrightnessGesture) {
                    adjustBrightness(deltaPercent)
                } else {
                    adjustVolume(deltaPercent)
                }
                return true
            }
        })

        pv.setOnTouchListener { _, event ->
            val handled = gestureDetector?.onTouchEvent(event) ?: false
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                isDraggingGesture = false
            }
            handled
        }
    }

    private fun setScreenLock(locked: Boolean) {
        isScreenLocked = locked
        if (locked) {
            hideOverlay.run()
            phoneUnlockContainer?.visibility = View.VISIBLE
            phoneUnlockContainer?.alpha = 1f
            handler.removeCallbacks(hideUnlockBtnTask)
            handler.postDelayed(hideUnlockBtnTask, 3000L)
            Toast.makeText(this, "Đã khóa màn hình cảm ứng", Toast.LENGTH_SHORT).show()
        } else {
            phoneUnlockContainer?.visibility = View.GONE
            handler.removeCallbacks(hideUnlockBtnTask)
            Toast.makeText(this, "Đã mở khóa màn hình", Toast.LENGTH_SHORT).show()
            showOverlay()
        }
    }

    private fun adjustBrightness(delta: Float) {
        val lp = window.attributes
        var current = lp.screenBrightness
        if (current < 0) current = 0.5f
        val newBrightness = (current + delta).coerceIn(0.01f, 1.0f)
        lp.screenBrightness = newBrightness
        window.attributes = lp
        val percent = (newBrightness * 100).toInt()
        showGestureFeedback(R.drawable.ic_brightness, percent, "$percent%")
    }

    private fun adjustVolume(delta: Float) {
        val am = audioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val step = if (delta > 0) 1 else -1
        val newVol = (cur + step).coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
        val percent = (newVol * 100 / max.toFloat()).toInt()
        showGestureFeedback(R.drawable.ic_volume, percent, "$percent%")
    }

    private fun showGestureFeedback(iconRes: Int, progress: Int, text: String) {
        gestureFeedbackBox?.visibility = View.VISIBLE
        gestureIcon?.setImageResource(iconRes)
        gestureProgress?.progress = progress
        gestureText?.text = text
        handler.removeCallbacks(hideGestureRunnable)
        handler.postDelayed(hideGestureRunnable, 1000L)
    }

    private fun fmtPlaybackTime(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSec = ms / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            centerPlayPauseBtn?.setImageResource(R.drawable.kl_p_play)
            Toast.makeText(this, "Tạm dừng", Toast.LENGTH_SHORT).show()
        } else {
            p.play()
            centerPlayPauseBtn?.setImageResource(R.drawable.kl_p_pause)
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
        actions.add { showSettingsDialog() }

        // Đổi server cho chế độ thể thao
        if (!isIptvMode) {
            options.add("Đổi nguồn phát (${currentSportsSource.displayName})")
            actions.add { showServerPickerDialog() }
        }

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

        // 7. Danh sách kênh (nếu đang ở IPTV)
        if (isIptvMode) {
            options.add("Mở danh sách kênh")
            actions.add { toggleSidebar() }
        }

        val d = AlertDialog.Builder(this, R.style.Theme_KenhLive_Dialog)
            .setTitle("Tùy Chọn Phát")
            .setItems(options.toTypedArray()) { d, which ->
                d.dismiss()
                actions[which].invoke()
            }
            .setNegativeButton(R.string.dialog_close, null)
            .create()
        d.show()
        d.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        hideOnce()
    }

    /**
     * Menu Tùy chọn phát cho TV (live): 1 nút duy nhất trên thanh trên, thay cho
     * 7 nút icon rời rạc trước đây. Mỗi mục gọi ĐÚNG handler của nút cũ, có ghi
     * giá trị hiện tại trong nhãn (tỉ lệ, chế độ âm thanh, mức tăng âm, hẹn giờ)
     * để nhìn từ xa vẫn biết đang ở trạng thái nào, như menu điện thoại đã làm.
     * Danh sách kênh không đưa vào đây vì nó đã là nút riêng trên thanh trên
     * ở chế độ IPTV; Đổi nguồn chỉ có ở thể thao (không phải IPTV).
     */
    private fun showTvMoreOptions() {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        options.add("Chất lượng")
        actions.add { showSettingsDialog() }

        options.add("Tỉ lệ: ${aspectNames[currentAspectIdx]}")
        actions.add { cycleAspectRatio() }

        val aqNames = arrayOf(
            getString(R.string.aq_standard), getString(R.string.aq_bass), getString(R.string.aq_dialog),
            getString(R.string.aq_night), getString(R.string.aq_auto)
        )
        val curAq = EnhanceSettings.audioMode(this).coerceIn(0, aqNames.size - 1)
        options.add("Âm thanh: ${aqNames[curAq]}")
        actions.add { showSettingsDialog() }

        val boostLabels = arrayOf("Tắt", "+3dB", "+6dB", "+9dB")
        options.add("Khuếch đại âm lượng: ${boostLabels[audioBoostLevel]}")
        actions.add { cycleAudioBoost() }

        options.add("Kênh âm thanh (Ngôn ngữ)")
        actions.add { showAudioTrackDialog() }

        val timerLabel = if (sleepMinutesLeft > 0) "Còn $sleepMinutesLeft phút" else "Chưa đặt"
        options.add("Hẹn giờ tắt ($timerLabel)")
        actions.add { showSleepTimerDialog() }

        options.add("Thông số kỹ thuật (Stats)")
        actions.add { toggleStatsHud() }

        if (!isIptvMode) {
            options.add("Đổi nguồn phát (${currentSportsSource.displayName})")
            actions.add { showServerPickerDialog() }
        }

        val d = AlertDialog.Builder(this, R.style.Theme_KenhLive_Dialog)
            .setTitle("Tùy Chọn Phát")
            .setItems(options.toTypedArray()) { d, which ->
                d.dismiss()
                actions[which].invoke()
            }
            .setNegativeButton(R.string.dialog_close, null)
            .create()
        d.show()
        d.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.72).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        hideOnce()
    }

    // ================= ĐỔI MÁY CHỦ / NGUỒN PHÁT THỂ THAO =================
    private fun showServerPickerDialog() {
        val baseRoom = if (currentRoomNum.isNotBlank()) currentRoomNum else "live"
        val servers = com.kenhlive.tv.sports.SportsAggregator.getAvailableServers(baseRoom)
        val serverNames = servers.map { s ->
            val isCurrent = s.source == currentSportsSource
            "${s.name}" + (if (isCurrent) "  ✓ (Đang phát)" else "")
        }.toTypedArray()

        AlertDialog.Builder(this, R.style.Theme_KenhLive_Dialog)
            .setTitle("📡 Chọn Máy Chủ / Nguồn Phát")
            .setItems(serverNames) { d, which ->
                d.dismiss()
                val selected = servers[which]
                switchSportsServer(selected)
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun switchSportsServer(server: com.kenhlive.tv.sports.SportsServer) {
        if (server.source == currentSportsSource && player?.isPlaying == true) {
            Toast.makeText(this, "Đang phát từ ${server.source.displayName}", Toast.LENGTH_SHORT).show()
            return
        }
        currentSportsSource = server.source
        Toast.makeText(this, "Đang kết nối ${server.name}...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val newUrl = com.kenhlive.tv.sports.SportsStreamResolver.resolveStream(server.roomNum)
            if (!newUrl.isNullOrBlank()) {
                url = newUrl
                prepareChannelUrl(newUrl)
                osdTechTag?.text = "${server.source.shortTag} · HD"
                osdTechTag?.visibility = View.VISIBLE
                Toast.makeText(this@PlayerActivity, "Đã chuyển sang ${server.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@PlayerActivity, "Nguồn ${server.source.displayName} chưa sẵn sàng, đang giữ nguồn hiện tại", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ================= CHUYỂN TỈ LỆ MÀN HÌNH =================
    private fun cycleAspectRatio() {
        val pv = findViewById<PlayerView>(R.id.playerView) ?: return
        currentAspectIdx = (currentAspectIdx + 1) % aspectModes.size
        pv.resizeMode = aspectModes[currentAspectIdx]
        getSharedPreferences(PREFS_PLAYER, MODE_PRIVATE).edit().putInt(KEY_ASPECT_IDX, currentAspectIdx).apply()
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
                // Chỉ hiện số liệu THẬT lấy từ format của luồng; chưa có format thì để "—"
                // (trước đây bịa "1920 x 1080 (HD)" / "50.0 fps" / "3,850 kbps" / "H.264 / AAC"
                // hiển thị như số đo thật — audit L1, nhóm dữ liệu bịa).
                val res = if (f != null && f.width > 0) "${f.width} x ${f.height}" else "—"
                val fps = if (f != null && f.frameRate > 0) String.format(Locale.US, "%.1f fps", f.frameRate) else "—"
                val br = if (f != null && f.bitrate > 0) String.format(Locale.US, "%,d kbps", f.bitrate / 1000) else "—"
                val codec = (f?.sampleMimeType?.substringAfter('/')?.uppercase(Locale.US) ?: "—") + " / " +
                        (p.audioFormat?.sampleMimeType?.substringAfter('/')?.uppercase(Locale.US) ?: "—")
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
        quickChannelOsd?.visibility = View.VISIBLE

        osdChannelNumber?.text = String.format(Locale.US, "%02d", safeIndex + 1)
        osdChannelTitle?.text = ch.name
        if (ch.logo.isNotEmpty()) {
            osdChannelLogo?.load(ch.logo) {
                crossfade(true)
                error(R.drawable.kl_p_channels)
            }
        } else {
            osdChannelLogo?.setImageResource(R.drawable.kl_p_channels)
        }

        val epg = EpgRepository.getCurrentAndNext(ch.id, ch.name)
        if (epg != null && epg.first != null) {
            val cur = epg.first!!
            osdEpgNow?.visibility = View.VISIBLE
            osdEpgNow?.text = "[${cur.timeRange()}] ${cur.title}"
            val pct = cur.progressPercent()
            osdEpgProgress?.visibility = View.VISIBLE
            osdEpgProgress?.progress = pct

            if (epg.second != null) {
                val nxt = epg.second!!
                osdEpgNext?.visibility = View.VISIBLE
                osdEpgNext?.text = "Tiếp theo: [${nxt.startFormatted()}] ${nxt.title}"
            } else {
                osdEpgNext?.visibility = View.GONE
            }
        } else {
            osdEpgNow?.visibility = View.VISIBLE
            osdEpgNow?.text = if (ch.isVn) "Truyền hình Việt Nam trực tiếp" else "${ch.group} trực tiếp"
            osdEpgProgress?.visibility = View.GONE
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
                        error(R.drawable.kl_p_channels)
                        placeholder(R.drawable.kl_p_channels)
                    }
                } else {
                    holder.ivLogo.setImageResource(R.drawable.kl_p_channels)
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

    /**
     * v7.9: Tự chuyển tập kế tiếp khi xem hết một tập phim (chỉ khi tập sau có luồng m3u8 trực tiếp;
     * tập dạng embed thuần sẽ giữ nguyên màn hình kết thúc như trước).
     */
    private fun tryAutoNextEpisode() {
        if (autoNextBusy || filmSlug.isBlank() || episodeSlug.isBlank()) return
        autoNextBusy = true
        lifecycleScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    com.kenhlive.tv.phim.NguoncRepository.fetchFilmDetail(filmSlug, this@PlayerActivity)
                }
                // v7.13: CHỈ nhảy sang tập kế của CÙNG server đang phát (slug tập trùng giữa
                // các server Vietsub/Thuyết Minh). v7.14: dùng chung resolveCurrentServer()
                // với nút Tập trước/Tiếp và Danh sách tập.
                val resolved = resolveCurrentServer(detail)
                if (resolved == null) {
                    Toast.makeText(this@PlayerActivity, "Đã hết tập (không xác định được server đang xem)", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val (srv, idx) = resolved
                currentServerName = srv.serverName
                val next = srv.items.getOrNull(idx + 1)
                if (next == null) {
                    Toast.makeText(this@PlayerActivity, "Đã hết tập của phim này", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val direct = when {
                    next.m3u8.isNotBlank() -> next.m3u8
                    next.embed.contains(".m3u8", ignoreCase = true) -> next.embed
                    else -> ""
                }
                if (direct.isBlank()) {
                    Toast.makeText(this@PlayerActivity, "Tập tiếp theo cần mở bằng trình phát web", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                episodeSlug = next.slug
                episodeTitle = if (next.name.all { it.isDigit() }) "Tập ${next.name}" else next.name
                startPositionMs = 0L
                hasSeekedToStart = true
                url = direct
                findViewById<TextView>(R.id.playerTitle).text = "$filmTitle · $episodeTitle"
                Toast.makeText(this@PlayerActivity, "Đang chuyển sang $episodeTitle", Toast.LENGTH_SHORT).show()
                autoNextBusy = false
                prepareChannelUrl(direct)
            } catch (_: Exception) {
                autoNextBusy = false
            }
        }
    }

    /**
     * Nạp (lại) một URL vào player. setMediaItem luôn đưa vị trí về đầu item mới,
     * nên khi nạp lại CHÍNH luồng phim đang xem (keepPosition = true, vd nút Thử Lại
     * sau lỗi) phải seek lại vị trí cũ — nếu không người xem bị "giật" về đầu phim.
     */
    private fun prepareChannelUrl(targetUrl: String, keepPosition: Boolean = false) {
        if (targetUrl.isBlank()) return
        val keepPos = if (keepPosition && isVodMode) (player?.currentPosition ?: 0L) else 0L
        if (Tv360Resolver.isTv360(targetUrl)) {
            lifecycleScope.launch {
                val resolved = Tv360Resolver.resolve(targetUrl) ?: targetUrl
                player?.setMediaItem(Enhancer.buildMediaItem(resolved))
                player?.prepare()
                player?.playWhenReady = true
                if (keepPos > 1000L) player?.seekTo(keepPos)
            }
        } else {
            player?.setMediaItem(Enhancer.buildMediaItem(targetUrl))
            player?.prepare()
            player?.playWhenReady = true
            if (keepPos > 1000L) player?.seekTo(keepPos)
        }
    }

    private fun switchChannel(ch: IptvChannel) {
        url = ch.url
        findViewById<TextView>(R.id.playerTitle).text = ch.name
        channelSidebar?.visibility = View.GONE
        hideCarousel()
        showOverlay()

        player?.stop()
        prepareChannelUrl(url)
        if (!DeviceMode.isTv) {
            Toast.makeText(this, "Đang chuyển sang: ${ch.name}", Toast.LENGTH_SHORT).show()
        }
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
                        error(R.drawable.kl_p_channels)
                        placeholder(R.drawable.kl_p_channels)
                    }
                } else {
                    holder.ivLogo.setImageResource(R.drawable.kl_p_channels)
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
                            tvCarouselEpgPreview?.text = "[${epg.first!!.timeRange()}] ${epg.first!!.title}"
                        } else {
                            tvCarouselEpgPreview?.text = if (ch.isVn) "Truyền hình trực tiếp chất lượng cao" else "${ch.group} trực tiếp"
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
        player = ExoPlayer.Builder(this, Enhancer.buildRenderersFactory(this))
            .setMediaSourceFactory(Enhancer.buildMediaSourceFactory(this))
            .setTrackSelector(Enhancer.buildTrackSelector(this))
            .setLoadControl(Enhancer.buildLoadControl(this))
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        val isNet = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                                error.errorCodeName.startsWith("ERROR_CODE_IO")

                        // 1. Thể thao LIVE: Tự động kết nối lại luồng hiện tại khi mạng chập chờn.
                        // v7.14: loại phim VOD khỏi nhánh này — prepareChannelUrl() nạp lại media item
                        // làm vị trí phát quay về đầu, chính là lỗi "bấm tua tới xong bị giật trở lại"
                        // mà người dùng phản hồi. VOD đi nhánh 3 (prepare() giữ nguyên vị trí).
                        if (!isIptvMode && !isVodMode && sportsFailoverCount < 3) {
                            sportsFailoverCount++
                            Toast.makeText(this@PlayerActivity, "Đang kết nối lại luồng trực tiếp (Lần $sportsFailoverCount/3)...", Toast.LENGTH_SHORT).show()
                            handler.postDelayed({
                                if (!isFinishing && !isDestroyed) {
                                    prepareChannelUrl(url)
                                }
                            }, 1000)
                            return
                        }

                        // 2. IPTV tự động failover sang luồng backup hoặc kênh tiếp theo
                        if (isIptvMode && iptvFailoverCount < 3) {
                            iptvFailoverCount++
                            val backup = Tv360Resolver.getBackupForChannel(url)
                            if (backup != null && backup != url) {
                                Toast.makeText(this@PlayerActivity, "Nguồn phát gián đoạn — Tự chuyển luồng dự phòng...", Toast.LENGTH_SHORT).show()
                                url = backup
                                prepareChannelUrl(backup)
                                return
                            }
                            if (IptvRepository.currentChannels.isNotEmpty()) {
                                val nextIdx = (currentChannelIndex + 1) % IptvRepository.currentChannels.size
                                val nextCh = IptvRepository.currentChannels.getOrNull(nextIdx)
                                if (nextCh != null) {
                                    currentChannelIndex = nextIdx
                                    Toast.makeText(this@PlayerActivity, "Tự chuyển sang kênh ${nextCh.name}...", Toast.LENGTH_SHORT).show()
                                    switchChannel(nextCh)
                                    return
                                }
                            }
                        }

                        // 3. Retry luồng thông thường nếu mạng chập chờn
                        if (isNet && streamRetries < 2) {
                            streamRetries++
                            Toast.makeText(
                                this@PlayerActivity,
                                getString(R.string.player_retry, streamRetries),
                                Toast.LENGTH_SHORT
                            ).show()
                            handler.postDelayed({ player?.prepare() }, 1500L * streamRetries)
                        } else {
                            val msg = if (isNet) getString(R.string.player_net_error)
                            else getString(R.string.player_stream_error, error.errorCodeName)
                            Toast.makeText(this@PlayerActivity, msg, Toast.LENGTH_LONG).show()

                            // Hiển thị khung báo lỗi và nút Thử Lại/Quay Lại trên TV
                            findViewById<View>(R.id.bufferBox)?.visibility = View.GONE
                            findViewById<View>(R.id.playerErrorBox)?.apply {
                                visibility = View.VISIBLE
                                findViewById<TextView>(R.id.tvPlayerErrorDetail)?.text = msg
                                findViewById<Button>(R.id.btnPlayerRetry)?.requestFocus()
                            }
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            streamRetries = 0
                            sportsFailoverCount = 0
                            iptvFailoverCount = 0
                            handler.removeCallbacks(autoRecoveryRunnable)
                            findViewById<View>(R.id.playerErrorBox)?.visibility = View.GONE
                            findViewById<View>(R.id.bufferBox)?.visibility = View.GONE
                            if (isVodMode && startPositionMs > 1000L && !hasSeekedToStart) {
                                hasSeekedToStart = true
                                val dur = player?.duration ?: 0L
                                // Tập đã xem gần hết (>= 90% hoặc còn < 10s) mà mở lại từ lịch sử/danh sách
                                // -> phát lại từ đầu, tránh seek tới sát cuối rồi STATE_ENDED/tự nhảy tập ngay.
                                // Ngoại lệ: vị trí do app xuống nền giữa chừng thì luôn quay lại đúng chỗ đó.
                                val alreadyFinished = !positionFromBackground && dur > 0L &&
                                    (startPositionMs * 10L >= dur * 9L || dur - startPositionMs < 10_000L)
                                positionFromBackground = false
                                if (alreadyFinished) {
                                    startPositionMs = 0L
                                    Toast.makeText(this@PlayerActivity, "Tập này đã xem hết — phát lại từ đầu", Toast.LENGTH_SHORT).show()
                                } else {
                                    player?.seekTo(startPositionMs)
                                    val m = startPositionMs / 60000L
                                    val s = (startPositionMs % 60000L) / 1000L
                                    Toast.makeText(this@PlayerActivity, String.format(Locale.US, "Tiếp tục phát từ %02d:%02d", m, s), Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else if (state == Player.STATE_BUFFERING) {
                            handler.removeCallbacks(autoRecoveryRunnable)
                            handler.postDelayed(autoRecoveryRunnable, if (isVodMode) 8000L else 4000L)
                            if (findViewById<View>(R.id.playerErrorBox)?.visibility != View.VISIBLE) {
                                findViewById<View>(R.id.bufferBox)?.visibility = View.VISIBLE
                            }
                        } else if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
                            if (findViewById<View>(R.id.playerErrorBox)?.visibility != View.VISIBLE) {
                                findViewById<View>(R.id.bufferBox)?.visibility = View.GONE
                            }
                            if (state == Player.STATE_ENDED && isVodMode) {
                                tryAutoNextEpisode()
                            }
                        }
                    }
                })
            }
        findViewById<View>(R.id.bufferBox)?.visibility = View.VISIBLE
        findViewById<PlayerView>(R.id.playerView).apply {
            player = this@PlayerActivity.player
            resizeMode = aspectModes[currentAspectIdx]
        }
        player?.let { p ->
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (DeviceMode.isPhone) {
                        centerPlayPauseBtn?.setImageResource(if (isPlaying) R.drawable.kl_p_pause else R.drawable.kl_p_play)
                    }
                    if (isVodMode) {
                        if (isPlaying) {
                            handler.removeCallbacks(vodAutoSaveRunnable)
                            handler.postDelayed(vodAutoSaveRunnable, 5000L)
                        } else {
                            handler.removeCallbacks(vodAutoSaveRunnable)
                            saveVodProgress()
                        }
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
        prepareChannelUrl(url)
    }

    private val vodAutoSaveRunnable = object : Runnable {
        override fun run() {
            if (isVodMode && player?.isPlaying == true) {
                saveVodProgress()
            }
            handler.postDelayed(this, 5000L)
        }
    }

    private fun saveVodProgress(explicitPos: Long? = null) {
        if (!isVodMode || filmSlug.isBlank()) return
        val p = player ?: return
        val pos = explicitPos ?: p.currentPosition
        val dur = p.duration.coerceAtLeast(0L)
        if (pos > 1000L || explicitPos != null) {
            val resolvedFilmName = if (filmTitle.isNotBlank()) filmTitle else findViewById<TextView>(R.id.playerTitle).text.toString()
            com.kenhlive.tv.phim.WatchHistoryManager.saveProgressAsync(
                slug = filmSlug,
                filmName = resolvedFilmName,
                posterUrl = posterUrl,
                episodeName = episodeTitle,
                episodeSlug = episodeSlug,
                embedUrl = url,
                positionMs = pos,
                durationMs = dur
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (player == null && url.isNotBlank()) initPlayer()
    }

    // ===== Dialog cài đặt thống nhất: Hình ảnh + Âm thanh + Tỉ lệ + Bước tua =====
    // Trước đây: nút Hình ảnh / Âm thanh mở 2 dialog giống hệt nhau chỉ hiện 1 phần,
    // menu Cài đặt của thanh VOD lại là list chữ rời rạc (đổi chất lượng phải nhảy 2 dialog).
    // Nay: 1 dialog duy nhất, 4 nhóm luôn hiện, chọn là áp dụng ngay + cập nhật nhãn trạng thái.
    private fun showSettingsDialog() {
        dialog?.dismiss()
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_player_settings, null)
        val videoBox = v.findViewById<LinearLayout>(R.id.videoOptions)
        val audioBox = v.findViewById<LinearLayout>(R.id.audioOptions)
        val aspectBox = v.findViewById<LinearLayout>(R.id.aspectOptions)
        val seekBox = v.findViewById<LinearLayout>(R.id.seekOptions)
        val videoStatus = v.findViewById<TextView>(R.id.videoStatus)
        val audioStatus = v.findViewById<TextView>(R.id.audioStatus)
        val aspectStatus = v.findViewById<TextView>(R.id.aspectStatus)
        val seekStatus = v.findViewById<TextView>(R.id.seekStatus)

        val vqNames = arrayOf(
            getString(R.string.vq_auto), getString(R.string.vq_high), getString(R.string.vq_stable)
        )
        val aqNames = arrayOf(
            getString(R.string.aq_standard), getString(R.string.aq_bass), getString(R.string.aq_dialog),
            getString(R.string.aq_night), getString(R.string.aq_auto)
        )
        val seekOptions = PlayerPrefs.SEEK_STEP_OPTIONS_SEC
        val seekNames = seekOptions.map { "${it}s" }.toTypedArray()

        videoStatus.text = "Đang chọn: ${vqNames[EnhanceSettings.videoQuality(this)]}"
        audioStatus.text = "Đang chọn: ${aqNames[EnhanceSettings.audioMode(this)]}"
        aspectStatus.text = "Đang chọn: ${aspectNames[currentAspectIdx]}"
        seekStatus.text = "Đang chọn: ${PlayerPrefs.seekStepSec(this)}s"

        val d = AlertDialog.Builder(this, R.style.Theme_KenhLive_Dialog)
            .setView(v)
            .setNegativeButton(R.string.dialog_close, null)
            .setOnDismissListener { dialog = null }
            .create()
        dialog = d

        buildChips(videoBox, vqNames, EnhanceSettings.videoQuality(this), isLastRow = false, dialog = d) { i ->
            if (i != EnhanceSettings.videoQuality(this)) {
                EnhanceSettings.setVideoQuality(this, i)
                (player?.trackSelector as? androidx.media3.exoplayer.trackselection.DefaultTrackSelector)
                    ?.let { Enhancer.applyVideo(it, i) }
                Toast.makeText(this, getString(R.string.player_video_changed, vqNames[i]), Toast.LENGTH_SHORT).show()
                markSelection(videoBox, i)
                videoStatus.text = "Đang chọn: ${vqNames[i]}"
            }
        }
        buildChips(audioBox, aqNames, EnhanceSettings.audioMode(this), isLastRow = false, dialog = d) { i ->
            if (i != EnhanceSettings.audioMode(this)) {
                EnhanceSettings.setAudioMode(this, i)
                val sid = player?.audioSessionId ?: 0
                if (sid != 0) audioFx.attach(sid, i)
                val suffix = if (sid == 0) getString(R.string.player_audio_pending) else ""
                Toast.makeText(this, getString(R.string.player_audio_changed, aqNames[i]) + suffix, Toast.LENGTH_SHORT).show()
                markSelection(audioBox, i)
                audioStatus.text = "Đang chọn: ${aqNames[i]}"
            }
        }
        buildChips(aspectBox, aspectNames, currentAspectIdx, isLastRow = false, dialog = d) { i ->
            if (i != currentAspectIdx) {
                currentAspectIdx = i
                findViewById<PlayerView>(R.id.playerView)?.resizeMode = aspectModes[i]
                getSharedPreferences(PREFS_PLAYER, MODE_PRIVATE).edit().putInt(KEY_ASPECT_IDX, i).apply()
                Toast.makeText(this, "Tỉ lệ màn hình: ${aspectNames[i]}", Toast.LENGTH_SHORT).show()
                markSelection(aspectBox, i)
                aspectStatus.text = "Đang chọn: ${aspectNames[i]}"
            }
        }
        buildChips(seekBox, seekNames, seekOptions.indexOf(PlayerPrefs.seekStepSec(this)).coerceAtLeast(0), isLastRow = true, dialog = d) { i ->
            val sec = seekOptions[i]
            if (sec != PlayerPrefs.seekStepSec(this)) {
                PlayerPrefs.setSeekStepSec(this, sec)
                seekVod?.keyProgressIncrement = sec
                syncVodControlBar()
                Toast.makeText(this, "Bước tua: ${sec}s", Toast.LENGTH_SHORT).show()
                markSelection(seekBox, i)
                seekStatus.text = "Đang chọn: ${sec}s"
            }
        }

        d.setOnShowListener {
            val closeBtn = d.getButton(AlertDialog.BUTTON_NEGATIVE)
            closeBtn?.isFocusable = true
            closeBtn?.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    val idx = seekOptions.indexOf(PlayerPrefs.seekStepSec(this@PlayerActivity)).coerceAtLeast(0)
                    seekBox.getChildAt(idx.coerceAtMost(seekBox.childCount - 1))?.requestFocus()
                    return@setOnKeyListener true
                }
                false
            }
            val idx = EnhanceSettings.videoQuality(this)
            videoBox.getChildAt(idx.coerceAtMost(videoBox.childCount - 1))?.requestFocus()
        }
        d.show()
        d.window?.setLayout(
            (resources.displayMetrics.widthPixels * (if (DeviceMode.isTv) 0.72 else 0.92)).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun buildChips(
        box: LinearLayout, names: Array<String>, selected: Int,
        isLastRow: Boolean, dialog: AlertDialog? = null, onPick: (Int) -> Unit
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
                // Hàng cuối: DOWN -> nút Đóng. Các hàng khác để focus search mặc định
                // của Android tự di chuyển dọc (hàng trên <-> hàng dưới).
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN && isLastRow) {
                    dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
                    return@setOnKeyListener true
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
        if (Build.VERSION.SDK_INT >= 26 && !isInPictureInPictureMode && (player?.isPlaying == true) && !DeviceMode.isTv) {
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
            quickChannelOsd?.visibility = View.GONE
            statsHudBox?.visibility = View.GONE
            findViewById<PlayerView>(R.id.playerView)?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
        } else {
            findViewById<PlayerView>(R.id.playerView)?.resizeMode = aspectModes[currentAspectIdx]
            showOverlay()
        }
    }

    /** Nối thanh tua VOD (dùng chung phone + TV): kéo/chỉnh để tua, nhãn thời gian cập nhật trực tiếp. */
    private fun wireVodSeekBar() {
        phoneSeekRow = findViewById(R.id.phoneSeekRow)
        seekVod = findViewById(R.id.seekVod)
        tvSeekCurrent = findViewById(R.id.tvSeekCurrent)
        tvSeekTotal = findViewById(R.id.tvSeekTotal)
        // Tiến trình của thanh tua tính bằng GIÂY → bước phím D-pad trên thanh = đúng
        // bước tua người dùng đã chọn trong Cài đặt (10/20/30s).
        seekVod?.keyProgressIncrement = PlayerPrefs.seekStepSec(this)
        seekVod?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                tvSeekCurrent?.text = fmtPlaybackTime(progress * 1000L)
                // Remote TV: D-pad ◄/► khi seekbar đang focus CHỈ bắn onProgressChanged
                // (fromUser=true) — không hề có start/stop tracking. Coi đây là đang
                // scrub: khóa updater bằng seekDragging (updater đã tôn trọng cờ này —
                // nó không ghi đè progress khi seekDragging=true), ghi đích cho guard
                // chống giật ngược và lên lịch chốt seek bằng debounce sau lần bấm cuối.
                // Kéo cảm ứng (phone) thì bỏ qua debounce — onStopTrackingTouch chốt ngay.
                seekDragging = true
                pendingScrubTargetMs = progress * 1000L
                noteUserSeek(pendingScrubTargetMs)
                if (!seekTouchTracking) {
                    handler.removeCallbacks(scrubCommitRunnable)
                    handler.postDelayed(scrubCommitRunnable, SCRUB_COMMIT_DELAY_MS)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                seekTouchTracking = true
                seekDragging = true
                handler.removeCallbacks(scrubCommitRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                // Hủy commit debounce đang chờ (nếu có) và chốt ngay tại vị trí núm.
                handler.removeCallbacks(scrubCommitRunnable)
                seekTouchTracking = false
                seekDragging = false
                pendingScrubTargetMs = -1L
                val target = (sb?.progress ?: 0) * 1000L
                noteUserSeek(target)
                player?.seekTo(target)
                tvSeekCurrent?.text = fmtPlaybackTime(target)
                hideOnce()
            }
        })
        // Mất focus giữa chừng khi đang scrub bằng D-pad (vd bấm phím điều hướng sang
        // nút khác): chốt seek ngay thay vì chờ hết debounce.
        seekVod?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && seekDragging && !seekTouchTracking) {
                handler.removeCallbacks(scrubCommitRunnable)
                scrubCommitRunnable.run()
            }
        }
    }

    /** Bước tua hiện tại (giây) theo cài đặt của người dùng — mặc định 10s. */
    private fun seekStepMs(): Long = PlayerPrefs.seekStepSec(this) * 1000L

    /** Ghi lại một lần tua do người dùng chủ động (đích + thời điểm) cho guard chống giật ngược. */
    private fun noteUserSeek(targetMs: Long) {
        lastSeekTargetMs = targetMs
        lastSeekAtMs = android.os.SystemClock.uptimeMillis()
    }

    /**
     * Vị trí gốc để tính lần tua tiếp theo: nếu vừa tua chưa đầy 1.5s thì dùng ĐÍCH của lần
     * trước — trên máy yếu player chưa kịp cập nhật currentPosition (vẫn báo vị trí cũ),
     * lấy vị trí cũ làm gốc sẽ khiến video "tới rồi giật lại". Hết cửa sổ thì dùng vị trí thật.
     */
    private fun guardedSeekBase(p: Player): Long {
        val fresh = lastSeekTargetMs >= 0L &&
            android.os.SystemClock.uptimeMillis() - lastSeekAtMs < 1500L
        return if (fresh) lastSeekTargetMs else p.currentPosition
    }

    /** Tua theo bước đã cài đặt. Dùng chung: D-pad ◄/►, chạm đúp (phone), nút tua trên thanh điều khiển TV. */
    private fun seekByStep(dir: Int) {
        val p = player ?: return
        val step = seekStepMs()
        val base = guardedSeekBase(p)
        if (dir < 0) {
            val target = (base - step).coerceAtLeast(0L)
            noteUserSeek(target)
            p.seekTo(target)
            val m = target / 60000L
            val s = (target % 60000L) / 1000L
            showGestureFeedback(R.drawable.ic_back, 0, String.format(Locale.US, "-%ds (%02d:%02d)", step / 1000L, m, s))
            saveVodProgress(target)
        } else {
            val dur = p.duration.coerceAtLeast(0L)
            val target = if (dur > 0L) (base + step).coerceAtMost(dur) else base + step
            noteUserSeek(target)
            p.seekTo(target)
            val m = target / 60000L
            val s = (target % 60000L) / 1000L
            showGestureFeedback(R.drawable.kl_p_play, 100, String.format(Locale.US, "+%ds (%02d:%02d)", step / 1000L, m, s))
            saveVodProgress(target)
        }
        hideOnce()
    }

    private fun syncVodControlBar() {
        val playing = player?.isPlaying == true
        btnBarPlayPause?.setCompoundDrawablesWithIntrinsicBounds(
            0, if (playing) R.drawable.kl_p_pause else R.drawable.kl_p_play, 0, 0
        )
        btnBarPlayPause?.text = if (playing) "Tạm dừng" else "Phát"
        val step = PlayerPrefs.seekStepSec(this)
        btnBarSeekBack?.text = "Tua -${step}s"
        btnBarSeekFwd?.text = "Tua +${step}s"
    }

    /**
     * Menu Cài Đặt của thanh điều khiển VOD (nút Cài Đặt): gom các chỉnh thông số vốn
     * nằm rời rạc trên topOverlay. Mỗi mục gọi ĐÚNG handler của nút topOverlay tương ứng:
     * Chất lượng = qualityBtn, Âm thanh = audioBtn, Tỷ lệ khung hình = aspectBtn.
     */
    private fun showVodSettingsDialog() {
        val boostLabels = arrayOf("Tắt", "+3dB", "+6dB", "+9dB")
        val timerLabel = if (sleepMinutesLeft > 0) "Còn $sleepMinutesLeft phút" else "Chưa đặt"
        // Hình ảnh / Âm thanh / Tỉ lệ / Bước tua đã gộp vào dialog cài đặt thống nhất
        // (showSettingsDialog) — không lặp lại ở đây để tránh 2 nơi chỉnh cùng 1 thông số.
        val items = arrayOf(
            "Hình ảnh, âm thanh & hiển thị",
            "Kênh âm thanh",
            "Hẹn giờ tắt ($timerLabel)",
            "Khuếch đại âm lượng: ${boostLabels[audioBoostLevel]}",
            "Thông số kỹ thuật (Stats)"
        )
        AlertDialog.Builder(this, R.style.Theme_KenhLive_Dialog)
            .setTitle("Cài Đặt")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showSettingsDialog()
                    1 -> showAudioTrackDialog()
                    2 -> showSleepTimerDialog()
                    3 -> cycleAudioBoost()
                    4 -> toggleStatsHud()
                }
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
        hideOnce()
    }

    private fun wireVodControlBar() {
        vodControlBar = findViewById(R.id.vodControlBar)
        btnBarSettings = findViewById(R.id.btnBarSettings)
        btnBarPrevEp = findViewById(R.id.btnBarPrevEp)
        btnBarSeekBack = findViewById(R.id.btnBarSeekBack)
        btnBarPlayPause = findViewById(R.id.btnBarPlayPause)
        btnBarSeekFwd = findViewById(R.id.btnBarSeekFwd)
        btnBarNextEp = findViewById(R.id.btnBarNextEp)
        btnBarEpisodes = findViewById(R.id.btnBarEpisodes)
        // Nút đầu tiên của thanh: Cài Đặt — mở trực tiếp Dialog Cài Đặt Trình Phát Thống Nhất
        // (Hình ảnh, Âm thanh, Tỉ lệ, Bước tua) chuẩn 10-foot Android TV.
        btnBarSettings?.setOnClickListener { showSettingsDialog() }
        btnBarPrevEp?.setOnClickListener { switchEpisodeByDelta(-1); hideOnce() }
        btnBarSeekBack?.setOnClickListener { seekByStep(-1) }
        btnBarPlayPause?.setOnClickListener { togglePlayPause() }
        btnBarSeekFwd?.setOnClickListener { seekByStep(1) }
        btnBarNextEp?.setOnClickListener { switchEpisodeByDelta(1); hideOnce() }
        btnBarEpisodes?.setOnClickListener { showEpisodeListDialog() }
    }

    /**
     * Nhận diện server đang phát + vị trí tập hiện tại trong server đó (dùng chung:
     * tự chuyển tập, nút Tập trước/Tiếp, hộp thoại Danh sách tập). Thứ tự tin cậy:
     * 1) tên server đã chốt; 2) URL luồng khớp tập hiện tại; 3) slug chỉ xuất hiện ở 1 server.
     */
    private fun resolveCurrentServer(
        detail: com.kenhlive.tv.phim.NguoncFilmDetail?
    ): Pair<com.kenhlive.tv.phim.NguoncServer, Int>? {
        val servers = detail?.episodes.orEmpty()
        var currentServer: com.kenhlive.tv.phim.NguoncServer? = null
        if (currentServerName.isNotBlank()) {
            currentServer = servers.firstOrNull { srv ->
                srv.serverName.equals(currentServerName, ignoreCase = true) &&
                    srv.items.any { it.slug == episodeSlug }
            }
        }
        if (currentServer == null) {
            currentServer = servers.firstOrNull { srv ->
                srv.items.any { it.slug == episodeSlug && (it.m3u8 == url || it.embed == url) }
            }
        }
        if (currentServer == null) {
            val slugServers = servers.filter { srv -> srv.items.any { it.slug == episodeSlug } }
            if (slugServers.size == 1) currentServer = slugServers[0]
        }
        val srv = currentServer ?: return null
        val idx = srv.items.indexOfFirst { it.slug == episodeSlug }
        if (idx < 0) return null
        return srv to idx
    }

    /** Phát một tập khác ngay trong player (nút Tập trước/Tiếp + Danh sách tập). */
    private fun playEpisodeItem(
        server: com.kenhlive.tv.phim.NguoncServer,
        item: com.kenhlive.tv.phim.NguoncEpisodeItem
    ) {
        val direct = when {
            item.m3u8.isNotBlank() -> item.m3u8
            item.embed.contains(".m3u8", ignoreCase = true) -> item.embed
            else -> ""
        }
        if (direct.isBlank()) {
            Toast.makeText(this, "Tập này cần mở bằng trình phát web", Toast.LENGTH_SHORT).show()
            return
        }
        saveVodProgress()
        currentServerName = server.serverName
        episodeSlug = item.slug
        episodeTitle = if (item.name.all { it.isDigit() }) "Tập ${item.name}" else item.name
        startPositionMs = 0L
        hasSeekedToStart = true
        url = direct
        findViewById<TextView>(R.id.playerTitle).text = "$filmTitle · $episodeTitle"
        Toast.makeText(this, "Đang chuyển sang $episodeTitle", Toast.LENGTH_SHORT).show()
        prepareChannelUrl(direct)
    }

    private fun switchEpisodeByDelta(delta: Int) {
        if (filmSlug.isBlank() || episodeSlug.isBlank()) {
            Toast.makeText(this, "Nội dung này không có danh sách tập", Toast.LENGTH_SHORT).show()
            return
        }
        if (autoNextBusy) return
        autoNextBusy = true
        lifecycleScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    com.kenhlive.tv.phim.NguoncRepository.fetchFilmDetail(filmSlug, this@PlayerActivity)
                }
                val resolved = resolveCurrentServer(detail)
                if (resolved == null) {
                    Toast.makeText(this@PlayerActivity, "Không xác định được server đang xem", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val (srv, idx) = resolved
                val target = srv.items.getOrNull(idx + delta)
                if (target == null) {
                    Toast.makeText(
                        this@PlayerActivity,
                        if (delta < 0) "Đã là tập đầu tiên" else "Đã hết tập của phim này",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                playEpisodeItem(srv, target)
            } catch (_: Exception) {
            } finally {
                autoNextBusy = false
            }
        }
    }

    /** Hộp thoại lưới tập của server đang phát — chọn tập bất kỳ để phát ngay. */
    private fun showEpisodeListDialog() {
        if (filmSlug.isBlank() || episodeSlug.isBlank()) {
            Toast.makeText(this, "Không có danh sách tập cho nội dung này", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val detail = withContext(Dispatchers.IO) {
                com.kenhlive.tv.phim.NguoncRepository.fetchFilmDetail(filmSlug, this@PlayerActivity)
            }
            val srv = resolveCurrentServer(detail)?.first
                ?: detail?.episodes?.firstOrNull { s -> s.items.any { it.slug == episodeSlug } }
            if (srv == null || srv.items.isEmpty()) {
                Toast.makeText(this@PlayerActivity, "Không tải được danh sách tập", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val curIdx = srv.items.indexOfFirst { it.slug == episodeSlug }.coerceAtLeast(0)
            val rv = RecyclerView(this@PlayerActivity).apply {
                layoutManager = GridLayoutManager(this@PlayerActivity, 5)
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, pad / 2)
                clipToPadding = false
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (300 * resources.displayMetrics.density).toInt()
                )
                adapter = PlayerEpisodeAdapter(srv.items, episodeSlug) { item ->
                    dialog?.dismiss()
                    playEpisodeItem(srv, item)
                }
            }
            dialog = AlertDialog.Builder(this@PlayerActivity, R.style.Theme_KenhLive_Dialog)
                .setTitle("$filmTitle · ${srv.serverName}")
                .setView(rv)
                .setNegativeButton("Đóng", null)
                // BẮT BUỘC reset trường dialog khi đóng (như showSettingsDialog): thiếu dòng này
                // thì sau khi đóng dialog tập, cổng phím chính (dialog == null) không bao giờ mở
                // lại -> OK không mở được thanh điều khiển, ◄/► không tua được (audit H1).
                .setOnDismissListener { dialog = null }
                .create()
            dialog?.show()
            rv.scrollToPosition(curIdx)
            rv.post {
                (rv.findViewHolderForAdapterPosition(curIdx)?.itemView
                    ?: rv.layoutManager?.findViewByPosition(curIdx))?.requestFocus()
            }
        }
    }

    /** Nhãn chip tập rút gọn "Tập N" (cùng chuẩn với EpisodePickerDialog). */
    private fun episodeChipLabel(raw: String): String {
        val name = raw.trim()
        if (name.isEmpty()) return "Tập ?"
        if (name.all { it.isDigit() }) return "Tập $name"
        val trailingNum = Regex("(\\d+)\\s*$").find(name)?.groupValues?.get(1)
        if (trailingNum != null && (name.contains("tập", true) || name.contains("tap", true) ||
                name.contains("ep", true) || name.length <= 22)) {
            trailingNum.toIntOrNull()?.let { return "Tập $it" }
        }
        return if (name.length > 18) name.substring(0, 17) + "…" else name
    }

    private inner class PlayerEpisodeAdapter(
        private val items: List<com.kenhlive.tv.phim.NguoncEpisodeItem>,
        private val currentSlug: String?,
        private val onPick: (com.kenhlive.tv.phim.NguoncEpisodeItem) -> Unit
    ) : RecyclerView.Adapter<PlayerEpisodeAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvEpisodeName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_episode_chip, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val isCurrent = item.slug == currentSlug
            holder.tv.text = episodeChipLabel(item.name)
            if (isCurrent) {
                holder.tv.setBackgroundResource(R.drawable.bg_badge_hd)
                holder.tv.setTextColor(android.graphics.Color.WHITE)
            } else {
                holder.tv.setBackgroundResource(R.drawable.bg_badge_glass)
                holder.tv.setTextColor(0xFFE2E8F0.toInt())
            }
            holder.itemView.setOnClickListener { onPick(item) }
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                // Không scale animation: vòng focus cam của background lo phần nổi bật;
                // animate trên view bị recycle gây chớp hình "tập ẩn tập hiện" (đồng bộ EpisodePickerDialog).
                if (hasFocus) holder.tv.setTextColor(android.graphics.Color.WHITE)
                else if (!isCurrent) holder.tv.setTextColor(0xFFE2E8F0.toInt())
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private fun showOverlay() {
        if (isScreenLocked) return
        if (channelSidebar?.visibility == View.VISIBLE) return
        quickChannelOsd?.visibility = View.GONE
        topOverlay?.visibility = View.VISIBLE
        if (DeviceMode.isPhone) {
            centerPlayPauseBtn?.visibility = View.VISIBLE
            phoneBottomBar?.visibility = View.VISIBLE
            val isPlaying = player?.isPlaying == true
            centerPlayPauseBtn?.setImageResource(if (isPlaying) R.drawable.kl_p_pause else R.drawable.kl_p_play)
            // Thanh tua chỉ dành cho phim VOD (luồng live không tua được)
            if (isVodMode) {
                phoneSeekRow?.visibility = View.VISIBLE
                handler.removeCallbacks(seekUpdateRunnable)
                handler.post(seekUpdateRunnable)
            } else {
                phoneSeekRow?.visibility = View.GONE
            }
        } else if (isVodMode) {
            // TV xem phim VOD: hiện THANH ĐIỀU KHIỂN dưới đáy (Cài Đặt / Tập trước / Tua /
            // Phát-Dừng / Tập tiếp / Danh sách tập) + thanh tua kèm overlay. Nút Play/Pause
            // giữa màn hình không cần nữa vì đã có ngay trong thanh (bản v7.11-v7.13 dùng
            // nút giữa màn hình; người dùng góp ý muốn đủ tính năng như các trình phát TV).
            vodControlBar?.visibility = View.VISIBLE
            syncVodControlBar()
            centerPlayPauseBtn?.visibility = View.GONE
            val row = phoneSeekRow
            if (row != null) {
                (row.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    // Thanh tua nằm ngay trên thanh điều khiển 68dp
                    lp.bottomMargin = (76 * resources.displayMetrics.density).toInt()
                    row.layoutParams = lp
                }
                row.visibility = View.VISIBLE
                handler.removeCallbacks(seekUpdateRunnable)
                handler.post(seekUpdateRunnable)
            }
            // TV: Tự động bắt focus ngay vào nút Cài Đặt ở đầu thanh để người dùng remote D-pad thấy ngay
            vodControlBar?.post {
                val cur = currentFocus
                if (cur == null || (cur !== btnBarSettings && cur !== btnBarPlayPause && cur !== btnBarSeekBack && cur !== btnBarSeekFwd && cur !== btnBarPrevEp && cur !== btnBarNextEp && cur !== btnBarEpisodes && cur !== seekVod)) {
                    btnBarSettings?.requestFocus()
                }
            }
        }
        hideOnce()
    }

    private fun hideOnce(delayMs: Long = if (DeviceMode.isTv) 45000L else 4000L) {
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, delayMs)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val isDown = e.action == KeyEvent.ACTION_DOWN
        val isOkKey = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                e.keyCode == KeyEvent.KEYCODE_ENTER ||
                e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

        // 0. Khi màn hình báo lỗi đang hiển thị
        val errBox = findViewById<View>(R.id.playerErrorBox)
        if (errBox != null && errBox.visibility == View.VISIBLE) {
            if (isDown) {
                if (e.keyCode == KeyEvent.KEYCODE_BACK) {
                    finish()
                    return true
                }
                val btnRetry = findViewById<Button>(R.id.btnPlayerRetry)
                val btnBack = findViewById<Button>(R.id.btnPlayerBack)
                val btnReport = findViewById<Button>(R.id.btnPlayerReport)
                if (btnRetry?.hasFocus() != true && btnBack?.hasFocus() != true && btnReport?.hasFocus() != true) {
                    btnRetry?.requestFocus()
                }
            }
            return super.dispatchKeyEvent(e)
        }

        // 1. Phím số (0-9): Nhảy kênh trực tiếp bằng số
        if (isDown && isIptvMode && ((e.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) || (e.keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9))) {
            val digit = if (e.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                e.keyCode - KeyEvent.KEYCODE_0
            } else {
                e.keyCode - KeyEvent.KEYCODE_NUMPAD_0
            }
            keypadAccumulator = keypadAccumulator * 10 + digit
            showKeypadFeedback(keypadAccumulator)
            handler.removeCallbacks(keypadCommitRunnable)
            handler.postDelayed(keypadCommitRunnable, 1000L)
            return true
        }

        // Bấm OK ngay khi đang gõ số kênh -> Chuyển kênh tức thì không cần chờ
        if (isDown && keypadAccumulator > 0 && isOkKey) {
            handler.removeCallbacks(keypadCommitRunnable)
            keypadCommitRunnable.run()
            return true
        }

        // Phím tắt đổi tỉ lệ màn hình (A / Red key) & Tăng âm lượng Audio Boost (B / Yellow key)
        if (isDown && (e.keyCode == KeyEvent.KEYCODE_A || e.keyCode == KeyEvent.KEYCODE_PROG_RED)) {
            cycleAspectRatio()
            return true
        }
        if (isDown && (e.keyCode == KeyEvent.KEYCODE_B || e.keyCode == KeyEvent.KEYCODE_PROG_YELLOW)) {
            cycleAudioBoost()
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
                if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP || e.keyCode == KeyEvent.KEYCODE_CHANNEL_UP || e.keyCode == KeyEvent.KEYCODE_PAGE_UP) {
                    showQuickOsd(pendingChannelIndex - 1)
                    return true
                }
                if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || e.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || e.keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
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
        // LƯU Ý: Phím BACK tuyệt đối KHÔNG gọi showOverlay() để tránh bẫy loop (BACK khi overlay ẩn phải finish activity)
        if (isDown && topOverlay?.visibility != View.VISIBLE && dialog == null && e.keyCode != KeyEvent.KEYCODE_BACK) {
            // Tua lùi / Tua tiến bằng remote D-pad Left / Right khi đang xem Phim VOD
            if (isVodMode || (player != null && !player!!.isCurrentMediaItemLive && player!!.duration > 0L)) {
                if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                    if (player != null) {
                        seekByStep(-1)
                        return true
                    }
                }
                if (e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || e.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                    if (player != null) {
                        seekByStep(1)
                        return true
                    }
                }
            }

            if (isIptvMode && (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || e.keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
                showCarousel()
                return true
            }
            if (isIptvMode && (e.keyCode == KeyEvent.KEYCODE_CHANNEL_UP || e.keyCode == KeyEvent.KEYCODE_PAGE_UP)) {
                showQuickOsd(currentChannelIndex - 1)
                return true
            }
            if (isIptvMode && (e.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || e.keyCode == KeyEvent.KEYCODE_PAGE_DOWN)) {
                showQuickOsd(currentChannelIndex + 1)
                return true
            }
            // Bấm OK hoặc phím điều hướng khi đang xem phim VOD:
            // - TV: mở thanh điều khiển đầy đủ (Cài Đặt / Tập trước / Tua / Phát / Tập tiếp /
            //   Danh sách tập), focus ngay vào nút Cài Đặt (btnBarSettings) ở đầu thanh.
            // - Điện thoại: Bấm OK -> Dừng/Tiếp tục phát ngay (v7.11); phím khác -> hiện overlay.
            if (isVodMode && (isOkKey || e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || e.keyCode == KeyEvent.KEYCODE_DPAD_UP || e.keyCode == KeyEvent.KEYCODE_MENU)) {
                if (DeviceMode.isTv) {
                    showOverlay()
                    val target = btnBarSettings ?: btnBarPlayPause
                    target?.requestFocus()
                    vodControlBar?.post { target?.requestFocus() }
                } else if (isOkKey) {
                    togglePlayPause()
                } else {
                    showOverlay()
                }
                return true
            }
            // Bấm OK hoặc phím khác khi overlay đang ẩn -> Hiện overlay điều khiển
            showOverlay()
            val targetBtn = if (isVodMode && DeviceMode.isTv) {
                btnBarSettings ?: btnBarPlayPause ?: centerPlayPauseBtn
            } else if (isVodMode) {
                centerPlayPauseBtn
            } else if (isIptvMode && findViewById<View>(R.id.channelListBtn)?.visibility == View.VISIBLE) {
                findViewById<View>(R.id.channelListBtn)
            } else {
                findViewById<View>(R.id.phoneMenuBtn) ?: findViewById<View>(R.id.backBtn)
            }
            targetBtn?.requestFocus()
            vodControlBar?.post { targetBtn?.requestFocus() }
            return true
        }

        // 6. Nếu overlay đang hiện, gia hạn thời gian tự ẩn và bảo đảm luôn có view giữ focus
        if (isDown && (topOverlay?.visibility == View.VISIBLE || vodControlBar?.visibility == View.VISIBLE)) {
            hideOnce()
            if (currentFocus == null) {
                val target = if (isVodMode) (btnBarSettings ?: btnBarPlayPause)
                             else findViewById<View>(R.id.phoneMenuBtn) ?: findViewById<View>(R.id.backBtn)
                target?.requestFocus()
                if (isOkKey) {
                    target?.performClick()
                    return true
                }
            }
        }

        // 7. Phím BACK: Luôn xử lý đóng dialog / HUD / overlay / thoát player
        if (e.keyCode == KeyEvent.KEYCODE_BACK && isDown) {
            if (isScreenLocked) {
                setScreenLock(false)
                return true
            }
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
            // BACK khi overlay đang hiện: ẩn ĐỒNG BỘ mọi lớp overlay bằng đúng runnable
            // chuẩn hideOverlay (top + nút giữa + thanh dưới phone + hàng seek + thanh điều
            // khiển VOD) và hủy timer tự ẩn. Trước đây chỉ ẩn topOverlay nên thanh dưới +
            // hàng seek treo vô thời hạn trên video và còn giữ focus (audit H2).
            if (topOverlay?.visibility == View.VISIBLE ||
                vodControlBar?.visibility == View.VISIBLE ||
                phoneBottomBar?.visibility == View.VISIBLE ||
                phoneSeekRow?.visibility == View.VISIBLE ||
                centerPlayPauseBtn?.visibility == View.VISIBLE
            ) {
                handler.removeCallbacks(hideOverlay)
                hideOverlay.run()
                return true
            }
            finish()
            return true
        }

        return super.dispatchKeyEvent(e)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!DeviceMode.isPhone && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            showOverlay()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(vodAutoSaveRunnable)
        saveVodProgress()
        handler.removeCallbacks(commitChannelSwitch)
        handler.removeCallbacks(autoRecoveryRunnable)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(vodAutoSaveRunnable)
        saveVodProgress()
        val inPip = Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode && !DeviceMode.isTv
        if (inPip && !isFinishing) return
        handler.removeCallbacks(hideOverlay)
        handler.removeCallbacks(seekUpdateRunnable)
        handler.removeCallbacks(statsUpdateRunnable)
        handler.removeCallbacks(commitChannelSwitch)
        handler.removeCallbacks(keypadCommitRunnable)
        handler.removeCallbacks(autoRecoveryRunnable)
        handler.removeCallbacks(sleepTimerRunnable)
        try { loudnessEnhancer?.release() } catch (_: Exception) {}
        loudnessEnhancer = null
        audioFx.detach()
        // v7.13: trước khi release player, chốt vị trí đang xem vào startPositionMs và mở lại
        // cờ seek — nếu không, onStart tạo lại player sẽ phát từ vị trí trong Intent ban đầu
        // (hasSeekedToStart vẫn true) khiến người dùng mất chỗ xem dở khi app xuống nền.
        if (isVodMode) {
            val pos = player?.currentPosition ?: 0L
            if (pos > 1000L) {
                startPositionMs = pos
                hasSeekedToStart = false
                positionFromBackground = true
            }
        }
        player?.stop()
        player?.clearMediaItems()
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
        player?.stop()
        player?.clearMediaItems()
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
