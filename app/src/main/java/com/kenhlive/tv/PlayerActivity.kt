package com.kenhlive.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
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
    private var bottomOverlay: View? = null
    private val hideOverlay = Runnable {
        topOverlay?.visibility = View.GONE
        bottomOverlay?.visibility = View.GONE
    }
    private val audioFx = AudioEnhancer(this)
    private var url: String = ""
    private var streamRetries = 0
    private var isLowRam = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        isLowRam = DeviceMode.isLowRamDevice(this)

        url = intent.getStringExtra("url") ?: ""
        val name = intent.getStringExtra("name") ?: "Kênh"

        topOverlay = findViewById(R.id.topOverlay)
        bottomOverlay = findViewById(R.id.bottomOverlay)
        findViewById<TextView>(R.id.playerTitle).text = name

        player = ExoPlayer.Builder(this)
            .setTrackSelector(Enhancer.buildTrackSelector(this))
            .setLoadControl(Enhancer.buildLoadControl(this, isMultiView = false))
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
                            android.widget.Toast.makeText(
                                this@PlayerActivity,
                                "Mạng chập chờn — tự thử lại lần $streamRetries…", android.widget.Toast.LENGTH_SHORT
                            ).show()
                            handler.postDelayed({ player?.prepare() }, 2000L * streamRetries)
                        } else {
                            val msg = if (isNet) "Mạng lỗi — thoát ra vào lại sau" else "Stream lỗi: ${error.errorCodeName}"
                            android.widget.Toast.makeText(this@PlayerActivity, msg, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            // hide loading if any
                        }
                    }
                })
            }
        val pv = findViewById<PlayerView>(R.id.playerView)
        pv.player = player
        pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

        player?.let { p ->
            p.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    val sid = (player as? ExoPlayer)?.audioSessionId ?: 0
                    if (sid != 0 && audioFx.notAttached) {
                        // Low RAM: skip heavy FX if needed? Keep but lightweight
                        audioFx.attach(sid, EnhanceSettings.audioMode(this@PlayerActivity))
                    }
                }
            })
        }

        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.qualityBtn).setOnClickListener { showQualityDialog() }

        findViewById<TextView>(R.id.multiBtn).setOnClickListener {
            val i = Intent(this, MultiViewActivity::class.java)
            i.putExtra("initial_room", name)
            i.putExtra("initial_url", url)
            startActivity(i)
        }

        topOverlay?.visibility = View.VISIBLE
        bottomOverlay?.visibility = View.VISIBLE
        hideOnce()
    }

    private fun showQualityDialog() {
        val vq = EnhanceSettings.videoQuality(this)
        val aq = EnhanceSettings.audioMode(this)
        val vqNames = arrayOf("Tự động", "Cao nhất (nét)", "Ổn định (mượt)")
        val aqNames = arrayOf("Chuẩn", "Bass mạnh", "Rõ tiếng BLV", "Ban đêm (êm)", "Tự động (to & hay)")
        val lowRamNote = if (isLowRam) "\n\n⚠ TV RAM thấp: tự giới hạn 1080p/720p để ổn định\n" else ""
        val msg = "Hình: ${vqNames[vq]}\nÂm: ${aqNames[aq]}$lowRamNote\n\nChọn hình:\n" +
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
            ?.let { Enhancer.applyVideo(it, next, this) }
        val names = arrayOf("Tự động", "Cao nhất", "Ổn định")
        android.widget.Toast.makeText(this, "Hình: ${names[next]}${if (isLowRam) " (giới hạn RAM)" else ""}", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun cycleAudio() {
        val next = (EnhanceSettings.audioMode(this) + 1) % 5
        EnhanceSettings.setAudioMode(this, next)
        player?.let { audioFx.attach(it.audioSessionId, next) }
        val names = arrayOf("Chuẩn", "Bass mạnh", "Rõ tiếng BLV", "Ban đêm", "Tự động (to & hay)")
        android.widget.Toast.makeText(this, "Âm: ${names[next]}", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showOverlay() {
        topOverlay?.visibility = View.VISIBLE
        bottomOverlay?.visibility = View.VISIBLE
        hideOnce()
    }

    private fun hideOnce() {
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 4000)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        showOverlay()
        if (e.action == KeyEvent.ACTION_DOWN) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_M -> {
                    showQualityDialog()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) showOverlay()
        return super.dispatchTouchEvent(ev)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(hideOverlay)
        try {
            player?.pause()
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(hideOverlay)
        audioFx.detach()
        try {
            player?.release()
        } catch (_: Exception) {}
        player = null
        findViewById<PlayerView>(R.id.playerView)?.player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        audioFx.detach()
        try {
            player?.release()
        } catch (_: Exception) {}
        player = null
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            // Reduce buffer if needed - player already uses low RAM config
            if (isLowRam && level >= TRIM_MEMORY_RUNNING_CRITICAL) {
                try {
                    player?.pause()
                    android.widget.Toast.makeText(this, "RAM thấp: tạm dừng để tránh crash", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
        }
    }
}
