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
import kotlinx.coroutines.launch

/**
 * Multiview v3 — ĐÚNG như tên: xem NHIỀU trận cùng lúc.
 * - Bố cục 2 (trái/phải) hoặc 4 (2×2), nút ℹ/chạm "Bố cục" để đổi (máy RAM thấp khoá ở 2)
 * - CẢ các ô đều có tiếng: ô focus 100%, ô khác 55% (layout 2) / 40% (layout 4); mute riêng từng ô
 * - ỔN ĐỊNH: mỗi slot tự phục hồi — lỗi/đứng hình → thử lại cùng phòng (backoff, tối đa 5 lần)
 *   → hết nguồn mới báo OK chọn trận; watchdog 15s phát hiện buffer treo
 * - ←→↑↓: di chuyển 2 chiều giữa các ô · OK: đổi trận/BLV · MENU: hoán đổi với ô kế · BACK: thoát
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

        val slotRoots = listOf(findViewById<FrameLayout>(R.id.slot0), findViewById(R.id.slot1),
                               findViewById(R.id.slot2), findViewById(R.id.slot3))
        slots = Array(4) { i -> Slot(slotRoots[i], i) }

        // debug hook QA: --ei mv_layout 4 ép bố cục 4 (vượt khoá lowRam để chụp ảnh CI)
        val forceLayout = intent.getIntExtra("mv_layout", 0)
        layoutN = when {
            forceLayout in intArrayOf(2, 4) -> forceLayout
            KenhLiveApp.lowRam -> 2
            else -> getSharedPreferences("mv", MODE_PRIVATE).getInt("layout", 2)
        }
        findViewById<TextView>(R.id.layoutBtn)?.setOnClickListener { toggleLayout() }

        slots.forEachIndexed { i, s ->
            s.root.setOnClickListener { requestFocusSlot(i) }
            s.audioBadge.setOnClickListener {
                s.muted = !s.muted
                s.audioBadge.text = if (s.muted) "TĨNH LẶNG" else "ÂM THANH"
                applyVolumes()
            }
        }
        applyLayoutChrome()

        val initialRoomName = intent.getStringExtra("initial_room")
        val initialUrl = intent.getStringExtra("initial_url")

        lifecycleScope.launch {
            groups = try { SocoliveRepository.groupRooms(SocoliveRepository.fetchLiveRooms()) }
                     catch (e: Exception) { emptyList() }
            if (groups.isEmpty()) {
                Toast.makeText(this@MultiViewActivity, "Không load được trận live — thoát ra vào lại", Toast.LENGTH_LONG).show()
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
                if (t != null) bindSlot(2, t, null); if (f != null) bindSlot(3, f, null)
            }
            applyFocus()
            handler.postDelayed(watchdog, 15_000)
        }
    }

    /** group chưa bị ô nào khác chiếm */
    private fun pickUnused(avoidIndex: Int, vararg takenIdx: Int): LiveMatchGroup? {
        val taken = takenIdx.toList().mapNotNull { slots[it].group?.matchTitle }.toHashSet()
        return groups.firstOrNull { it.matchTitle !in taken }
    }

    // ===== BỐ CỤC 2 ⇄ 4 =====
    private fun toggleLayout() {
        if (KenhLiveApp.lowRam) {
            Toast.makeText(this, "Máy RAM thấp — giữ bố cục 2 trận để mượt", Toast.LENGTH_SHORT).show(); return
        }
        layoutN = if (layoutN == 2) 4 else 2
        getSharedPreferences("mv", MODE_PRIVATE).edit().putInt("layout", layoutN).apply()
        applyLayoutChrome()
        if (layoutN == 4) {
            if (groups.isEmpty()) return
            val t = pickUnused(2, 0, 1); val f = pickUnused(3, 0, 1, 2)
            if (t == null && slots[2].group == null) Toast.makeText(this, "Chưa đủ trận live cho 4 ô", Toast.LENGTH_SHORT).show()
            if (t != null && slots[2].group?.matchTitle != t.matchTitle) bindSlot(2, t, null)
            if (f != null && slots[3].group?.matchTitle != f.matchTitle) bindSlot(3, f, null)
            if (focused >= layoutN) { focused = 0 }
            applyFocus()
        } else {
            if (focused >= 2) { focused = 0; applyFocus() }
        }
    }

    private fun applyLayoutChrome() {
        // 2 ô: XẾP CHỒNG TRÊN/DƯỚI (cell 1920×540 = đúng 16:9, không đen không nhỏ).
        // 4 ô: 2 hàng × 2 cell (960×540 = cũng đúng 16:9). KHÔNG BAO GIỜ chia trái/phải ở mode 2.
        val row0 = findViewById<LinearLayout>(R.id.mvRow0)
        if (layoutN == 4) {
            row0.orientation = LinearLayout.HORIZONTAL
            for (i in 0..1) slots[i].root.layoutParams = LinearLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT, 1f)
        } else {
            row0.orientation = LinearLayout.VERTICAL
            for (i in 0..1) slots[i].root.layoutParams = LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        findViewById<View>(R.id.mvRow1).visibility = if (layoutN == 4) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.layoutBtn).text = "Bố cục: $layoutN"
        findViewById<TextView>(R.id.hintText).text =
            if (layoutN == 4) "↑↓←→ chọn ô · OK: đổi trận · MENU: hoán đổi · giữ OK: 2 ô"
            else "↑↓ chọn trận · OK: đổi trận · MENU: hoán đổi · giữ OK: 4 ô"
        if (layoutN == 2) for (i in 2..3) {
            val s = slots[i]
            if (s.group != null) { s.player?.release(); s.fx.detach(); s.player = null; s.playerView.player = null; s.group = null }
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
            s.label.text = (s.group?.matchTitle ?: "Ô ${i+1}") + " · đang tải stream…"
            val url = if (knownUrl != null && knownUrl != "FETCH") knownUrl else resolveStream(s)
            if (url == null) {
                s.label.text = "${s.group?.matchTitle ?: ""} · hết nguồn · OK để chọn trận"
                return@launch
            }
            s.player?.release(); s.fx.detach()
            s.player = ExoPlayer.Builder(this@MultiViewActivity)
                .setTrackSelector(Enhancer.buildTrackSelector(this@MultiViewActivity))
                .setLoadControl(Enhancer.buildLoadControl(this@MultiViewActivity))
                .build().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
                    volume = 0f
                    setMediaItem(Enhancer.buildMediaItem(url))
                    prepare()
                    playWhenReady = true
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) { slotRecover(i, "lỗi sóng") }
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_BUFFERING) { if (s.bufferingSince == 0L) s.bufferingSince = System.currentTimeMillis() }
                            else s.bufferingSince = 0L
                            if (state == Player.STATE_READY) s.retry = 0
                        }
                    })
                }
            s.playerView.player = s.player
            // FIT trọn khung hình: ZOOM ở ô 1/2 màn crop mất phân nửa trận (user report v5.2.0)
            s.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            s.fx.attach(s.player!!.audioSessionId, EnhanceSettings.audioMode(this@MultiViewActivity))
            applyVolumes()
        }
    }

    /** Lỗi/đứng → reload lại CÙNG phòng trước (giữ trận người dùng chọn), backoff 2·4·6·8·10s. */
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

    /** Watchdog: buffer treo >30s hoặc IDLE không lý do → recover. */
    private val watchdog = object : Runnable {
        override fun run() {
            val self = this
            for (s in active) {
                val p = s.player ?: continue
                if (p.playbackState == Player.STATE_BUFFERING &&
                    s.bufferingSince > 0 && System.currentTimeMillis() - s.bufferingSince > 30_000) {
                    s.bufferingSince = 0; slotRecover(s.index, "kẹt hình")
                } else if (p.playbackState == Player.STATE_IDLE) {
                    slotRecover(s.index, "rơi sóng")
                }
            }
            handler.postDelayed(self, 15_000)
        }
    }

    /** fetch chain: phòng đang chọn → phòng khác cùng trận → trận khác (tối đa 8). */
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
            KeyEvent.KEYCODE_DPAD_LEFT  -> { moveFocus(-1, 0); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { moveFocus(1, 0); return true }
            KeyEvent.KEYCODE_DPAD_UP    -> { moveFocus(0, -1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN  -> { moveFocus(0, 1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // giữ OK (lặp >=3) = đổi bố cục 2⇄4 — vì phím ℹ không có trên mọi remote
                // và dispatchKeyEvent nuốt hết arrow nên không D-pad tới được nút "Bố cục".
                if (event.repeatCount >= 3 && (dialog?.isShowing != true)) { toggleLayout(); return true }
                if (event.repeatCount < 3 && slots[focused].group != null) openRoomPicker(focused)
                return true
            }
            KeyEvent.KEYCODE_MENU -> { swapWithNext(); return true }
            KeyEvent.KEYCODE_INFO -> { toggleLayout(); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    /** lưới: 2 ô = 1 cột × 2 hàng (dọc); 4 ô = 2×2. Chặn mọi mép — không wrap, không nhảy. */
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
        active.forEachIndexed { i, s ->
            val isFocus = s.index == focused
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
            border.x = target.x.toFloat(); border.y = target.y.toFloat()
            val lp = border.layoutParams
            lp.width = target.width; lp.height = target.height
            border.layoutParams = lp
            border.foreground = focusDrawable()
        }
    }

    /** Viền mảnh 4px + nền đen mờ 10px đằng sau: mỏng hơn mà vẫn nổi trên áo trắng/cỏ sáng. */
    private fun focusDrawable(): android.graphics.drawable.Drawable {
        val back = android.graphics.drawable.GradientDrawable().apply {
            setColor(0x00000000); setStroke(10, 0x99000000.toInt())
        }
        val line = android.graphics.drawable.GradientDrawable().apply {
            setColor(0x00000000); setStroke(4, 0xFFFF3B30.toInt())
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
        a.player = b.player; a.group = b.group; a.room = b.room; a.muted = b.muted; a.label.text = b.label.text
        b.player = tp; b.group = tg; b.room = tr; b.muted = tm; b.label.text = tl
        a.playerView.player = a.player; b.playerView.player = b.player
        a.fx.detach(); b.fx.detach()
        a.player?.let { a.fx.attach(it.audioSessionId, EnhanceSettings.audioMode(this@MultiViewActivity)) }
        b.player?.let { b.fx.attach(it.audioSessionId, EnhanceSettings.audioMode(this@MultiViewActivity)) }
        a.audioBadge.text = if (a.muted) "TĨNH LẶNG" else "ÂM THANH"
        b.audioBadge.text = if (b.muted) "TĨNH LẶNG" else "ÂM THANH"
        applyVolumes()
        Toast.makeText(this, "Đã hoán đổi 2 ô", Toast.LENGTH_SHORT).show()
    }

    private fun fmtLabel(s: Slot): String =
        s.group?.let { "${it.matchTitle} · ${s.room?.blvName ?: it.top.blvName}" } ?: ""

    // ===== CHỜ TRẬN 2 + PICKER =====
    private fun showWaitingSecond() {
        val s = slots[1]
        s.player?.release(); s.player = null; s.fx.detach()
        s.playerView.player = null
        s.label.text = "Đang chờ trận live thứ 2…"
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
                    val need = if (layoutN == 4) listOf(0,1,2,3) else listOf(0,1)
                    var changed = false
                    for (i in need) {
                        if (slots[i].group == null) {
                            val g = pickUnused(i, *(need.filter { it != i }.toIntArray()))
                            if (g != null) { bindSlot(i, g, null); changed = true }
                        }
                    }
                    if (changed) { Toast.makeText(this@MultiViewActivity, "Đã thêm trận", Toast.LENGTH_SHORT).show(); applyFocus() }
                    else handler.postDelayed(self, 20_000)
                } catch (e: Exception) { handler.postDelayed(self, 20_000) }
            }
        }
    }

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
                s.room = r; s.label.text = fmtLabel(s); s.retry = 0
                playInSlot(i, "FETCH")
                dialog?.dismiss()
            }
            list.addView(opt)
        }
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
                    s.group = og; s.room = og.top; s.retry = 0; s.label.text = fmtLabel(s)
                    playInSlot(i, "FETCH")
                    dialog?.dismiss()
                }
                list.addView(opt)
            }
        }
        dialog = AlertDialog.Builder(this).setView(view).create()
        dialog?.show()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(secondWatcher)
        handler.removeCallbacks(watchdog)
        slots.forEach { it.player?.release(); it.player = null; it.fx.detach() }
    }
}
