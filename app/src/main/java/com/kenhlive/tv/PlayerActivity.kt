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
    private val hideOverlay = Runnable { topOverlay?.visibility = View.GONE }
    private val audioFx = AudioEnhancer(this)
    private var url: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        url = intent.getStringExtra("url") ?: ""
        val name = intent.getStringExtra("name") ?: "Kênh"

        topOverlay = findViewById(R.id.topOverlay)
        findViewById<TextView>(R.id.playerTitle).text = name

        player = ExoPlayer.Builder(this)
            .setTrackSelector(Enhancer.buildTrackSelector(this))
            .setLoadControl(Enhancer.buildLoadControl())
            .build().apply {
                setMediaItem(Enhancer.buildMediaItem(url))
                prepare()
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        val msg = when (error.errorCode) {
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Mạng lỗi — thử lại sau"
                            else -> "Stream lỗi: ${error.errorCodeName}"
                        }
                        android.widget.Toast.makeText(this@PlayerActivity, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                })
            }
        val pv = findViewById<PlayerView>(R.id.playerView)
        pv.player = player
        pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

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

        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.qualityBtn).setOnClickListener { showQualityDialog() }

        findViewById<TextView>(R.id.multiBtn).setOnClickListener {
            val i = Intent(this, MultiViewActivity::class.java)
            i.putExtra("initial_room", name)
            i.putExtra("initial_url", url)
            startActivity(i)
        }

        topOverlay?.visibility = View.VISIBLE
        hideOnce()
    }

    private fun showQualityDialog() {
        val vq = EnhanceSettings.videoQuality(this)
        val aq = EnhanceSettings.audioMode(this)
        val vqNames = arrayOf("Tự động", "Cao nhất (nét)", "Ổn định (mượt)")
        val aqNames = arrayOf("Chuẩn", "Bass mạnh", "Rõ tiếng BLV", "Ban đêm (êm)")
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
        val next = (EnhanceSettings.audioMode(this) + 1) % 4
        EnhanceSettings.setAudioMode(this, next)
        player?.let { audioFx.attach(it.audioSessionId, next) }
        val names = arrayOf("Chuẩn", "Bass mạnh", "Rõ tiếng BLV", "Ban đêm")
        android.widget.Toast.makeText(this, "Âm: ${names[next]}", android.widget.Toast.LENGTH_SHORT).show()
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
        handler.removeCallbacks(hideOverlay)
        audioFx.detach()
        player?.release()
        player = null
    }
}
