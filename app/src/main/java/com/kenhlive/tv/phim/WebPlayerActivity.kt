package com.kenhlive.tv.phim

import android.annotation.SuppressLint
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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kenhlive.tv.R
import com.kenhlive.tv.ui.applyTvDensity
import kotlinx.coroutines.launch
import org.json.JSONObject

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

    private lateinit var webView: WebView
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
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        setContentView(R.layout.activity_web_player)

        embedUrl = intent.getStringExtra("embed_url").orEmpty()
        filmSlug = intent.getStringExtra("film_slug").orEmpty()
        filmTitle = intent.getStringExtra("film_title") ?: "Phim Nguồn C"
        episodeSlug = intent.getStringExtra("episode_slug").orEmpty()
        episodeTitle = intent.getStringExtra("episode_title") ?: "Tập 1"
        posterUrl = intent.getStringExtra("poster_url").orEmpty()
        startPositionMs = intent.getLongExtra("start_position_ms", 0L)

        webView = findViewById(R.id.webViewPlayer)
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

        setupWebView()
        showOsd()

        if (embedUrl.isNotBlank()) {
            val extraHeaders = mapOf(
                "Referer" to NguonC_REFERER,
                "Origin" to "https://phim.nguonc.com"
            )
            webView.loadUrl(embedUrl, extraHeaders)
            handler.postDelayed(progressTrackerRunnable, 4000L)
        } else {
            finish()
        }
    }

    private fun hideSystemUi() {
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
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
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
        s.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                loadingBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loadingBar.visibility = View.GONE

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
                view?.evaluateJavascript(autoPlayJs, null)

                // 2. Tự động tua đến giây đã lưu nếu có tiến độ trước đó
                if (startPositionMs > 1000L && !hasSeekedToStart) {
                    val targetSec = startPositionMs / 1000.0
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
                        view?.evaluateJavascript(seekJs) { res ->
                            if (res != null && res.contains("SEEKED")) {
                                hasSeekedToStart = true
                            }
                        }
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
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                customViewContainer.addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
                customViewContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                topOsd.visibility = View.GONE
            }

            override fun onHideCustomView() {
                if (customView == null) return
                customViewContainer.removeView(customView)
                customView = null
                customViewContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                customViewCallback?.onCustomViewHidden()
                showOsd()
            }
        }
    }

    /**
     * Truy vấn vị trí phát hiện tại từ player trong web.
     */
    private fun queryPlaybackPosition() {
        val queryJs = """
            (function() {
                var v = document.querySelector('video');
                if (v && v.duration > 0) {
                    return JSON.stringify({
                        pos: Math.floor(v.currentTime * 1000),
                        dur: Math.floor(v.duration * 1000),
                        paused: v.paused
                    });
                }
                if (window.jwplayer && typeof window.jwplayer === 'function') {
                    try {
                        var p = window.jwplayer();
                        var pos = Math.floor(p.getPosition() * 1000);
                        var dur = Math.floor(p.getDuration() * 1000);
                        if (dur > 0) {
                            return JSON.stringify({
                                pos: pos,
                                dur: dur,
                                paused: p.getState() === 'paused'
                            });
                        }
                    } catch(e){}
                }
                return '';
            })();
        """.trimIndent()

        webView.evaluateJavascript(queryJs) { rawJson ->
            if (rawJson != null && rawJson != "null" && rawJson.length > 4) {
                try {
                    // Loại bỏ escape ký tự nếu có
                    val cleanJson = if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                        rawJson.substring(1, rawJson.length - 1).replace("\\\"", "\"")
                    } else rawJson

                    val obj = JSONObject(cleanJson)
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

    private fun saveCurrentProgress() {
        if (filmSlug.isBlank() || currentPositionMs <= 0L) return
        lifecycleScope.launch {
            WatchHistoryManager.saveProgress(
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
    }

    private fun showOsd() {
        handler.removeCallbacks(hideOsdRunnable)
        topOsd.visibility = View.VISIBLE
        topOsd.animate().alpha(1f).translationY(0f).setDuration(200).start()
        handler.postDelayed(hideOsdRunnable, 3500L)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_MENU -> {
                    showOsd()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    // Toggle Play / Pause video HTML5 / JWPlayer
                    val togglePlayJs = """
                        (function() {
                            if (window.jwplayer && typeof window.jwplayer === 'function') {
                                try {
                                    var state = window.jwplayer().getState();
                                    if (state === 'playing') window.jwplayer().pause();
                                    else window.jwplayer().play();
                                    return;
                                } catch(e){}
                            }
                            var v = document.querySelector('video');
                            if (v) {
                                if (v.paused) v.play();
                                else v.pause();
                            }
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(togglePlayJs, null)
                    showOsd()
                    queryPlaybackPosition()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (customView != null) {
                        webView.webChromeClient?.onHideCustomView()
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
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        hideSystemUi()
    }

    override fun onPause() {
        super.onPause()
        saveCurrentProgress()
        webView.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideOsdRunnable)
        handler.removeCallbacks(progressTrackerRunnable)
        saveCurrentProgress()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val NguonC_REFERER = "https://phim.nguonc.com/"
    }
}
