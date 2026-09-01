package com.kenhlive.tv

import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val url = intent.getStringExtra("url") ?: ""
        val name = intent.getStringExtra("name") ?: "Kênh"
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
        // Multi-view: chỉ có ở màn hình đang xem — mở grid 2x2, phòng hiện tại làm ô đầu
        findViewById<TextView>(R.id.multiBtn).setOnClickListener {
            val i = android.content.Intent(this, MultiViewActivity::class.java)
            i.putExtra("initial_room", name)
            i.putExtra("initial_url", url)
            startActivity(i)
        }
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }
}
