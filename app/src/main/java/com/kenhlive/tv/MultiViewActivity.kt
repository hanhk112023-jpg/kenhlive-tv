package com.kenhlive.tv

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch

/**
 * Multiview v2: ĐÚNG 2 TRẬN cạnh nhau.
 * - CẢ HAI đều có tiếng (ô focus 100%, ô kia 55%)
 * - ←/→: chuyển focus giữa 2 trận
 * - OK (Enter/DPAD_CENTER): mở dialog chọn phòng/BLV cho trận đang focus → đổi trận cũng được
 * - MENU: hoán đổi 2 trận
 * - Tap 🔇: mute/unmute riêng ô đó
 * - BACK: thoát
 */
class MultiViewActivity : AppCompatActivity() {

    private class Slot(val root: FrameLayout) {
        val playerView: PlayerView = root.findViewById(R.id.cellPlayer)
        val label: TextView = root.findViewById(R.id.cellLabel)
        val audioBadge: TextView = root.findViewById(R.id.cellAudio)
        val swapHint: TextView = root.findViewById(R.id.cellSwapHint)
        var player: ExoPlayer? = null
        var group: LiveMatchGroup? = null
        var room: LiveRoom? = null
        var muted = false
    }

    private lateinit var slots: Array<Slot>
    private var focused = 0
    private var groups = listOf<LiveMatchGroup>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multiview)

        slots = arrayOf(
            Slot(findViewById(R.id.slot0)),
            Slot(findViewById(R.id.slot1))
        )

        // 2 trận ban đầu: trận được mở từ Player (initial_room) + trận hot kế tiếp
        val initialRoomName = intent.getStringExtra("initial_room")
        val initialUrl = intent.getStringExtra("initial_url")

        lifecycleScope.launch {
            groups = try { SocoliveRepository.groupRooms(SocoliveRepository.fetchLiveRooms()) }
                     catch (e: Exception) { emptyList() }
            if (groups.isEmpty()) { Toast.makeText(this@MultiViewActivity, "Không có trận live", Toast.LENGTH_LONG).show(); finish(); return@launch }

            // slot0: trận chứa phòng đang xem (khớp tên) hoặc trận top
            val wantMatch = initialRoomName?.substringBefore(" · ")
            var idx0 = if (wantMatch != null) groups.indexOfFirst { it.matchTitle == wantMatch } else -1
            if (idx0 < 0) idx0 = 0
            val idx1 = if (groups.size > 1) (idx0 + 1) % groups.size else idx0

            bindSlot(0, groups[idx0], if (initialUrl != null && idx0 == 0) initialUrl else null)
            if (idx1 != idx0) bindSlot(1, groups[idx1], null)
            else { slots[1].root.visibility = View.GONE }

            applyFocus()
        }

        // điều khiển từng ô
        slots.forEachIndexed { i, s ->
            s.root.setOnClickListener { requestFocusSlot(i) }
            s.audioBadge.setOnClickListener {
                s.muted = !s.muted
                s.audioBadge.text = if (s.muted) "TĨNH LẶNG" else "ÂM THANH"
                applyVolumes()
            }
        }
    }

    private fun bindSlot(i: Int, g: LiveMatchGroup, knownUrl: String?) {
        val s = slots[i]
        s.group = g
        s.room = g.top
        s.label.text = "${g.matchTitle} · ${g.top.blvName}"
        playInSlot(i, knownUrl ?: "FETCH")
    }

    /** knownUrl == "FETCH" → fetch stream theo roomNum. */
    private fun playInSlot(i: Int, knownUrl: String?) {
        val s = slots[i]
        lifecycleScope.launch {
            val url = when {
                knownUrl == null || knownUrl == "FETCH" -> s.room?.let { SocoliveRepository.fetchStream(it.roomNum) }
                else -> knownUrl
            }
            if (url == null) { s.label.text = "${s.label.text} · lỗi stream"; return@launch }
            s.player?.release()
            s.player = ExoPlayer.Builder(this@MultiViewActivity).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                    false
                )
                volume = 0f
                setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                prepare()
                playWhenReady = true
            }
            s.playerView.player = s.player
            applyVolumes()
        }
    }

    // ===== Điều khiển =====
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP ->
                { if (slots[1].root.visibility == View.VISIBLE && focused != 0) { focused = 0; applyFocus(); return true } }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN ->
                { if (slots[1].root.visibility == View.VISIBLE && focused != 1) { focused = 1; applyFocus(); return true } }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                openRoomPicker(focused); return true
            }
            KeyEvent.KEYCODE_MENU -> { swapSlots(); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun requestFocusSlot(i: Int) { focused = i; applyFocus() }

    private fun applyFocus() {
        slots.forEachIndexed { i, s ->
            val isFocus = i == focused
            s.root.background = if (isFocus) focusDrawable() else normalDrawable()
            s.swapHint.visibility = if (isFocus && slots[1].root.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        }
        slots[focused].root.requestFocus()
        applyVolumes()
    }

    private fun focusDrawable(): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF000000.toInt())
            setStroke(8, 0xFFFF3B30.toInt())
        }
    private fun normalDrawable(): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF000000.toInt())
            setStroke(2, 0xFF262626.toInt())
        }

    /** CẢ HAI đều nghe được: focus 100%, kia 55% (trừ khi mute riêng). */
    private fun applyVolumes() {
        slots.forEachIndexed { i, s ->
            val base = if (i == focused) 1.0f else 0.55f
            s.player?.volume = if (s.muted) 0f else base
        }
    }

    private fun swapSlots() {
        if (slots[1].root.visibility != View.VISIBLE) return
        val (a, b) = slots[0] to slots[1]
        val tmpPlayer = a.player; val tmpRoom = a.room; val tmpGroup = a.group; val tmpMuted = a.muted
        a.player = b.player; a.room = b.room; a.group = b.group; a.muted = b.muted
        b.player = tmpPlayer; b.room = tmpRoom; b.group = tmpGroup; b.muted = tmpMuted
        a.playerView.player = a.player; b.playerView.player = b.player
        a.label.text = fmtLabel(a); b.label.text = fmtLabel(b)
        a.audioBadge.text = if (a.muted) "TĨNH LẶNG" else "ÂM THANH"; b.audioBadge.text = if (b.muted) "TĨNH LẶNG" else "ÂM THANH"
        applyVolumes()
        Toast.makeText(this, "Đã hoán đổi 2 trận", Toast.LENGTH_SHORT).show()
    }

    private fun fmtLabel(s: Slot): String =
        s.group?.let { "${it.matchTitle} · ${s.room?.blvName ?: it.top.blvName}" } ?: ""

    /** Dialog chọn: các BLV trong trận hiện tại + các TRẬN khác (đổi cả trận được). */
    private fun openRoomPicker(i: Int) {
        val s = slots[i]
        val g = s.group ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_room_picker, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = g.matchTitle
        view.findViewById<TextView>(R.id.dialogLeague).text = "${g.league} · ${g.count} phòng · chọn BLV hoặc trận khác"
        val list = view.findViewById<LinearLayout>(R.id.roomList)
        val inf = LayoutInflater.from(this)

        // các phòng của trận này
        g.rooms.forEach { r ->
            val opt = inf.inflate(R.layout.item_room_option, list, false)
            opt.findViewById<TextView>(R.id.roomName).text = r.blvName
            opt.findViewById<TextView>(R.id.roomMeta).text = "${SocoliveRepository.fmtViewers(r.viewers)} lượt xem · LIVE"
            opt.setOnClickListener {
                s.room = r; s.label.text = fmtLabel(s)
                playInSlot(i, "FETCH")
                dialog?.dismiss()
            }
            list.addView(opt)
        }
        // divider + các trận khác để đổi trận
        val other = groups.filter { it !== g }.take(6)
        if (other.isNotEmpty()) {
            val div = TextView(this).apply {
                text = "ĐỔI SANG TRẬN KHÁC"
                setTextColor(0xFF94A3B8.toInt()); textSize = 12f
                setPadding(24, 18, 24, 8)
            }
            list.addView(div)
            other.forEach { og ->
                val opt = inf.inflate(R.layout.item_room_option, list, false)
                opt.findViewById<TextView>(R.id.roomName).text = og.matchTitle
                opt.findViewById<TextView>(R.id.roomMeta).text = "${og.league} · ${og.count} phòng · ${SocoliveRepository.fmtViewers(og.totalViewers)} lượt xem"
                opt.setOnClickListener {
                    s.group = og; s.room = og.top; s.label.text = fmtLabel(s)
                    playInSlot(i, "FETCH")
                    dialog?.dismiss()
                }
                list.addView(opt)
            }
        }
        dialog = AlertDialog.Builder(this).setView(view).create()
        dialog?.show()
    }

    private var dialog: AlertDialog? = null

    override fun onStop() {
        super.onStop()
        slots.forEach { it.player?.release(); it.player = null }
    }
}
