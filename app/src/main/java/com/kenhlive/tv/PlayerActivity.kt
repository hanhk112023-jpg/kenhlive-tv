package com.kenhlive.tv

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.util.Rational
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var topOverlay: View? = null
    private val hideOverlay = Runnable { topOverlay?.visibility = View.GONE }
    private val audioFx = AudioEnhancer(this)
    private var url: String = ""
    private var streamRetries = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        url = intent.getStringExtra("url") ?: ""
        val name = intent.getStringExtra("name") ?: "Kênh"

        topOverlay = findViewById(R.id.topOverlay)
        findViewById<TextView>(R.id.playerTitle).text = name

        initPlayer()
        val pv = findViewById<PlayerView>(R.id.playerView)

        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.qualityBtn).setOnClickListener { showQualityDialog() }
        findViewById<TextView>(R.id.pipBtn)?.setOnClickListener { enterPip(manual = true) }
        if (intent.getBooleanExtra("pip", false)) pv.post { enterPip(manual = false) }

        findViewById<TextView>(R.id.multiBtn).setOnClickListener {
            val i = Intent(this, MultiViewActivity::class.java)
            i.putExtra("initial_room", name)
            i.putExtra("initial_url", url)
            startActivity(i)
        }

        topOverlay?.visibility = View.VISIBLE
        hideOnce()
    }

    /** Dung ExoPlayer tu `url` — goi o onCreate VA onStart (BUG-04: onStop release khi
     *  nguoi dung bo app ngoai PiP; quay lai phai dung lai, khong de man den tinh). */
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
                            // stream hay chập chờn đầu phiên → tự thử lại tối đa 3 lần (2s/4s/6s)
                            streamRetries++
                            android.widget.Toast.makeText(this@PlayerActivity,
                                "Mạng chập chờn — tự thử lại lần $streamRetries…", android.widget.Toast.LENGTH_SHORT).show()
                            handler.postDelayed({ player?.prepare() }, 2000L * streamRetries)
                        } else {
                            val msg = if (isNet) "Mạng lỗi — thoát ra vào lại sau" else "Stream lỗi: ${error.errorCodeName}"
                            android.widget.Toast.makeText(this@PlayerActivity, msg, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        // BUG-06: phat OK thi reset budget retry — loi mang ngan sau nay van duoc tu hoi phuc
                        if (state == Player.STATE_READY) streamRetries = 0
                        // nemotron-omni QA: thay man den luc buffer -> chi bao ro
                        findViewById<View>(R.id.bufferBox)?.visibility =
                            if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    }
                })
            }
        findViewById<PlayerView>(R.id.playerView).apply {
            player = this@PlayerActivity.player
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        // audio fx gắn sau khi player có session
        player?.let { p ->
            p.addListener(object : Player.Listener {
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

    private fun showQualityDialog() {
        val vq = EnhanceSettings.videoQuality(this)
        val aq = EnhanceSettings.audioMode(this)
        val vqNames = arrayOf("Tự động", "Cao nhất (nét)", "Ổn định (mượt)")
        val aqNames = arrayOf("Chuẩn", "Bass mạnh", "Rõ tiếng BLV", "Ban đêm (êm)", "Tự động (to & hay)")
        val msg = "Hình: ${vqNames[vq]}\nÂm: ${aqNames[aq]}\n\nChọn hình:\n" +
            vqNames.mapIndexed { i, n -> if (i == vq) "[x] $n" else "[ ] $n" }.joinToString("\n") +
            "\n\nChọn âm:\n" + aqNames.mapIndexed { i, n -> if (i == aq) "[x] $n" else "[ ] $n" }.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("Chất lượng hình & âm")
            .setMessage(msg)
            .setPositiveButton("Hình ▸") { _, _ -> cycleVideo() }
            .setNeutralButton("Âm ▸") { _, _ -> cycleAudio() }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun cycleVideo() {
        val next = (EnhanceSettings.videoQuality(this) + 1) % 3
        EnhanceSettings.setVideoQuality(this, next)
        (player?.trackSelector as? androidx.media3.exoplayer.trackselection.DefaultTrackSelector)
            ?.let { Enhancer.applyVideo(it, next) }
        val names = arrayOf("Tự động", "Cao nhất", "Ổn định")
        android.widget.Toast.makeText(this, "Hình: ${names[next]}", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun cycleAudio() {
        val next = (EnhanceSettings.audioMode(this) + 1) % 5
        EnhanceSettings.setAudioMode(this, next)
        val sid = player?.audioSessionId ?: 0
        if (sid != 0) audioFx.attach(sid, next)
        // BUG-12: sid==0 thi listener onEvents se tu attach khi co am — toast noi dung that
        val names = arrayOf("Chuẩn", "Bass mạnh", "Rõ tiếng BLV", "Ban đêm", "Tự động (to & hay)")
        val suffix = if (sid == 0) " — áp dụng khi có âm thanh" else ""
        android.widget.Toast.makeText(this, "Âm: ${names[next]}$suffix", android.widget.Toast.LENGTH_SHORT).show()
    }

    // ================= PICTURE-IN-PICTURE =================
    private fun pipSupported(): Boolean =
        Build.VERSION.SDK_INT >= 26 &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun enterPip(manual: Boolean) {
        if (!pipSupported()) {
            if (manual) android.widget.Toast.makeText(this,
                "Máy không hỗ trợ hình trong hình", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val p = player ?: return
        if (p.playbackState == Player.STATE_IDLE) { if (manual) return; }
        try {
            val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
            if (Build.VERSION.SDK_INT >= 27) {
                val r = Rect()
                findViewById<PlayerView>(R.id.playerView).getGlobalVisibleRect(r)
                if (!r.isEmpty) b.setSourceRectHint(r)
            }
            enterPictureInPictureMode(b.build())
        } catch (e: Exception) {
            if (manual) android.widget.Toast.makeText(this,
                "Không mở được PIP: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // bấm HOME khi đang xem → tự co thành cửa sổ nổi, tiếng không ngắt
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && !isInPictureInPictureMode &&
            (player?.isPlaying == true)) enterPip(manual = false)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            topOverlay?.visibility = View.GONE
            findViewById<PlayerView>(R.id.playerView)?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
        } else {
            findViewById<PlayerView>(R.id.playerView)?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            showOverlay()
        }
    }

    private fun showOverlay() {
        topOverlay?.visibility = View.VISIBLE
        hideOnce()
    }

    private fun hideOnce() {
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 3500)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        showOverlay()
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) showOverlay()
        return super.dispatchTouchEvent(ev)
    }

    override fun onStop() {
        super.onStop()
        val inPip = Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode
        if (inPip && !isFinishing) return   // đang là cửa sổ nổi → giữ nguyên player
        handler.removeCallbacks(hideOverlay)
        audioFx.detach()
        player?.release()
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode) return
        player?.release(); player = null
    }
}
