package com.kenhlive.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.kenhlive.tv.ui.RoomPickerDialog
import kotlinx.coroutines.launch

/**
 * Multiview v4 — xem NHIỀU trận cùng lúc (2 hoặc 4 ô).
 * - CẢ các ô đều có tiếng: ô focus 100%, ô khác 55% (2 ô) / 40% (4 ô); mute riêng từng ô
 * - ỔN ĐỊNH: mỗi ô tự phục hồi (backoff 2·4·6·8·10s, tối đa 5 lần) + watchdog 15s
 * - TV: ←→↑↓ chuyển ô · OK đổi trận · giữ OK/MENU/INFO đổi bố cục · BACK thoát
 * - Phone: chạm ô để focus, chạm loa để mute
 */
class MultiViewActivity : AppCompatActivity() {

    private inner class Slot(val root: FrameLayout, val index: Int) {
        val playerView: PlayerView = root.findViewById(R.id.cellPlayer)
        val label: TextView = root.findViewById(R.id.cellLabel)
        val audioBadge: TextView = root.findViewById(R.id.cellAudio)
        val swapHint: TextView = root.findViewById(R.id.cellSwapHint)
        var player: ExoPlayer? = null
        var group: LiveMatchGroup? = null
        var room: LiveRoom? = null
        var muted = false
        var retry = 0
        var bufferingSince = 0L
        val fx = AudioEnhancer(this@MultiViewActivity)
    }

    private lateinit var slots: Array<Slot>
    private var focused = 0
    private var groups = listOf<LiveMatchGroup>()
    private var layoutN = 2
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null

    private val active get() = slots.take(layoutN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multiview)

        val slotRoots = listOf(
            findViewById<FrameLayout>(R.id.slot0), findViewById(R.id.slot1),
            findViewById(R.id.slot2), findViewById(R.id.slot3)
        )
        slots = Array(4) { i -> Slot(slotRoots[i], i) }

        val forceLayout = intent.getIntExtra("mv_layout", 0)
        layoutN = when {
            forceLayout in intArrayOf(2, 4) -> forceLayout
            DeviceMode.lowRam -> 2
            else -> getSharedPreferences("mv", MODE_PRIVATE).getInt("layout", 2)
        }
        findViewById<TextView>(R.id.layoutBtn)?.setOnClickListener { toggleLayout() }

        slots.forEachIndexed { i, s ->
            s.root.setOnClickListener { requestFocusSlot(i) }
            s.audioBadge.setOnClickListener { toggleMute(s) }
        }
        applyLayoutChrome()

        val initialRoomName = intent.getStringExtra("initial_room")
        val initialUrl = intent.getStringExtra("initial_url")

