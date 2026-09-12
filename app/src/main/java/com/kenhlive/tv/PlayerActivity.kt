package com.kenhlive.tv

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Màn phát stream. Overlay điều khiển focusable (TV dùng D-pad để tới từng nút),
 * tự ẩn 3.5s; dialog hình/âm dạng chip chọn trực tiếp (thay dialog chữ bản cũ).
 */
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var topOverlay: View? = null
    private var hint: TextView? = null
    private val hideOverlay = Runnable {
        topOverlay?.visibility = View.GONE
        hint?.visibility = View.GONE
    }
    private val audioFx = AudioEnhancer(this)
    private var url: String = ""
    private var streamRetries = 0
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        url = intent.getStringExtra("url") ?: ""
        val name = intent.getStringExtra("name") ?: getString(R.string.player_default_name)

        topOverlay = findViewById(R.id.topOverlay)
        hint = findViewById(R.id.playerHint)
        findViewById<TextView>(R.id.playerTitle).text = name
        findViewById<TextView>(R.id.playerSub).visibility = View.VISIBLE

        initPlayer()
        val pv = findViewById<PlayerView>(R.id.playerView)

        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.qualityBtn).setOnClickListener { showSettingsDialog(video = true) }
        findViewById<ImageButton>(R.id.audioBtn).setOnClickListener { showSettingsDialog(video = false) }
        findViewById<ImageButton>(R.id.pipBtn)?.let { b ->
            if (pipSupported() && !DeviceMode.isTv) {
                b.visibility = View.VISIBLE
                b.setOnClickListener { enterPip(manual = true) }
            }
        }
        findViewById<ImageButton>(R.id.multiBtn).setOnClickListener {
            startActivity(
                Intent(this, MultiViewActivity::class.java)
                    .putExtra("initial_room", name)
                    .putExtra("initial_url", url)
            )
        }
        if (intent.getBooleanExtra("pip", false)) pv.post { enterPip(manual = false) }

        hint?.text = getString(if (DeviceMode.isTv) R.string.player_hint_tv else R.string.player_hint_phone)
        showOverlay()
    }

    private fun initPlayer() {
        if (url.isBlank()) return
        player = ExoPlayer.Builder(this)
            .setTrackSelector(Enhancer.buildTrackSelector(this))
            .setLoadControl(Enhancer.buildLoadControl(this))
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
                            Toast.makeText(
                                this@PlayerActivity,
                                getString(R.string.player_retry, streamRetries),
                                Toast.LENGTH_SHORT
                            ).show()
                            handler.postDelayed({ player?.prepare() }, 2000L * streamRetries)
                        } else {
                            val msg = if (isNet) getString(R.string.player_net_error)
                            else getString(R.string.player_stream_error, error.errorCodeName)
                            Toast.makeText(this@PlayerActivity, msg, Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) streamRetries = 0
                        findViewById<View>(R.id.bufferBox)?.visibility =
                            if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    }
                })
            }
        findViewById<PlayerView>(R.id.playerView).apply {
            player = this@PlayerActivity.player
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
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
    }

    override fun onStart() {
        super.onStart()
        if (player == null && url.isNotBlank()) initPlayer()
    }

    // ===== dialog hình & âm: chip chọn trực tiếp, focus được cho remote =====
    private fun showSettingsDialog(video: Boolean) {
        dialog?.dismiss()
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_player_settings, null)
        val videoBox = v.findViewById<LinearLayout>(R.id.videoOptions)
        val audioBox = v.findViewById<LinearLayout>(R.id.audioOptions)

        val vqNames = arrayOf(
            getString(R.string.vq_auto), getString(R.string.vq_high), getString(R.string.vq_stable)
        )
        val aqNames = arrayOf(
            getString(R.string.aq_standard), getString(R.string.aq_bass), getString(R.string.aq_dialog),
            getString(R.string.aq_night), getString(R.string.aq_auto)
        )
        buildChips(videoBox, vqNames, EnhanceSettings.videoQuality(this)) { i ->
            EnhanceSettings.setVideoQuality(this, i)
            (player?.trackSelector as? androidx.media3.exoplayer.trackselection.DefaultTrackSelector)
                ?.let { Enhancer.applyVideo(it, i) }
            Toast.makeText(this, getString(R.string.player_video_changed, vqNames[i]), Toast.LENGTH_SHORT).show()
            markSelection(videoBox, i)
        }
        buildChips(audioBox, aqNames, EnhanceSettings.audioMode(this)) { i ->
            EnhanceSettings.setAudioMode(this, i)
            val sid = player?.audioSessionId ?: 0
            if (sid != 0) audioFx.attach(sid, i)
            val suffix = if (sid == 0) getString(R.string.player_audio_pending) else ""
            Toast.makeText(this, getString(R.string.player_audio_changed, aqNames[i]) + suffix, Toast.LENGTH_SHORT).show()
            markSelection(audioBox, i)
        }

        dialog = AlertDialog.Builder(this)
            .setView(v)
            .setNegativeButton(R.string.dialog_close, null)
            .setOnDismissListener { dialog = null }
            .create()
        dialog?.show()
        // focus nhóm đang chỉnh
        (if (video) videoBox else audioBox).post {
            val idx = if (video) EnhanceSettings.videoQuality(this) else EnhanceSettings.audioMode(this)
            (if (video) videoBox else audioBox).getChildAt(idx)?.requestFocus()
        }
    }

    private fun buildChips(box: LinearLayout, names: Array<String>, selected: Int, onPick: (Int) -> Unit) {
        box.removeAllViews()
        val inf = LayoutInflater.from(this)
        names.forEachIndexed { i, n ->
            val chip = inf.inflate(R.layout.item_search_chip, box, false) as TextView
            chip.text = n
            chip.isSelected = i == selected
            chip.setOnClickListener { onPick(i) }
            box.addView(chip)
        }
    }

    private fun markSelection(box: LinearLayout, selected: Int) {
        for (i in 0 until box.childCount) box.getChildAt(i).isSelected = i == selected
    }

    // ================= PICTURE-IN-PICTURE =================
    private fun pipSupported(): Boolean =
        Build.VERSION.SDK_INT >= 26 &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun enterPip(manual: Boolean) {
        if (!pipSupported()) {
            if (manual) Toast.makeText(this, R.string.player_pip_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val p = player ?: return
        if (p.playbackState == Player.STATE_IDLE && manual) return
        try {
            val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
            if (Build.VERSION.SDK_INT >= 27) {
                val r = Rect()
                findViewById<PlayerView>(R.id.playerView).getGlobalVisibleRect(r)
                if (!r.isEmpty) b.setSourceRectHint(r)
            }
            enterPictureInPictureMode(b.build())
        } catch (e: Exception) {
            if (manual) Toast.makeText(this, getString(R.string.player_pip_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && !isInPictureInPictureMode && (player?.isPlaying == true)) {
            enterPip(manual = false)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            topOverlay?.visibility = View.GONE
            hint?.visibility = View.GONE
            findViewById<PlayerView>(R.id.playerView)?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
        } else {
            findViewById<PlayerView>(R.id.playerView)?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            showOverlay()
        }
    }

    private fun showOverlay() {
        topOverlay?.visibility = View.VISIBLE
        hint?.visibility = View.VISIBLE
        hideOnce()
    }

    private fun hideOnce() {
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 3500)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // BACK khi overlay đang hiện → chỉ ẩn overlay? Không: BACK luôn thoát player (chuẩn TV).
        showOverlay()
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) showOverlay()
        return super.dispatchTouchEvent(ev)
    }

    override fun onStop() {
        super.onStop()
        val inPip = Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode
        if (inPip && !isFinishing) return
        handler.removeCallbacks(hideOverlay)
        audioFx.detach()
        player?.release()
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        dialog?.dismiss()
        dialog = null
        if (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode) return
        player?.release()
        player = null
    }
}
