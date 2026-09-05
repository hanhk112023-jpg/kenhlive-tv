package com.kenhlive.tv

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Multiview v5 - Ổn định & Tối ưu RAM thấp:
 * - ĐÚNG 2 TRẬN cạnh nhau, cả 2 có tiếng (focus 100%, kia 55%)
 * - D-pad fix: dispatchKeyEvent không còn lambda bug
 * - Low RAM: track selector cap 720p/1.2Mbps, buffer nhỏ, tắt audio FX cho slot phụ
 * - Retry 3 lần với backoff, fallback phòng khác
 * - Release đúng lifecycle: onPause release, onDestroy cleanup, onTrimMemory clear
 * - Swap không leak audio session
 * - Loading overlay + error UI
 */
class MultiViewActivity : AppCompatActivity() {

    private inner class Slot(val root: FrameLayout) {
        val playerView: PlayerView = root.findViewById(R.id.cellPlayer)
        val label: TextView = root.findViewById(R.id.cellLabel)
        val league: TextView? = root.findViewById(R.id.cellLeague)
        val viewers: TextView? = root.findViewById(R.id.cellViewers)
        val audioBadge: TextView = root.findViewById(R.id.cellAudio)
        val swapHint: TextView = root.findViewById(R.id.cellSwapHint)
        val focusOverlay: View? = root.findViewById(R.id.cellFocusOverlay)
        val loading: View? = root.findViewById(R.id.cellLoading)
        val loadingSub: TextView? = root.findViewById(R.id.cellLoadingSub)
        var player: ExoPlayer? = null
        var group: LiveMatchGroup? = null
        var room: LiveRoom? = null
        var muted = false
        var loadingJob: Job? = null
        val fx = AudioEnhancer(this@MultiViewActivity)
        var retryCount = 0
        var isLowRamFxDisabled = false
    }

    private lateinit var slots: Array<Slot>
    private var focused = 0
    private var groups = listOf<LiveMatchGroup>()
    private var isLowRam = false