        lifecycleScope.launch {
            groups = try {
                SocoliveRepository.groupRooms(SocoliveRepository.fetchLiveRooms())
            } catch (_: Exception) { emptyList() }
            if (groups.isEmpty()) {
                Toast.makeText(this@MultiViewActivity, R.string.mv_load_error, Toast.LENGTH_LONG).show()
                finish(); return@launch
            }
            val wantMatch = initialRoomName?.substringBefore(" · ")
            var idx0 = if (wantMatch != null) groups.indexOfFirst { it.matchTitle == wantMatch } else -1
            if (idx0 < 0) idx0 = 0
            bindSlot(0, groups[idx0], initialUrl)
            val second = pickUnused(1, 0)
            if (second != null) bindSlot(1, second, null) else showWaitingSecond()
            if (layoutN == 4) {
                val t = pickUnused(2, 0, 1); val f = pickUnused(3, 0, 1, 2)
                if (t != null) bindSlot(2, t, null)
                if (f != null) bindSlot(3, f, null)
            }
            applyFocus()
            handler.postDelayed(watchdog, 15_000)
        }
    }

    private fun toggleMute(s: Slot) {
        s.muted = !s.muted
        s.audioBadge.text = getString(if (s.muted) R.string.mv_audio_off else R.string.mv_audio_on)
        applyVolumes()
    }

    private fun pickUnused(avoidIndex: Int, vararg takenIdx: Int): LiveMatchGroup? {
        val taken = takenIdx.toList().mapNotNull { slots[it].group?.matchTitle }.toHashSet()
        return groups.firstOrNull { it.matchTitle !in taken }
    }

    // ===== BỐ CỤC 2 ⇄ 4 =====
    private fun toggleLayout() {
        if (DeviceMode.lowRam) {
            Toast.makeText(this, R.string.mv_lowram_lock, Toast.LENGTH_SHORT).show(); return
        }
        layoutN = if (layoutN == 2) 4 else 2
        getSharedPreferences("mv", MODE_PRIVATE).edit().putInt("layout", layoutN).apply()
        applyLayoutChrome()
        if (layoutN == 4) {
            if (groups.isEmpty()) return
            val t = pickUnused(2, 0, 1); val f = pickUnused(3, 0, 1, 2)
            if (t == null && slots[2].group == null) {
                Toast.makeText(this, R.string.mv_not_enough, Toast.LENGTH_SHORT).show()
            }
            if (t != null && slots[2].group?.matchTitle != t.matchTitle) bindSlot(2, t, null)
            if (f != null && slots[3].group?.matchTitle != f.matchTitle) bindSlot(3, f, null)
            if (focused >= layoutN) focused = 0
            applyFocus()
        } else {
            if (focused >= 2) { focused = 0; applyFocus() }
        }
    }

    private fun applyLayoutChrome() {
        val row0 = findViewById<LinearLayout>(R.id.mvRow0)
        if (layoutN == 4) {
            row0.orientation = LinearLayout.HORIZONTAL
            for (i in 0..1) {
                slots[i].root.layoutParams =
                    LinearLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT, 1f)
            }
        } else {
            row0.orientation = LinearLayout.VERTICAL
            for (i in 0..1) {
                slots[i].root.layoutParams =
                    LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
        }
        findViewById<View>(R.id.mvRow1).visibility = if (layoutN == 4) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.layoutBtn).text = getString(R.string.mv_layout_btn, layoutN)
        findViewById<TextView>(R.id.hintText).text = getString(
            if (DeviceMode.isTv) {
                if (layoutN == 4) R.string.mv_hint_4 else R.string.mv_hint_2
            } else R.string.mv_hint_phone
        )
        if (layoutN == 2) for (i in 2..3) {
            val s = slots[i]
            if (s.group != null) {
                s.player?.release(); s.fx.detach(); s.player = null
                s.playerView.player = null; s.group = null
            }
        }
    }

    // ===== PHÁT + TỰ PHỤC HỒI =====
    private fun bindSlot(i: Int, g: LiveMatchGroup, knownUrl: String?) {
        val s = slots[i]
        s.group = g; s.room = g.top; s.retry = 0
        s.label.text = "${g.matchTitle} · ${g.top.blvName}"
        playInSlot(i, knownUrl ?: "FETCH")
    }

    private fun playInSlot(i: Int, knownUrl: String?) {
        val s = slots[i]
        handler.removeCallbacks(secondWatcher)
        lifecycleScope.launch {
            s.label.text = (s.group?.matchTitle ?: getString(R.string.mv_slot, i + 1)) +
                " " + getString(R.string.mv_loading_stream)
            val url = if (knownUrl != null && knownUrl != "FETCH") knownUrl else resolveStream(s)
            if (url == null) {
                s.label.text = "${s.group?.matchTitle ?: ""} ${getString(R.string.mv_stream_missing)}"
                return@launch
            }
            s.player?.release(); s.fx.detach()
            s.player = ExoPlayer.Builder(this@MultiViewActivity)
                .setTrackSelector(Enhancer.buildTrackSelector(this@MultiViewActivity))
                .setLoadControl(Enhancer.buildLoadControl(this@MultiViewActivity))
                .build().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false
                    )
                    volume = 0f
                    setMediaItem(Enhancer.buildMediaItem(url))
                    prepare()
                    playWhenReady = true
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) { slotRecover(i, "lỗi sóng") }
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_BUFFERING) {
                                if (s.bufferingSince == 0L) s.bufferingSince = System.currentTimeMillis()
                            } else s.bufferingSince = 0L
                            if (state == Player.STATE_READY) s.retry = 0
                        }
                    })
                }
            s.playerView.player = s.player
            s.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            s.fx.attach(s.player!!.audioSessionId, EnhanceSettings.audioMode(this@MultiViewActivity))
            applyVolumes()
        }
    }

    private fun slotRecover(i: Int, why: String) {
        val s = slots[i]
        if (s.group == null || s.player == null) return
        if (s.retry >= 5) {
            s.label.text = "${s.group?.matchTitle} · mất sóng · OK để chọn trận"
            return
        }
        s.retry++
        s.label.text = "${s.group?.matchTitle} · $why — tự thử lại ${s.retry}/5…"
        handler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (layoutN == 2 && i >= 2) return@postDelayed
            playInSlot(i, "FETCH")
        }, 2000L * s.retry)
    }

    private val watchdog = object : Runnable {
        override fun run() {
            val self = this
            for (s in active) {
                val p = s.player ?: continue
                if (p.playbackState == Player.STATE_BUFFERING &&
                    s.bufferingSince > 0 && System.currentTimeMillis() - s.bufferingSince > 30_000
                ) {
                    s.bufferingSince = 0; slotRecover(s.index, "kẹt hình")
                } else if (p.playbackState == Player.STATE_IDLE) {
                    slotRecover(s.index, "rơi sóng")
                }
            }
            handler.postDelayed(self, 15_000)
        }
    }

    private suspend fun resolveStream(s: Slot): String? {
        val g = s.group ?: return null
        val tried = mutableListOf<Pair<LiveMatchGroup, LiveRoom>>()
        s.room?.let { tried += (g to it) }
        tried += g.rooms.filter { it !== s.room }.map { g to it }
        for (og in groups.filter { it !== g }) tried += og.rooms.take(2).map { og to it }
        for ((og, r) in tried.take(8)) {
            SocoliveRepository.fetchStream(r.roomNum)?.let {
                s.group = og; s.room = r; s.label.text = fmtLabel(s); return it
            }
        }
        return null
    }

    // ===== ĐIỀU HƯỚNG =====
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { moveFocus(-1, 0); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { moveFocus(1, 0); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { moveFocus(0, -1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveFocus(0, 1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount >= 3 && (dialog?.isShowing != true)) { toggleLayout(); return true }
                if (event.repeatCount < 3 && slots[focused].group != null) openRoomPicker(focused)
                return true
            }
            KeyEvent.KEYCODE_MENU -> { swapWithNext(); return true }
            KeyEvent.KEYCODE_INFO -> { toggleLayout(); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun moveFocus(dx: Int, dy: Int) {
        val cols = if (layoutN == 4) 2 else 1
        val rows = layoutN / cols
        val r = focused / cols
        val c = focused % cols
        val nr = r + dy
        val nc = c + dx
        if (nr < 0 || nr >= rows || nc < 0 || nc >= cols) return
        val t = nr * cols + nc
        if (t in 0 until layoutN) { focused = t; applyFocus() }
    }

    private fun requestFocusSlot(i: Int) { if (i < layoutN) { focused = i; applyFocus() } }

    private fun applyFocus() {
        active.forEach { s ->
            val isFocus = s.index == focused
            s.root.animate().scaleX(if (isFocus) 1.035f else 1f).scaleY(if (isFocus) 1.035f else 1f)
                .setDuration(150).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            s.root.elevation = if (isFocus) 14f else 0f
            s.root.foreground = if (isFocus) focusDrawable() else normalDrawable()
            s.swapHint.visibility = if (isFocus && s.group != null) View.VISIBLE else View.GONE
        }
        slots[focused].root.requestFocus()
        syncFocusBorder()
        applyVolumes()
    }

    private fun syncFocusBorder() {
        val border = findViewById<View?>(R.id.focusBorder) ?: return
        val target = slots[focused].root
        border.post {
            if (target.width == 0) return@post
            val sc = 1.035f
            val w = (target.width * sc).toInt(); val h = (target.height * sc).toInt()
            val cx = target.x + target.width / 2f; val cy = target.y + target.height / 2f
            val lp = border.layoutParams
            if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; border.layoutParams = lp }
            border.foreground = focusDrawable()
            border.animate()
                .x(cx - w / 2f).y(cy - h / 2f)
                .setDuration(160).setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun focusDrawable(): android.graphics.drawable.Drawable {
        val back = android.graphics.drawable.GradientDrawable().apply {
            setColor(0x00000000); setStroke(12, 0xB3000000.toInt()); cornerRadius = 12f
        }
        val line = android.graphics.drawable.GradientDrawable().apply {
            setColor(0x00000000); setStroke(5, 0xFFFFFFFF.toInt()); cornerRadius = 12f
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(back, line))
    }

    private fun normalDrawable(): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(0x00000000); setStroke(2, 0xFF262626.toInt())
        }

    private fun applyVolumes() {
        val dim = if (layoutN == 4) 0.40f else 0.55f
        active.forEach { s ->
            s.player?.volume = if (s.muted) 0f else if (s.index == focused) 1f else dim
        }
    }

    private fun swapWithNext() {
        val other = if (layoutN == 4) (focused + 2) % 4 else (focused + 1) % 2
        val a = slots[focused]; val b = slots[other]
        if (a.group == null || b.group == null) return
        val tp = a.player; val tg = a.group; val tr = a.room; val tm = a.muted; val tl = a.label.text
        val tr2 = a.retry; val tb = a.bufferingSince
        a.player = b.player; a.group = b.group; a.room = b.room; a.muted = b.muted
        a.label.text = b.label.text
        a.retry = b.retry; a.bufferingSince = b.bufferingSince
        b.player = tp; b.group = tg; b.room = tr; b.muted = tm; b.label.text = tl
        b.retry = tr2; b.bufferingSince = tb
        a.playerView.player = a.player; b.playerView.player = b.player
        a.fx.detach(); b.fx.detach()
        a.player?.let { a.fx.attach(it.audioSessionId, EnhanceSettings.audioMode(this)) }
        b.player?.let { b.fx.attach(it.audioSessionId, EnhanceSettings.audioMode(this)) }
        a.audioBadge.text = getString(if (a.muted) R.string.mv_audio_off else R.string.mv_audio_on)
        b.audioBadge.text = getString(if (b.muted) R.string.mv_audio_off else R.string.mv_audio_on)
        applyVolumes()
        Toast.makeText(this, R.string.mv_swapped, Toast.LENGTH_SHORT).show()
    }

    private fun fmtLabel(s: Slot): String =
        s.group?.let { "${it.matchTitle} · ${s.room?.blvName ?: it.top.blvName}" } ?: ""

    // ===== CHỜ TRẬN 2 + PICKER =====
    private fun showWaitingSecond() {
        val s = slots[1]
        s.player?.release(); s.player = null; s.fx.detach()
        s.playerView.player = null
        s.label.text = getString(R.string.mv_waiting_2)
        s.root.visibility = View.VISIBLE
        s.swapHint.visibility = View.GONE
        handler.postDelayed(secondWatcher, 20_000)
    }

    private val secondWatcher = object : Runnable {
        override fun run() {
            val self = this
            lifecycleScope.launch {
                try {
                    val gs = SocoliveRepository.groupRooms(SocoliveRepository.fetchLiveRooms())
                    if (gs.isNotEmpty()) groups = gs
                    val need = if (layoutN == 4) listOf(0, 1, 2, 3) else listOf(0, 1)
                    var changed = false
                    for (i in need) {
                        if (slots[i].group == null) {
                            val g = pickUnused(i, *(need.filter { it != i }.toIntArray()))
                            if (g != null) { bindSlot(i, g, null); changed = true }
                        }
                    }
                    if (changed) {
                        Toast.makeText(this@MultiViewActivity, R.string.mv_added, Toast.LENGTH_SHORT).show()
                        applyFocus()
                    } else handler.postDelayed(self, 20_000)
                } catch (_: Exception) { handler.postDelayed(self, 20_000) }
            }
        }
    }

    private fun openRoomPicker(i: Int) {
        val s = slots[i]
        val g = s.group ?: return
        dialog?.dismiss()
        dialog = RoomPickerDialog.show(
            this, g,
            onPickRoom = { r ->
                s.room = r; s.label.text = fmtLabel(s); s.retry = 0
                playInSlot(i, "FETCH")
            },
            otherGroups = groups,
            onPickGroup = { og ->
                s.group = og; s.room = og.top; s.retry = 0; s.label.text = fmtLabel(s)
                playInSlot(i, "FETCH")
            }
        )
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(secondWatcher)
        handler.removeCallbacks(watchdog)
        slots.forEach { it.player?.release(); it.player = null; it.fx.detach() }
    }

    override fun onStart() {
        super.onStart()
        if (groups.isEmpty()) return
        var rebuilt = false
        slots.take(layoutN).forEachIndexed { i, sl ->
            if (sl.player == null && sl.group != null) {
                playInSlot(i, null)
                rebuilt = true
            }
        }
        if (rebuilt) {
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, 15_000)
            handler.removeCallbacks(secondWatcher)
            handler.postDelayed(secondWatcher, 20_000)
        }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }
}
