package com.kenhlive.tv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var topOverlay: View? = null
    private val hideOverlay = Runnable { topOverlay?.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val url = intent.getStringExtra("url") ?: ""
        val name = intent.getStringExtra("name") ?: "Kênh"

        topOverlay = findViewById(R.id.topOverlay)
        findViewById<TextView>(R.id.playerTitle).text = name

        player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url)))
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
        findViewById<PlayerView>(R.id.playerView).player = player

        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }

        // Multi-view: mở grid với PHÒNG ĐANG XEM (ô đầu) + các phòng cùng trận
        findViewById<TextView>(R.id.multiBtn).setOnClickListener {
            val i = Intent(this, MultiViewActivity::class.java)
            i.putExtra("initial_room", name)
            i.putExtra("initial_url", url)
            startActivity(i)
        }

        topOverlay?.visibility = View.VISIBLE
        hideOnce()
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
        player?.release()
        player = null
    }
}
