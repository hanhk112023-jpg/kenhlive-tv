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
import android.view.LayoutInflater
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kenhlive.tv.PlayerPrefs
import com.kenhlive.tv.R
import com.kenhlive.tv.ui.applyTvDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        // Bước tua theo cài đặt dùng chung với trình phát native (mặc định 10s, chỉnh trong Cài đặt)
        val stepSec = PlayerPrefs.seekStepSec(this)
        findViewById<Button>(R.id.btnWebSeekBack).apply {
            text = "◀◀  -${stepSec}s"
            setOnClickListener { webSeekBy(-PlayerPrefs.seekStepSec(this@WebPlayerActivity)) }
        }
        findViewById<Button>(R.id.btnWebSeekFwd).apply {
            text = "+${stepSec}s  ▶▶"
            setOnClickListener { webSeekBy(PlayerPrefs.seekStepSec(this@WebPlayerActivity)) }
        }
        findViewById<View>(R.id.btnWebPrevEp).setOnClickListener { switchWebEpisodeByDelta(-1) }
        findViewById<View>(R.id.btnWebNextEp).setOnClickListener { switchWebEpisodeByDelta(1) }
        findViewById<View>(R.id.btnWebEpisodes).setOnClickListener { showWebEpisodeListDialog() }

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

    /**
     * v7.14: Chuyển tập ngay trong trình phát web (người dùng góp ý player phải có
     * Tập trước / Tập tiếp / Danh sách tập như các trình phát khác).
     * Nhận diện server: ưu tiên server có tập khớp cả slug + embed đang phát;
     * không thì server duy nhất chứa slug.
     */
    private fun resolveWebServer(detail: NguoncFilmDetail?): Pair<NguoncServer, Int>? {
        val servers = detail?.episodes.orEmpty()
        var srv = servers.firstOrNull { s -> s.items.any { it.slug == episodeSlug && it.embed == embedUrl } }
        if (srv == null) {
            val bySlug = servers.filter { s -> s.items.any { it.slug == episodeSlug } }
            if (bySlug.size == 1) srv = bySlug[0]
        }
        val found = srv ?: return null
        val idx = found.items.indexOfFirst { it.slug == episodeSlug }
        if (idx < 0) return null
        return found to idx
    }

    private fun loadWebEpisode(item: NguoncEpisodeItem) {
        if (item.embed.isBlank()) {
            Toast.makeText(this, "Tập này chưa có nguồn phát", Toast.LENGTH_SHORT).show()
            return
        }
        saveCurrentProgress()
        episodeSlug = item.slug
        episodeTitle = if (item.name.all { it.isDigit() }) "Tập ${item.name}" else item.name
        embedUrl = item.embed
        tvEpisodeTitle.text = episodeTitle
        startPositionMs = 0L
        currentPositionMs = 0L
        currentDurationMs = 0L
        hasSeekedToStart = false
        hasJsControl = false
        try { loadingBar.visibility = View.VISIBLE } catch (_: Throwable) {}
        val extraHeaders = mapOf(
            "Referer" to NguonC_REFERER,
            "Origin" to "https://phim.nguonc.com"
        )
        webView?.loadUrl(embedUrl, extraHeaders)
        Toast.makeText(this, "Đang chuyển sang $episodeTitle", Toast.LENGTH_SHORT).show()
        showOsd()
    }

    private fun switchWebEpisodeByDelta(delta: Int) {
        if (filmSlug.isBlank() || episodeSlug.isBlank()) {
            Toast.makeText(this, "Nội dung này không có danh sách tập", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val detail = withContext(Dispatchers.IO) {
                NguoncRepository.fetchFilmDetail(filmSlug, this@WebPlayerActivity)
            }
            val resolved = resolveWebServer(detail)
            if (resolved == null) {
                Toast.makeText(this@WebPlayerActivity, "Không xác định được server đang xem", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val (srv, idx) = resolved
            val target = srv.items.getOrNull(idx + delta)
            if (target == null) {
                Toast.makeText(
                    this@WebPlayerActivity,
                    if (delta < 0) "Đã là tập đầu tiên" else "Đã hết tập của phim này",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            loadWebEpisode(target)
        }
    }

    private fun showWebEpisodeListDialog() {
        if (filmSlug.isBlank() || episodeSlug.isBlank()) {
            Toast.makeText(this, "Không có danh sách tập cho nội dung này", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val detail = withContext(Dispatchers.IO) {
                NguoncRepository.fetchFilmDetail(filmSlug, this@WebPlayerActivity)
            }
            val srv = resolveWebServer(detail)?.first
                ?: detail?.episodes?.firstOrNull { s -> s.items.any { it.slug == episodeSlug } }
            if (srv == null || srv.items.isEmpty()) {
                Toast.makeText(this@WebPlayerActivity, "Không tải được danh sách tập", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val curIdx = srv.items.indexOfFirst { it.slug == episodeSlug }.coerceAtLeast(0)
            lateinit var dlg: AlertDialog
            val rv = RecyclerView(this@WebPlayerActivity).apply {
                layoutManager = GridLayoutManager(this@WebPlayerActivity, 5)
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, pad / 2)
                clipToPadding = false
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (300 * resources.displayMetrics.density).toInt()
                )
                adapter = WebEpisodeAdapter(srv.items, episodeSlug) { item ->
                    dlg.dismiss()
                    loadWebEpisode(item)
                }
            }
            dlg = AlertDialog.Builder(this@WebPlayerActivity, R.style.Theme_KenhLive_Dialog)
                .setTitle("$filmTitle · ${srv.serverName}")
                .setView(rv)
                .setNegativeButton("Đóng", null)
                .create()
            dlg.show()
            rv.scrollToPosition(curIdx)
            rv.post {
                (rv.findViewHolderForAdapterPosition(curIdx)?.itemView
                    ?: rv.layoutManager?.findViewByPosition(curIdx))?.requestFocus()
            }
        }
    }

    /** Nhãn chip tập rút gọn "Tập N" (cùng chuẩn với EpisodePickerDialog). */
    private fun webEpisodeChipLabel(raw: String): String {
        val name = raw.trim()
        if (name.isEmpty()) return "Tập ?"
        if (name.all { it.isDigit() }) return "Tập $name"
        val trailingNum = Regex("(\\d+)\\s*$").find(name)?.groupValues?.get(1)
        if (trailingNum != null && (name.contains("tập", true) || name.contains("tap", true) ||
                name.contains("ep", true) || name.length <= 22)) {
            trailingNum.toIntOrNull()?.let { return "Tập $it" }
        }
        return if (name.length > 18) name.substring(0, 17) + "…" else name
    }

    private inner class WebEpisodeAdapter(
        private val items: List<NguoncEpisodeItem>,
        private val currentSlug: String?,
        private val onPick: (NguoncEpisodeItem) -> Unit
    ) : RecyclerView.Adapter<WebEpisodeAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvEpisodeName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_episode_chip, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val isCurrent = item.slug == currentSlug
            holder.tv.text = webEpisodeChipLabel(item.name)
            if (isCurrent) {
                holder.tv.setBackgroundResource(R.drawable.bg_badge_hd)
                holder.tv.setTextColor(android.graphics.Color.WHITE)
            } else {
                holder.tv.setBackgroundResource(R.drawable.bg_badge_glass)
                holder.tv.setTextColor(0xFFE2E8F0.toInt())
            }
            holder.itemView.setOnClickListener { onPick(item) }
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                // Không scale animation: vòng focus cam của background lo phần nổi bật;
                // animate trên view bị recycle gây chớp hình "tập ẩn tập hiện" (đồng bộ EpisodePickerDialog).
                if (hasFocus) holder.tv.setTextColor(android.graphics.Color.WHITE)
                else if (!isCurrent) holder.tv.setTextColor(0xFFE2E8F0.toInt())
            }
        }

        override fun getItemCount(): Int = items.size
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
