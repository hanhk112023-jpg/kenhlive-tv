package com.kenhlive.tv

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/**
 * Multiview: 2-4 stream song song trong grid 2 cột (TV: 2x2).
 * Âm thanh đi theo ô đang focus (D-pad); ô focus có viền vàng.
 */
class MultiViewActivity : AppCompatActivity() {
    private val players = mutableListOf<ExoPlayer?>()
    private val cells = mutableListOf<FrameLayout>()
    private var focusedIdx = 0
    private var names = arrayOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nums = intent?.getStringArrayExtra("roomNums") ?: arrayOf()
        names = intent?.getStringArrayExtra("names") ?: arrayOf()
        // mở từ Player: phòng đang xem làm ô đầu (cần fetch roomNum từ tên? — Player truyền thẳng stream URL)
        val initialUrl = intent?.getStringExtra("initial_url")
        val initialName = intent?.getStringExtra("initial_room")
        if (nums.isEmpty() && initialUrl == null) { finish(); return }

        val grid = GridLayout(this).apply {
            columnCount = 2
            rowCount = 2
            setBackgroundColor(0xFF0B0F19.toInt())
        }
        setContentView(grid)

        lifecycleScope.launch {
            // fetch stream song song; nếu mở từ Player thì ô đầu là phòng đang xem (URL sẵn có)
            val deferred = nums.map { n -> async { SocoliveRepository.fetchStream(n) } }.toMutableList()
            if (initialUrl != null) deferred.add(0, async { initialUrl })
            val urls = deferred.awaitAll()
            // tế bào không có stream vẫn thêm (đen + label lỗi) để giữ grid đều
            urls.forEachIndexed { i, url -> addCell(grid, i, url, i / 2, i % 2, urls.size) }
            if (players.isNotEmpty()) {
                players[focusedIdx]?.volume = 1f
            }
        }
    }

    private fun addCell(grid: GridLayout, idx: Int, url: String?, row: Int, col: Int, total: Int) {
        val cell = LayoutInflater.from(this).inflate(R.layout.item_player_cell, grid, false) as FrameLayout
        val rows = if (total <= 2) 1 else 2
        val lp = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED, 1f),
            GridLayout.spec(GridLayout.UNDEFINED, 1f)
        ).apply {
            width = 0; height = 0
            if (total <= 2) rowSpec = GridLayout.spec(0, 1f)
            else rowSpec = GridLayout.spec(row, 1f)
            columnSpec = GridLayout.spec(col, 1f)
        }
        cell.layoutParams = lp

        val label = cell.findViewById<TextView>(R.id.cellLabel)
        val initialName = intent?.getStringExtra("initial_room")
        label.text = when {
            idx == 0 && initialName != null -> initialName
            else -> names.getOrElse(idx) { "Kênh ${idx + 1}" }
        }

        if (url == null) {
            label.text = "${label.text} · không có stream"
            cell.isFocusable = false
        } else {
            val pv = cell.findViewById<PlayerView>(R.id.cellPlayer)
            val player = ExoPlayer.Builder(this).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                    /* handleAudioFocus = */ false   // multiview tự quản âm lượng
                )
                volume = 0f
                setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                prepare()
                playWhenReady = true
            }
            players.add(player)
            pv.player = player
            cell.isFocusable = true
            cell.isFocusableInTouchMode = true
            cell.setOnClickListener {
                focusedIdx = idx
                updateVolumes()
                Toast.makeText(this, "Âm thanh: ${names.getOrElse(idx) { "" }}", Toast.LENGTH_SHORT).show()
            }
            cell.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) { focusedIdx = idx; updateVolumes() }
                cell.background = getDrawable(
                    if (hasFocus || focusedIdx == idx) R.drawable.cell_focus else R.drawable.cell_normal
                )
            }
        }
        cell.background = getDrawable(if (idx == 0) R.drawable.cell_focus else R.drawable.cell_normal)
        cells.add(cell)
        grid.addView(cell)
    }

    private fun updateVolumes() {
        players.forEachIndexed { i, p -> p?.volume = if (i == focusedIdx) 1f else 0f }
    }

    override fun onStop() {
        super.onStop()
        players.forEach { it?.release() }
        players.clear()
    }
}
