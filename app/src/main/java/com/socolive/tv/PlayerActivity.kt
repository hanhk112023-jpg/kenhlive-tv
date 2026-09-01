package com.socolive.tv

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var url: String = ""
    private var name: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        url = intent.getStringExtra("url") ?: ""
        name = intent.getStringExtra("name") ?: "Kênh"

        findViewById<TextView>(R.id.playerTitle).text = name

        val playerView = findViewById<PlayerView>(R.id.playerView)
        player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            prepare()
            playWhenReady = true
        }
        playerView.player = player

        findViewById<Button>(R.id.backBtn).setOnClickListener {
            player?.release()
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }
}
