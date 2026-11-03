package com.kenhlive.tv.phim

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kenhlive.tv.R
import com.kenhlive.tv.ui.applyTvDensity
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/**
 * WebPlayerActivity — Trình phát phim web chuyên nghiệp cho Phim Nguồn C (StreamC embed):
 * - Hỗ trợ WebView tăng tốc phần cứng, giải mã JWPlayer & HTML5 video.
 * - Tự động thiết lập Referer https://phim.nguonc.com/ và Chrome User-Agent chuẩn.
 * - TỰ ĐỘNG LƯU TIẾN ĐỘ XEM: Theo dõi vị trí mili-giây và lưu vào WatchHistoryManager.
 * - TỰ ĐỘNG TIẾP TỤC XEM: Tua ngay đến giây đã xem dở lần trước khi mở lại.
 * - Điều khiển thân thiện chuẩn Android TV & Mobile (D-pad Center = Play/Pause, OSD tự ẩn sau 3.5s).
 * - Toàn màn hình Immersive Mode, chống tắt màn hình khi xem phim.
 */
class WebPlayerActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private lateinit var loadingBar: ProgressBar
    private lateinit var topOsd: View
    private lateinit var tvFilmTitle: TextView
    private lateinit var tvEpisodeTitle: TextView
    private lateinit var customViewContainer: FrameLayout

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideOsdRunnable = Runnable {
        topOsd.animate().alpha(0f).translationY(-topOsd.height.toFloat()).setDuration(250).withEndAction {
            topOsd.visibility = View.GONE
        }.start()
        webControlBar?.visibility = View.GONE
    }

    private var embedUrl: String = ""
    private var filmSlug: String = ""
    private var filmTitle: String = ""
    private var episodeSlug: String = ""
    private var episodeTitle: String = ""
    private var posterUrl: String = ""
    private var startPositionMs: Long = 0L

    private var currentPositionMs: Long = 0L
    private var currentDurationMs: Long = 0L
    private var hasSeekedToStart: Boolean = false

    // Thanh điều khiển riêng của app cho trình phát web (trước đây chỉ có nút Back)
    private var webControlBar: View? = null
    private var btnWebPlayPause: ImageButton? = null
    // JS có tìm thấy <video>/JWPlayer điều khiển được không (kể cả trong iframe cùng nguồn).
    // Nếu không, phím D-pad được trả lại cho WebView để trình phát của nhà cung cấp tự xử lý.
    private var hasJsControl: Boolean = false
    private var lastPaused: Boolean = false

    // Vòng lặp định kỳ thăm dò tiến độ xem phim mỗi 3 giây
    private val progressTrackerRunnable = object : Runnable {
        override fun run() {
            queryPlaybackPosition()
            handler.postDelayed(this, 3000L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTvDensity()
        try {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } catch (_: Throwable) {}
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_web_player)
        hideSystemUi()

        embedUrl = intent.getStringExtra("embed_url").orEmpty()
        val m3u8Url = intent.getStringExtra("m3u8_url").orEmpty()
        filmSlug = intent.getStringExtra("film_slug").orEmpty()
        filmTitle = intent.getStringExtra("film_title") ?: "Phim Nguồn C"
        episodeSlug = intent.getStringExtra("episode_slug").orEmpty()
        episodeTitle = intent.getStringExtra("episode_title") ?: "Tập 1"
        posterUrl = intent.getStringExtra("poster_url").orEmpty()
        startPositionMs = intent.getLongExtra("start_position_ms", 0L)

        loadingBar = findViewById(R.id.playerLoading)
        topOsd = findViewById(R.id.playerTopOsd)
        tvFilmTitle = findViewById(R.id.tvFilmTitle)
        tvEpisodeTitle = findViewById(R.id.tvEpisodeTitle)
        customViewContainer = findViewById(R.id.customViewContainer)

        tvFilmTitle.text = filmTitle
        tvEpisodeTitle.text = episodeTitle

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            saveCurrentProgress()
            finish()
        }

        webControlBar = findViewById(R.id.webControlBar)
        btnWebPlayPause = findViewById(R.id.btnWebPlayPause)
        btnWebPlayPause?.setOnClickListener { webTogglePlayPause() }
        findViewById<View>(R.id.btnWebSeekBack).setOnClickListener { webSeekBy(-10) }
        findViewById<View>(R.id.btnWebSeekFwd).setOnClickListener { webSeekBy(10) }

        val host = findViewById<FrameLayout>(R.id.webViewHost)
        val wv = try {
            WebView(this).also {
                it.isFocusable = true
                it.isFocusableInTouchMode = true
                host.addView(it, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
            }
        } catch (t: Throwable) {
            android.util.Log.e("WebPlayerActivity", "WebView is not available on this device", t)
            null
        }
        webView = wv

        if (wv != null) {
            try {
                setupWebView(wv)
                showOsd()

                if (embedUrl.isNotBlank()) {
                    val extraHeaders = mapOf(
                        "Referer" to NguonC_REFERER,
                        "Origin" to "https://phim.nguonc.com"
                    )
                    wv.loadUrl(embedUrl, extraHeaders)
                    handler.postDelayed(progressTrackerRunnable, 4000L)
                } else if (m3u8Url.isNotBlank()) {
                    launchNativePlayerAndFinish(m3u8Url)
                } else {
                    finish()
                }
            } catch (t: Throwable) {
                android.util.Log.e("WebPlayerActivity", "WebView setup/load failed", t)
                if (m3u8Url.isNotBlank()) {
                    launchNativePlayerAndFinish(m3u8Url)
                } else {
                    showFallbackError(host)
                }
            }
        } else {
            if (m3u8Url.isNotBlank()) {
                launchNativePlayerAndFinish(m3u8Url)
            } else {
                showFallbackError(host)
            }
        }
    }

    private fun launchNativePlayerAndFinish(m3u8: String) {
        val pIntent = Intent(this, com.kenhlive.tv.PlayerActivity::class.java).apply {
            putExtra("url", m3u8)
            putExtra("name", "$filmTitle · $episodeTitle")
            putExtra("film_slug", filmSlug)
            putExtra("film_title", filmTitle)
            putExtra("episode_slug", episodeSlug)
            putExtra("episode_title", episodeTitle)
            putExtra("poster_url", posterUrl)
            putExtra("start_position_ms", startPositionMs)
        }
        startActivity(pIntent)
        finish()
    }

    private fun showFallbackError(host: FrameLayout) {
        loadingBar.visibility = View.GONE
        val errorTv = TextView(this).apply {
            text = "Trình phát Web không được hỗ trợ trên thiết bị này.\nBấm phím BACK để quay lại."
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            isFocusable = true
            isClickable = true
        }
        host.removeAllViews()
        host.addView(errorTv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        errorTv.requestFocus()
        showOsd()
    }

    private fun hideSystemUi() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                or View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        )
            }
        } catch (t: Throwable) {
            android.util.Log.w("WebPlayerActivity", "hideSystemUi ignored: ${t.message}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        val defaultUa = try { WebSettings.getDefaultUserAgent(this) } catch (_: Throwable) { "" }
        s.userAgentString = if (defaultUa.isNotBlank()) {
            defaultUa.replace("; wv", "")
        } else {
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                loadingBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loadingBar.visibility = View.GONE
                // Trao focus cho trang web (trình phát của nhà cung cấp nhận được phím khi JS
                // không điều khiển được) + thăm dò sớm khả năng điều khiển bằng JS
                try { view?.requestFocus() } catch (_: Throwable) {}
                handler.postDelayed({ queryPlaybackPosition() }, 2000L)

                // 1. Kích hoạt Autoplay
                val autoPlayJs = """
                    (function() {
                        var v = document.querySelector('video');
                        if (v) {
                            v.play().catch(function(){});
                        }
                        if (window.jwplayer && typeof window.jwplayer === 'function') {
                            try { window.jwplayer().play(); } catch(e){}
                        }
                    })();
                """.trimIndent()
                try { view?.evaluateJavascript(autoPlayJs, null) } catch (_: Throwable) {}

                // 2. Tự động tua đến giây đã lưu nếu có tiến độ trước đó
                if (startPositionMs > 1000L && !hasSeekedToStart) {
                    val targetSec = startPositionMs / 1000.0
                    val totalSec = targetSec.toInt()
                    val mm = totalSec / 60
                    val ss = totalSec % 60
                    Toast.makeText(this@WebPlayerActivity, String.format(Locale.US, "Tiếp tục xem từ %02d:%02d", mm, ss), Toast.LENGTH_SHORT).show()
                    val seekJs = """
                        (function() {
                            var v = document.querySelector('video');
                            if (v && v.duration > 0) {
                                v.currentTime = $targetSec;
                                return 'SEEKED_VIDEO';
                            }
                            if (window.jwplayer && typeof window.jwplayer === 'function') {
                                try {
                                    window.jwplayer().seek($targetSec);
                                    return 'SEEKED_JW';
                                } catch(e){}
                            }
                            return 'WAITING';
                        })();
                    """.trimIndent()
                    handler.postDelayed({
                        try {
                            view?.evaluateJavascript(seekJs) { res ->
                                if (res != null && res.contains("SEEKED")) {
                                    hasSeekedToStart = true
                                }
                            }
                        } catch (_: Throwable) {}
                    }, 2500L)
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    loadingBar.visibility = View.GONE
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val nextUrl = request?.url?.toString() ?: return false
                if (nextUrl.startsWith("http://") || nextUrl.startsWith("https://")) {
                    return false
                }
                return true
            }

            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                android.util.Log.e("WebPlayerActivity", "WebView render process exited: didCrash=${detail?.didCrash()}")
                try {
                    (view?.parent as? ViewGroup)?.removeView(view)
                    view?.destroy()
                } catch (_: Throwable) {}
                webView = null
                val host = findViewById<FrameLayout>(R.id.webViewHost)
                showFallbackError(host)
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                (view.parent as? ViewGroup)?.removeView(view)
                try {
                    customViewContainer.addView(view, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                    customViewContainer.visibility = View.VISIBLE
                    wv.visibility = View.GONE
                    topOsd.visibility = View.GONE
                } catch (t: Throwable) {
                    android.util.Log.e("WebPlayerActivity", "onShowCustomView failed", t)
                }
            }

            override fun onHideCustomView() {
                if (customView == null) return
                try {
                    customViewContainer.removeView(customView)
                } catch (_: Throwable) {}
                customView = null
                customViewContainer.visibility = View.GONE
                wv.visibility = View.VISIBLE
                customViewCallback?.onCustomViewHidden()
                showOsd()
            }
        }
    }

    /**
     * Truy vấn vị trí phát hiện tại từ player trong web (tìm sâu cả trong iframe cùng nguồn).
     * Đồng thời cập nhật hasJsControl: có video điều khiển được bằng JS hay không.
     */
    private fun queryPlaybackPosition() {
        val queryJs = """
            (function() {
                function klVideoIn(win) {
                    try {
                        var v = win.document.querySelector('video');
                        if (v) return v;
                        var fs = win.frames;
                        for (var i = 0; i < fs.length; i++) {
                            var r = klVideoIn(fs[i]);
                            if (r) return r;
                        }
                    } catch (e) {}
                    return null;
                }
                function klJwIn(win) {
                    try {
                        if (typeof win.jwplayer === 'function') {
                            var p = win.jwplayer();
                            if (p && typeof p.getState === 'function') return p;
                        }
                        var fs = win.frames;
                        for (var i = 0; i < fs.length; i++) {
                            var r = klJwIn(fs[i]);
                            if (r) return r;
                        }
                    } catch (e) {}
                    return null;
                }
                var v = klVideoIn(window);
                if (v && v.duration > 0) {
                    return JSON.stringify({
                        pos: Math.floor(v.currentTime * 1000),
                        dur: Math.floor(v.duration * 1000),
                        paused: v.paused,
                        found: 1
                    });
                }
                var jw = klJwIn(window);
                if (jw) {
                    try {
                        var pos = Math.floor(jw.getPosition() * 1000);
                        var dur = Math.floor(jw.getDuration() * 1000);
                        if (dur > 0) {
                            return JSON.stringify({
                                pos: pos,
                                dur: dur,
                                paused: jw.getState() === 'paused',
                                found: 1
                            });
                        }
                    } catch(e){}
                }
                return JSON.stringify({ found: 0 });
            })();
        """.trimIndent()

        webView?.evaluateJavascript(queryJs) { rawJson ->
            if (rawJson != null && rawJson != "null" && rawJson.length > 4) {
                try {
                    // Loại bỏ escape ký tự nếu có
                    val cleanJson = if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                        rawJson.substring(1, rawJson.length - 1).replace("\\\"", "\"")
                    } else rawJson

                    val obj = JSONObject(cleanJson)
                    hasJsControl = obj.optInt("found", 0) == 1
                    if (obj.has("paused")) {
                        lastPaused = obj.optBoolean("paused", false)
                        if (webControlBar?.visibility == View.VISIBLE) {
                            btnWebPlayPause?.setImageResource(
                                if (lastPaused) R.drawable.ic_play else R.drawable.ic_pause
                            )
                        }
                    }
                    val pos = obj.optLong("pos", 0L)
                    val dur = obj.optLong("dur", 0L)

                    if (pos > 0) currentPositionMs = pos
                    if (dur > 0) currentDurationMs = dur

                    // Tự động lưu tiến độ vào cơ sở dữ liệu
                    if (filmSlug.isNotBlank() && currentPositionMs > 5000L) {
                        saveCurrentProgress()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // ===== Điều khiển phát bằng JS: tìm <video>/JWPlayer sâu trong các iframe cùng nguồn =====
    // Trước đây chỉ tìm ở khung hình trên cùng: hầu hết trang embed bọc video trong iframe con
    // nên querySelector('video') trả về null -> mọi phím Dừng/Tua đều "chết" dù code có sẵn.
    private val jsFindLib = """
        function klVideoIn(win) {
            try {
                var v = win.document.querySelector('video');
                if (v) return v;
                var fs = win.frames;
                for (var i = 0; i < fs.length; i++) {
                    var r = klVideoIn(fs[i]);
                    if (r) return r;
                }
            } catch (e) {}
            return null;
        }
        function klJwIn(win) {
            try {
                if (typeof win.jwplayer === 'function') {
                    var p = win.jwplayer();
                    if (p && typeof p.getState === 'function') return p;
                }
                var fs = win.frames;
                for (var i = 0; i < fs.length; i++) {
                    var r = klJwIn(fs[i]);
                    if (r) return r;
                }
            } catch (e) {}
            return null;
        }
    """.trimIndent()

    /** Bấm giả vào giữa màn hình WebView — phương án dự phòng khi video nằm trong iframe khác nguồn:
     *  hầu hết trình phát web đều dừng/phát khi chạm vào giữa khung hình. */
    private fun simulateCenterTap() {
        val wv = webView ?: return
        try {
            val x = wv.width / 2f
            val y = wv.height / 2f
            if (x <= 0f || y <= 0f) return
            val now = android.os.SystemClock.uptimeMillis()
            val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
            val up = android.view.MotionEvent.obtain(now, now + 80, android.view.MotionEvent.ACTION_UP, x, y, 0)
            wv.dispatchTouchEvent(down)
            wv.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        } catch (_: Throwable) {}
    }

    private fun webTogglePlayPause() {
        val js = """
            (function() {
                $jsFindLib
                var jw = klJwIn(window);
                if (jw) {
                    try {
                        if (jw.getState() === 'playing') { jw.pause(); return 'PAUSED'; }
                        jw.play(); return 'PLAYING';
                    } catch (e) {}
                }
                var v = klVideoIn(window);
                if (v) {
                    if (v.paused) { v.play(); return 'PLAYING'; }
                    v.pause(); return 'PAUSED';
                }
                return 'NONE';
            })();
        """.trimIndent()
        try {
            webView?.evaluateJavascript(js) { res ->
                when {
                    res != null && res.contains("PAUSED") -> {
                        lastPaused = true
                        btnWebPlayPause?.setImageResource(R.drawable.ic_play)
                    }
                    res != null && res.contains("PLAYING") -> {
                        lastPaused = false
                        btnWebPlayPause?.setImageResource(R.drawable.ic_pause)
                    }
                    else -> simulateCenterTap()
                }
            }
        } catch (_: Throwable) {
            simulateCenterTap()
        }
        showOsd()
    }

    private fun webSeekBy(deltaSec: Int) {
        val js = """
            (function() {
                $jsFindLib
                var jw = klJwIn(window);
                if (jw) {
                    try {
                        var pos = jw.getPosition();
                        var dur = jw.getDuration();
                        var t = pos + $deltaSec;
                        if (t < 0) t = 0;
                        if (dur > 0 && t > dur) t = dur;
                        jw.seek(t);
                        return 'OK';
                    } catch (e) {}
                }
                var v = klVideoIn(window);
                if (v) {
                    var t2 = v.currentTime + $deltaSec;
                    if (t2 < 0) t2 = 0;
                    if (v.duration > 0 && t2 > v.duration) t2 = v.duration;
                    v.currentTime = t2;
                    return 'OK';
                }
                return 'NONE';
            })();
        """.trimIndent()
        try { webView?.evaluateJavascript(js, null) } catch (_: Throwable) {}
        Toast.makeText(
            this,
            if (deltaSec < 0) "◀◀ Tua lại ${-deltaSec}s" else "▶▶ Tua tới ${deltaSec}s",
            Toast.LENGTH_SHORT
        ).show()
        showOsd()
        queryPlaybackPosition()
    }

    private fun saveCurrentProgress() {
        if (filmSlug.isBlank() || currentPositionMs <= 0L) return
        WatchHistoryManager.saveProgressAsync(
            slug = filmSlug,
            filmName = filmTitle,
            posterUrl = posterUrl,
            episodeName = episodeTitle,
            episodeSlug = episodeSlug,
            embedUrl = embedUrl,
            positionMs = currentPositionMs,
            durationMs = currentDurationMs
        )
    }

    private fun showOsd() {
        handler.removeCallbacks(hideOsdRunnable)
        topOsd.visibility = View.VISIBLE
        topOsd.animate().alpha(1f).translationY(0f).setDuration(200).start()
        webControlBar?.visibility = View.VISIBLE
        btnWebPlayPause?.setImageResource(if (lastPaused) R.drawable.ic_play else R.drawable.ic_pause)
        handler.postDelayed(hideOsdRunnable, 3500L)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        try {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_INFO -> {
                        showOsd()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        // Chỉ chặn phím khi JS điều khiển được video; nếu không, để WebView/trình phát
                        // của nhà cung cấp tự nhận phím (trước đây chặn vô điều kiện mà JS lại không tìm
                        // thấy video trong iframe -> bấm tua không có tác dụng gì)
                        if (hasJsControl) {
                            webSeekBy(-10)
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        if (hasJsControl) {
                            webSeekBy(10)
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        val playJs = "var v = document.querySelector('video'); if (v) v.play(); if (window.jwplayer) try { window.jwplayer().play(); } catch(e){}"
                        try { webView?.evaluateJavascript(playJs, null) } catch (_: Throwable) {}
                        showOsd()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        val pauseJs = "var v = document.querySelector('video'); if (v) v.pause(); if (window.jwplayer) try { window.jwplayer().pause(); } catch(e){}"
                        try { webView?.evaluateJavascript(pauseJs, null) } catch (_: Throwable) {}
                        showOsd()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        webTogglePlayPause()
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        if (customView != null) {
                            try { webView?.webChromeClient?.onHideCustomView() } catch (_: Throwable) {}
                            return true
                        }
                        if (topOsd.visibility == View.VISIBLE) {
                            topOsd.visibility = View.GONE
                            saveCurrentProgress()
                            finish()
                            return true
                        }
                        saveCurrentProgress()
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("WebPlayerActivity", "dispatchKeyEvent error", t)
        }
        return try {
            super.dispatchKeyEvent(event)
        } catch (t: Throwable) {
            true
        }
    }

    override fun onResume() {
        super.onResume()
        try { webView?.onResume() } catch (_: Throwable) {}
        hideSystemUi()
    }

    override fun onPause() {
        super.onPause()
        saveCurrentProgress()
        try { webView?.onPause() } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideOsdRunnable)
        handler.removeCallbacks(progressTrackerRunnable)
        saveCurrentProgress()
        try {
            (webView?.parent as? ViewGroup)?.removeView(webView)
            webView?.stopLoading()
            webView?.destroy()
        } catch (_: Throwable) {}
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val NguonC_REFERER = "https://phim.nguonc.com/"
    }
}