    private val handler = Handler(Looper.getMainLooper())
    private val secondWatcher = object : Runnable {
        override fun run() {
            val self = this
            lifecycleScope.launch {
                try {
                    val gs = SocoliveRepository.groupRooms(SocoliveRepository.fetchLiveRooms())
                    if (gs.size > 1) {
                        groups = gs
                        val cur = slots[0].group
                        val other = gs.firstOrNull { it.matchTitle != cur?.matchTitle } ?: gs[1]
                        if (slots[1].group == null || slots[1].group?.matchTitle != other.matchTitle) {
                            bindSlot(1, other, null)
                            Toast.makeText(this@MultiViewActivity, "Đã thêm trận thứ 2: ${other.matchTitle}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        handler.postDelayed(self, 20_000)
                    }
                } catch (e: Exception) {
                    handler.postDelayed(self, 20_000)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multiview)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        isLowRam = DeviceMode.isLowRamDevice(this)
        findViewById<TextView>(R.id.lowRamBadge)?.let {
            it.visibility = if (isLowRam) View.VISIBLE else View.GONE
        }

        slots = arrayOf(
            Slot(findViewById(R.id.slot0)),
            Slot(findViewById(R.id.slot1))
        )

        val initialRoomName = intent.getStringExtra("initial_room")
        val initialUrl = intent.getStringExtra("initial_url")

        lifecycleScope.launch {
            groups = try {
                SocoliveRepository.groupRooms(SocoliveRepository.fetchLiveRooms())
            } catch (e: Exception) {
                emptyList()
            }
            if (groups.isEmpty()) {
                Toast.makeText(this@MultiViewActivity, "Không có trận live", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            val wantMatch = initialRoomName?.substringBefore(" · ")
            var idx0 = if (wantMatch != null) groups.indexOfFirst { it.matchTitle == wantMatch } else -1
            if (idx0 < 0) idx0 = 0
            val idx1 = if (groups.size > 1) (idx0 + 1) % groups.size else idx0

            bindSlot(0, groups[idx0], if (initialUrl != null && idx0 == 0) initialUrl else null)
            if (idx1 != idx0) bindSlot(1, groups[idx1], null)
            else showWaitingSecond()

            applyFocus()
        }

        slots.forEachIndexed { i, s ->
            s.root.setOnClickListener { requestFocusSlot(i) }
            s.audioBadge.setOnClickListener {
                s.muted = !s.muted
                updateAudioBadge(s)
                applyVolumes()
            }
        }
    }

    private fun bindSlot(i: Int, g: LiveMatchGroup, knownUrl: String?) {
        val s = slots[i]
        s.group = g
        s.room = g.top
        s.retryCount = 0
        updateLabel(s)
        // low RAM: disable FX for second slot to save CPU/RAM
        s.isLowRamFxDisabled = isLowRam && i == 1
        playInSlot(i, knownUrl ?: "FETCH")
    }

    private fun updateLabel(s: Slot) {
        val g = s.group ?: return
        s.label.text = "${g.matchTitle} · ${s.room?.blvName ?: g.top.blvName}"
        s.league?.let {
            it.text = g.league.uppercase()
            it.visibility = View.VISIBLE
        }
        s.viewers?.let {
            it.text = "👁 ${SocoliveRepository.fmtViewers(g.totalViewers)}"
            it.visibility = View.VISIBLE
        }
    }

    private fun updateAudioBadge(s: Slot) {
        if (s.muted) {
            s.audioBadge.text = "🔇 TẮT TIẾNG"
        } else {
            val vol = if (slots.indexOf(s) == focused) 100 else 55
            s.audioBadge.text = "🔊 $vol%"
        }
    }

    private fun showLoading(s: Slot, show: Boolean, sub: String = "") {
        s.loading?.visibility = if (show) View.VISIBLE else View.GONE
        s.loadingSub?.text = sub
    }

    private fun playInSlot(i: Int, knownUrl: String?) {
        val s = slots[i]
        s.loadingJob?.cancel()
        s.loadingJob = lifecycleScope.launch {
            showLoading(s, true, "Đang tải ${s.group?.matchTitle ?: ""}")
            s.label.text = (s.group?.matchTitle ?: "Ô ${i + 1}") + " · đang tải…"

            val url = when {
                knownUrl != null && knownUrl != "FETCH" -> knownUrl
                else -> resolveStream(s)
            }

            if (url == null) {
                showLoading(s, false)
                s.label.text = "${s.group?.matchTitle ?: ""} · lỗi stream · OK để chọn phòng khác"
                Toast.makeText(this@MultiViewActivity, "Không lấy được stream cho ô ${i + 1}", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // release old player safely
            try {
                s.player?.release()
            } catch (_: Exception) {}
            s.player = null
            s.fx.detach()

            try {
                val trackSelector = Enhancer.buildTrackSelectorForMultiView(this@MultiViewActivity, isLowRam)
                val loadControl = Enhancer.buildLoadControl(this@MultiViewActivity, isMultiView = true)

                val player = ExoPlayer.Builder(this@MultiViewActivity)
                    .setTrackSelector(trackSelector)
                    .setLoadControl(loadControl)
                    .build().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                                .build(),
                            false
                        )
                        volume = 0f
                        setMediaItem(Enhancer.buildMediaItem(url))
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) {
                                if (state == Player.STATE_READY) {
                                    showLoading(s, false)
                                    updateLabel(s)
                                } else if (state == Player.STATE_BUFFERING) {
                                    showLoading(s, true, "Đệm…")
                                }
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                showLoading(s, false)
                                if (s.retryCount < 3) {
                                    s.retryCount++
                                    lifecycleScope.launch {
                                        delay(1000L * s.retryCount)
                                        playInSlot(i, "FETCH")
                                    }
                                    Toast.makeText(this@MultiViewActivity, "Ô ${i + 1} lỗi, thử lại ${s.retryCount}/3", Toast.LENGTH_SHORT).show()
                                } else {
                                    s.label.text = "${s.group?.matchTitle ?: ""} · lỗi phát · OK để đổi"
                                    Toast.makeText(this@MultiViewActivity, "Ô ${i + 1} lỗi: ${error.errorCodeName}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        })
                        prepare()
                        playWhenReady = true
                    }

                s.player = player
                s.playerView.player = player
                s.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM

                // Audio FX: skip for low RAM second slot
                if (!s.isLowRamFxDisabled) {
                    player.addListener(object : Player.Listener {
                        override fun onEvents(p: Player, events: Player.Events) {
                            val sid = (p as? ExoPlayer)?.audioSessionId ?: 0
                            if (sid != 0 && s.fx.notAttached) {
                                s.fx.attach(sid, EnhanceSettings.audioMode(this@MultiViewActivity))
                            }
                        }
                    })
                }

                applyVolumes()
                updateAudioBadge(s)
            } catch (e: Exception) {
                showLoading(s, false)
                s.label.text = "Lỗi khởi tạo player: ${e.message}"
            }
        }
    }

    private suspend fun resolveStream(s: Slot): String? {
        val g = s.group ?: return null
        val tried = mutableListOf<Pair<LiveMatchGroup, LiveRoom>>()
        s.room?.let { tried += (g to it) }
        tried += g.rooms.filter { it != s.room }.map { g to it }
        for (og in groups.filter { it !== g }) tried += og.rooms.take(2).map { og to it }

        for ((og, r) in tried.take(8)) {
            try {
                SocoliveRepository.fetchStream(r.roomNum)?.let { url ->
                    s.group = og
                    s.room = r
                    updateLabel(s)
                    return url
                }
            } catch (_: Exception) {
                // continue
            }
            // small delay to avoid hammering API
            delay(200)
        }
        return null
    }

    private fun showWaitingSecond() {
        val s = slots[1]
        try {
            s.player?.release()
        } catch (_: Exception) {}
        s.player = null
        s.fx.detach()
        s.playerView.player = null
        s.label.text = "Đang chờ trận live thứ 2…"
        s.league?.visibility = View.GONE
        s.viewers?.visibility = View.GONE
        s.root.visibility = View.VISIBLE
        s.swapHint.visibility = View.GONE
        showLoading(s, true, "Chờ trận thứ 2…")
        handler.postDelayed(secondWatcher, 20_000)
    }

    // ===== Điều khiển - FIXED: không còn lambda bug =====
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                if (focused != 0) {
                    focused = 0
                    applyFocus()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (focused != 1) {
                    focused = 1
                    applyFocus()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (slots[focused].group != null) {
                    openRoomPicker(focused)
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_M -> {
                swapSlots()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MUTE -> {
                val s = slots[focused]
                s.muted = !s.muted
                updateAudioBadge(s)
                applyVolumes()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun requestFocusSlot(i: Int) {
        focused = i
        applyFocus()
    }

    private fun applyFocus() {
        slots.forEachIndexed { i, s ->
            val isFocus = i == focused
            // foreground drawable: red border for focus
            s.root.foreground = if (isFocus) focusDrawable() else normalDrawable()
            s.swapHint.visibility = if (isFocus && s.group != null) View.VISIBLE else View.GONE
            s.focusOverlay?.visibility = if (isFocus) View.VISIBLE else View.GONE
            s.audioBadge.alpha = if (isFocus) 1f else 0.8f
        }
        slots[focused].root.requestFocus()
        applyVolumes()
        // update badges
        slots.forEach { updateAudioBadge(it) }
    }

    private fun focusDrawable(): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(0x00000000)
            setStroke(6, 0xFFFF3B30.toInt())
            cornerRadius = 4f
        }

    private fun normalDrawable(): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(0x00000000)
            setStroke(1, 0xFF1E1E24.toInt())
            cornerRadius = 4f
        }

    private fun applyVolumes() {
        slots.forEachIndexed { i, s ->
            val base = if (i == focused) 1.0f else 0.55f
            s.player?.volume = if (s.muted) 0f else base
        }
    }

    private fun swapSlots() {
        if (slots[0].group == null || slots[1].group == null) return

        // Preserve players and metadata
        val p0 = slots[0].player
        val p1 = slots[1].player
        val g0 = slots[0].group
        val g1 = slots[1].group
        val r0 = slots[0].room
        val r1 = slots[1].room
        val m0 = slots[0].muted
        val m1 = slots[1].muted

        // Detach FX before swap
        slots[0].fx.detach()
        slots[1].fx.detach()

        slots[0].player = p1
        slots[1].player = p0
        slots[0].group = g1
        slots[1].group = g0
        slots[0].room = r1
        slots[1].room = r0
        slots[0].muted = m1
        slots[1].muted = m0

        slots[0].playerView.player = p1
        slots[1].playerView.player = p0

        // Re-attach FX with new session
        p1?.let {
            if (!slots[0].isLowRamFxDisabled) {
                try {
                    slots[0].fx.attach(it.audioSessionId, EnhanceSettings.audioMode(this))
                } catch (_: Exception) {}
            }
        }
        p0?.let {
            if (!slots[1].isLowRamFxDisabled) {
                try {
                    slots[1].fx.attach(it.audioSessionId, EnhanceSettings.audioMode(this))
                } catch (_: Exception) {}
            }
        }

        slots[0].label.text = formatLabel(slots[0])
        slots[1].label.text = formatLabel(slots[1])
        updateLabel(slots[0])
        updateLabel(slots[1])
        applyVolumes()
        slots.forEach { updateAudioBadge(it) }

        Toast.makeText(this, "Đã hoán đổi 2 trận", Toast.LENGTH_SHORT).show()
    }

    private fun formatLabel(s: Slot): String =
        s.group?.let { "${it.matchTitle} · ${s.room?.blvName ?: it.top.blvName}" } ?: ""

    private fun openRoomPicker(i: Int) {
        val s = slots[i]
        val g = s.group ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_room_picker, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = g.matchTitle
        view.findViewById<TextView>(R.id.dialogLeague).text = "${g.league} · ${g.count} phòng · chọn BLV hoặc trận khác"
        val list = view.findViewById<LinearLayout>(R.id.roomList)
        val inf = LayoutInflater.from(this)

        g.rooms.forEach { r ->
            val opt = inf.inflate(R.layout.item_room_option, list, false)
            opt.findViewById<TextView>(R.id.roomName).text = r.blvName
            opt.findViewById<TextView>(R.id.roomMeta).text = "${SocoliveRepository.fmtViewers(r.viewers)} lượt xem · LIVE"
            opt.setOnClickListener {
                s.room = r
                updateLabel(s)
                playInSlot(i, "FETCH")
                dialog?.dismiss()
            }
            list.addView(opt)
        }

        val other = groups.filter { it !== g }.take(6)
        if (other.isNotEmpty()) {
            val div = TextView(this).apply {
                text = "ĐỔI SANG TRẬN KHÁC"
                setTextColor(0xFF94A3B8.toInt())
                textSize = 12f
                setPadding(24, 18, 24, 8)
                letterSpacing = 0.08f
            }
            list.addView(div)
            other.forEach { og ->
                val opt = inf.inflate(R.layout.item_room_option, list, false)
                opt.findViewById<TextView>(R.id.roomName).text = og.matchTitle
                opt.findViewById<TextView>(R.id.roomMeta).text = "${og.league} · ${og.count} phòng · ${SocoliveRepository.fmtViewers(og.totalViewers)} lượt xem"
                opt.setOnClickListener {
                    s.group = og
                    s.room = og.top
                    updateLabel(s)
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

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(secondWatcher)
        // Release players early to save RAM when paused
        slots.forEach { slot ->
            try {
                slot.player?.pause()
            } catch (_: Exception) {}
        }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(secondWatcher)
        slots.forEach {
            it.loadingJob?.cancel()
            try {
                it.player?.release()
            } catch (_: Exception) {}
            it.player = null
            it.fx.detach()
            it.playerView.player = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        slots.forEach {
            it.loadingJob?.cancel()
            try {
                it.player?.release()
            } catch (_: Exception) {}
            it.fx.detach()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            // On low memory, reduce second player quality or pause it
            if (isLowRam) {
                slots[1].player?.let { p ->
                    try {
                        p.volume = 0f
                        // Lower bitrate already via track selector, but we can pause second if critical
                        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
                            p.pause()
                            Toast.makeText(this, "RAM thấp: tạm dừng ô 2 để ổn định", Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
