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
import com.kenhlive.tv.R
import com.kenhlive.tv.ui.applyTvDensity

/**
 * WebPlayerActivity — Trình phát phim web chuyên nghiệp cho Phim Nguồn C (StreamC embed):
 * - Hỗ trợ WebView tăng tốc phần cứng, giải mã JWPlayer & HTML5 video
 * - Tự động thiết lập Referer https://phim.nguonc.com/ và Chrome User-Agent
 * - Điều khiển thân thiện chuẩn Android TV (D-pad Center = Play/Pause, OSD tự ẩn sau 3.5s)
 * - Toàn màn hình Immersive Mode, chống tắt màn hình khi xem phim
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
    private var filmTitle: String = ""
    private var episodeTitle: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTvDensity()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        setContentView(R.layout.activity_web_player)

        embedUrl = intent.getStringExtra("embed_url").orEmpty()
        filmTitle = intent.getStringExtra("film_title") ?: "Phim Nguồn C"
        episodeTitle = intent.getStringExtra("episode_title") ?: "Tập 1"

        webView = findViewById(R.id.webViewPlayer)
        loadingBar = findViewById(R.id.playerLoading)
        topOsd = findViewById(R.id.playerTopOsd)
        tvFilmTitle = findViewById(R.id.tvFilmTitle)
        tvEpisodeTitle = findViewById(R.id.tvEpisodeTitle)
        customViewContainer = findViewById(R.id.customViewContainer)

        tvFilmTitle.text = filmTitle
        tvEpisodeTitle.text = episodeTitle

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
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
                // Tự động kích hoạt play và enter fullscreen trên video HTML5 nếu có
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
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (customView != null) {
                        webView.webChromeClient?.onHideCustomView()
                        return true
                    }
                    if (topOsd.visibility == View.VISIBLE) {
                        topOsd.visibility = View.GONE
                        finish()
                        return true
                    }
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
        webView.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideOsdRunnable)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val NguonC_REFERER = "https://phim.nguonc.com/"
    }
}
