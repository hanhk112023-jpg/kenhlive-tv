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
import android.net.http.SslError
import android.view.LayoutInflater
import android.webkit.SslErrorHandler
import android.webkit.WebResourceResponse
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
import android.net.Uri
import android.os.Message
import java.io.ByteArrayInputStream

/**
 * Tách link HLS trực tiếp khỏi URL embed: một số server bọc link phát trong tham số
 * "?url=<m3u8>" của trang player. Tách được thì phát native ngay, khỏi mở WebView —
 * cùng logic với iOS (VodWebPlayerView.directHlsUrl / extractDirectM3u8).
 */
internal fun unwrapDirectM3u8(embed: String): String {
    val e = embed.trim()
    if (e.isEmpty()) return ""
    if (e.startsWith("http") && e.contains(".m3u8", ignoreCase = true) && !e.contains("?url=", ignoreCase = true)) {
        return e
    }
    return try {
        val q = Uri.parse(e).getQueryParameter("url").orEmpty()
        if (q.contains(".m3u8", ignoreCase = true)) q else ""
    } catch (_: Throwable) {
        ""
    }
}

/** Domain quảng cáo/tracker thuần (không phải CDN dùng chung) — chặn ở tầng mạng của WebView. */
private val AD_HOSTS = listOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
    "popads.net", "popcash.net", "exoclick.com", "exosrv.com", "juicyads.com", "adcash.com",
    "propellerads.com", "clickadu.com", "adsterra.com", "hilltopads.com", "onclickmax.com",
    "mgid.com", "outbrain.com", "taboola.com", "revcontent.com", "smartadserver.com",
    "a-ads.com", "trafficjunky.com", "tsyndicate.com", "adskeeper.com", "marketgid.com",
    "adnxs.com", "rubiconproject.com", "pubmatic.com", "openx.net", "adsrvr.org", "bidvertiser.com"
)

/**
 * WebPlayerActivity — Trình phát phim web chuyên nghiệp cho embed KKPhim (player.phimapi.com):
 * - Hỗ trợ WebView tăng tốc phần cứng, giải mã JWPlayer & HTML5 video.
 * - Tự động thiết lập Referer https://phimapi.com/ và Chrome User-Agent chuẩn.
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
        // Thanh/OSD ẩn mà focus đang nằm trên một nút của chúng: trả focus về WebView,
        // nếu không focus sẽ bị hệ thống dọn đi nơi khác và phím D-pad "chết" theo view GONE.
        if (isControlFocused()) {
            try { webView?.requestFocus() } catch (_: Throwable) {}
        }
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
    private var btnBackView: View? = null

    // Hộp lỗi tải trang embed (H2): chỉ lỗi khung hình chính mới hiện; lỗi tài nguyên
    // phụ (ảnh/script con của nhà cung cấp) tuyệt đối không dựng màn hình lỗi.
    private var webErrorBox: View? = null
    private var tvWebError: TextView? = null
    private var btnWebRetry: Button? = null
    private var btnWebErrorBack: Button? = null
    private var mainFrameLoadFailed: Boolean = false
    // Săn luồng HLS thật bên trong trang embed để chuyển sang trình phát native
    // (phát native = không quảng cáo của trang embed, giống trải nghiệm iOS).
    private var nativeHandoffDone: Boolean = false
    private var sniffedStreamUrl: String = ""
    // Số lần khung chính đã nhảy sang domain lạ so với domain embed gốc: chỉ thả
    // 1 lần redirect hợp lệ của nhà cung cấp, các lần sau coi là quảng cáo chiếm trang.
    private var crossDomainNavCount: Int = 0
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
        // Embed bọc link HLS (?url=<m3u8>) hoặc tự nó là link HLS: phát native ngay,
        // không mở WebView — không dính quảng cáo (giống iOS).
        val unwrappedStart = unwrapDirectM3u8(embedUrl)
        if (unwrappedStart.isNotBlank()) {
            filmSlug = intent.getStringExtra("film_slug").orEmpty()
            filmTitle = intent.getStringExtra("film_title") ?: "Phim"
            episodeSlug = intent.getStringExtra("episode_slug").orEmpty()
            episodeTitle = intent.getStringExtra("episode_title") ?: "Tập 1"
            posterUrl = intent.getStringExtra("poster_url").orEmpty()
            startPositionMs = intent.getLongExtra("start_position_ms", 0L)
            launchNativePlayerAndFinish(unwrappedStart)
            return
        }
        filmSlug = intent.getStringExtra("film_slug").orEmpty()
        filmTitle = intent.getStringExtra("film_title") ?: "Phim"
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

        btnBackView = findViewById(R.id.btnBack)
        btnBackView?.setOnClickListener {
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

        webErrorBox = findViewById(R.id.webErrorBox)
        tvWebError = findViewById(R.id.tvWebError)
        btnWebRetry = findViewById(R.id.btnWebRetry)
        btnWebErrorBack = findViewById(R.id.btnWebErrorBack)
        btnWebRetry?.setOnClickListener { retryLoadEmbed() }
        btnWebErrorBack?.setOnClickListener {
            saveCurrentProgress()
            finish()
        }

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
                        "Referer" to EMBED_REFERER,
                        "Origin" to "https://phimapi.com"
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

    /** URL có phải link playlist HLS trực tiếp không (xét phần path, bỏ qua query token). */
    private fun isHlsUrl(u: String): Boolean {
        if (u.isBlank()) return false
        val p = try { Uri.parse(u).path } catch (_: Throwable) { null } ?: return false
        return p.contains(".m3u8", ignoreCase = true)
    }

    /** Chuyển sang trình phát native đúng 1 lần — điểm đến của cả hai đường săn luồng. */
    private fun handoffToNative(url: String) {
        if (nativeHandoffDone || isFinishing) return
        nativeHandoffDone = true
        handler.removeCallbacks(progressTrackerRunnable)
        try { webView?.stopLoading() } catch (_: Throwable) {}
        android.util.Log.i("WebPlayerActivity", "Native handoff: $url")
        launchNativePlayerAndFinish(url)
    }

    /**
     * Ghi nhận một playlist HLS vừa thấy trên mạng (gọi từ thread nền của WebView).
     * Sau 1,5 giây mới xác nhận chuyển giao: ưu tiên URL mà thẻ <video> đang phát thật
     * (chắc chắn nhất), không lấy được mới dùng chính playlist đã bắt gặp.
     */
    private fun onStreamCandidate(url: String) {
        if (nativeHandoffDone || mainFrameLoadFailed) return
        if (sniffedStreamUrl.isNotEmpty()) return
        sniffedStreamUrl = url
        handler.postDelayed({
            if (nativeHandoffDone || mainFrameLoadFailed || isFinishing) return@postDelayed
            probeVideoSrc { src ->
                if (nativeHandoffDone || isFinishing) return@probeVideoSrc
                when {
                    isHlsUrl(src) -> handoffToNative(src)
                    sniffedStreamUrl.isNotBlank() -> handoffToNative(sniffedStreamUrl)
                }
            }
        }, 1500L)
    }

    /** Đọc currentSrc của thẻ <video> (tìm sâu trong iframe cùng nguồn như các hàm JS khác). */
    private fun probeVideoSrc(cb: (String) -> Unit) {
        val js = """
            (function() {
                function f(w) {
                    try {
                        var v = w.document.querySelector('video');
                        if (v) return v;
                        for (var i = 0; i < w.frames.length; i++) {
                            var r = f(w.frames[i]);
                            if (r) return r;
                        }
                    } catch (e) {}
                    return null;
                }
                var v = f(window);
                return v ? (v.currentSrc || v.src || '') : '';
            })();
        """.trimIndent()
        try {
            val wv = webView
            if (wv == null) {
                cb("")
            } else {
                wv.evaluateJavascript(js) { res ->
                    val clean = res?.trim()?.removeSurrounding("\"")
                        ?.replace("\\/", "/")
                        ?.replace("\\u0026", "&")
                        .orEmpty()
                    cb(clean)
                }
            }
        } catch (_: Throwable) {
            cb("")
        }
    }

    /** Đặt lại trạng thái săn luồng + bộ đếm nhảy domain khi đổi tập hoặc thử lại. */
    private fun resetStreamHunt() {
        sniffedStreamUrl = ""
        crossDomainNavCount = 0
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
        // Không cho trang embed mở cửa sổ/tab mới (kênh popup quảng cáo kinh điển):
        // window.open bị từ chối ở onCreateWindow, còn nếu trang cố điều hướng
        // khung chính sang domain lạ thì shouldOverrideUrlLoading chặn.
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        val defaultUa = try { WebSettings.getDefaultUserAgent(this) } catch (_: Throwable) { "" }
        s.userAgentString = if (defaultUa.isNotBlank()) {
            defaultUa.replace("; wv", "")
        } else {
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Một lượt tải mới bắt đầu (kể cả bấm Thử lại): dọn trạng thái lỗi cũ
                hideWebLoadError()
                loadingBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loadingBar.visibility = View.GONE
                if (mainFrameLoadFailed) {
                    // Trang vừa nạp xong là trang lỗi (HTTP/SSL/mạng): giữ nguyên màn hình
                    // lỗi + focus ở nút Thử lại, không trao focus cho WebView, không chạy
                    // autoplay/tua tiếp tục xem trên một trang không có video.
                    return
                }
                // Trao focus cho trang web (trình phát của nhà cung cấp nhận được phím khi JS
                // không điều khiển được) + thăm dò sớm khả năng điều khiển bằng JS.
                // Không cướp focus nếu người dùng đang đứng trên một nút của thanh điều khiển.
                if (!isControlFocused()) {
                    try { view?.requestFocus() } catch (_: Throwable) {}
                }
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
                // CHỈ lỗi ở khung hình chính mới dựng màn hình lỗi; lỗi tài nguyên phụ
                // (ảnh, script, iframe con...) bỏ qua như cũ — trước đây lỗi main-frame
                // chỉ tắt spinner, để lại màn hình đen vô hạn không lối thoát rõ ràng.
                if (request?.isForMainFrame == true) {
                    android.util.Log.e(
                        "WebPlayerActivity",
                        "Embed load error ${error?.errorCode}: ${error?.description} (${request.url})"
                    )
                    showWebLoadError("Không tải được trang phát phim.\nKiểm tra kết nối mạng rồi bấm Thử lại.")
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                val code = errorResponse?.statusCode ?: 0
                if (request?.isForMainFrame == true && code >= 400) {
                    android.util.Log.e("WebPlayerActivity", "Embed HTTP $code (${request.url})")
                    showWebLoadError(
                        "Máy chủ phát phim trả về lỗi $code.\n" +
                            "Nguồn phát có thể đang bận hoặc đã bị gỡ — thử lại sau ít phút, hoặc Quay lại chọn tập/server khác."
                    )
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                // TUYỆT ĐỐI không proceed() bỏ qua lỗi SSL: hủy tải và báo lỗi rõ ràng
                // (trước đây lỗi SSL bị hủy im lặng, cũng thành màn hình đen).
                handler?.cancel()
                android.util.Log.e("WebPlayerActivity", "Embed SSL error: $error")
                showWebLoadError(
                    "Kết nối bảo mật tới máy chủ phát phim bị lỗi (SSL).\n" +
                        "Kiểm tra ngày giờ của thiết bị và kết nối mạng rồi bấm Thử lại."
                )
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return super.shouldInterceptRequest(view, request)
                val host = uri.host?.lowercase().orEmpty()
                if (host.isNotEmpty() && AD_HOSTS.any { host == it || host.endsWith(".$it") }) {
                    // Chặn yêu cầu tới mạng quảng cáo/tracker: không banner/popup chèn,
                    // trang nhẹ hơn hẳn trên máy yếu.
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                // Bắt gặp yêu cầu playlist HLS của player trong trang → ứng viên chuyển
                // sang trình phát native (xem không quảng cáo). Chỉ ghi nhận, việc
                // chuyển giao được xác nhận ở onStreamCandidate trên main thread.
                val path = uri.path?.lowercase().orEmpty()
                if (path.contains(".m3u8")) {
                    val full = uri.toString()
                    if (full != embedUrl) onStreamCandidate(full)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val nextUrl = request?.url?.toString() ?: return false
                if (nextUrl.startsWith("http://") || nextUrl.startsWith("https://")) {
                    // Chặn quảng cáo "chiếm trang": khung chính nhảy sang domain lạ
                    // (thường khi bấm Play trúng lớp quảng cáo trong suốt). Cùng domain
                    // hoặc sub-domain của trang embed thì thả; nhảy lạ lần đầu được thả
                    // (vài nhà cung cấp redirect 1 lần sang domain player thật), từ lần
                    // thứ hai trở đi chặn đứng.
                    if (request?.isForMainFrame == true) {
                        val nextHost = request.url.host?.lowercase().orEmpty()
                        val embedHost = try { Uri.parse(embedUrl).host?.lowercase().orEmpty() } catch (_: Throwable) { "" }
                        if (nextHost.isNotEmpty() && embedHost.isNotEmpty() &&
                            nextHost != embedHost && !nextHost.endsWith(".$embedHost") && !embedHost.endsWith(".$nextHost")
                        ) {
                            crossDomainNavCount++
                            if (crossDomainNavCount > 1) {
                                android.util.Log.w("WebPlayerActivity", "Blocked cross-domain nav to $nextHost")
                                return true
                            }
                        }
                    }
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
            // Từ chối mọi cửa sổ mới (window.open / target=_blank): kênh popup quảng cáo.
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                return false
            }

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
                        src: v.currentSrc || '',
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

                    // Thẻ <video> đang phát một playlist HLS thật → chuyển sang trình
                    // phát native để xem không quảng cáo (chỉ 1 lần, handoffToNative tự gác).
                    val playingSrc = obj.optString("src", "")
                    if (isHlsUrl(playingSrc)) {
                        handoffToNative(playingSrc)
                    }

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
        // Tập mới bọc link HLS trực tiếp (hoặc có sẵn m3u8): sang native luôn, khỏi qua web.
        val unwrappedEp = unwrapDirectM3u8(item.embed)
        val directEp = when {
            item.m3u8.isNotBlank() -> item.m3u8
            unwrappedEp.isNotBlank() -> unwrappedEp
            else -> ""
        }
        if (directEp.isNotBlank()) {
            startPositionMs = 0L
            launchNativePlayerAndFinish(directEp)
            return
        }
        resetStreamHunt()
        tvEpisodeTitle.text = episodeTitle
        startPositionMs = 0L
        currentPositionMs = 0L
        currentDurationMs = 0L
        hasSeekedToStart = false
        hasJsControl = false
        try { loadingBar.visibility = View.VISIBLE } catch (_: Throwable) {}
        val extraHeaders = mapOf(
            "Referer" to EMBED_REFERER,
            "Origin" to "https://phimapi.com"
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

    /** Focus hiện tại có đang nằm trên chrome của app (nút Back ở OSD trên, một nút trong
     *  thanh điều khiển dưới, hoặc trong hộp lỗi) hay không. Nếu có, phím D-pad/OK phải
     *  được trả cho cơ chế focus mặc định thay vì ép thành lệnh phát/tua cho WebView. */
    private fun isControlFocused(): Boolean {
        var v: View? = currentFocus ?: return false
        while (v != null) {
            if (v === webControlBar || v === topOsd || v === webErrorBox) return true
            v = v.parent as? View
        }
        return false
    }

    /** Hiện màn hình lỗi tải embed (chỉ gọi cho lỗi khung hình chính) + focus nút Thử lại. */
    private fun showWebLoadError(message: String) {
        mainFrameLoadFailed = true
        loadingBar.visibility = View.GONE
        tvWebError?.text = message
        webErrorBox?.visibility = View.VISIBLE
        btnWebRetry?.post { btnWebRetry?.requestFocus() }
    }

    private fun hideWebLoadError() {
        mainFrameLoadFailed = false
        webErrorBox?.visibility = View.GONE
    }

    /** Nút Thử lại ở màn hình lỗi: nạp lại đúng URL embed kèm Referer như lần tải đầu. */
    private fun retryLoadEmbed() {
        val wv = webView ?: return
        resetStreamHunt()
        hideWebLoadError()
        loadingBar.visibility = View.VISIBLE
        try { wv.requestFocus() } catch (_: Throwable) {}
        if (embedUrl.isNotBlank()) {
            val extraHeaders = mapOf(
                "Referer" to EMBED_REFERER,
                "Origin" to "https://phimapi.com"
            )
            wv.loadUrl(embedUrl, extraHeaders)
        } else {
            wv.reload()
        }
    }

    private fun showOsd() {
        handler.removeCallbacks(hideOsdRunnable)
        topOsd.visibility = View.VISIBLE
        topOsd.animate().alpha(1f).translationY(0f).setDuration(200).start()
        webControlBar?.visibility = View.VISIBLE
        btnWebPlayPause?.setImageResource(if (lastPaused) R.drawable.ic_play else R.drawable.ic_pause)
        handler.postDelayed(hideOsdRunnable, 3500L)
    }

    /** Dispatch mặc định (focus-search + click của nút đang focus), bọc try/catch như cũ. */
    private fun superDispatch(event: KeyEvent): Boolean = try {
        super.dispatchKeyEvent(event)
    } catch (t: Throwable) {
        true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 0. Hộp lỗi tải embed đang hiện (mẫu PlayerActivity): BACK = thoát; nếu chưa có
        //    nút nào giữ focus thì ép focus về nút Thử lại; mọi phím còn lại để dispatch
        //    mặc định lo (D-pad di chuyển giữa Thử lại/Quay lại, OK kích hoạt nút).
        if (webErrorBox?.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    saveCurrentProgress()
                    finish()
                    return true
                }
                if (btnWebRetry?.hasFocus() != true && btnWebErrorBack?.hasFocus() != true) {
                    btnWebRetry?.requestFocus()
                }
            }
            return superDispatch(event)
        }
        try {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_INFO -> {
                        showOsd()
                        if (!isControlFocused()) {
                            // Focus đang kẹt ở WebView: đưa focus vào chrome của app —
                            // UP lên nút Back ở OSD trên, DOWN/MENU/INFO xuống nút
                            // Phát/Dừng giữa thanh điều khiển — để các phím tiếp theo
                            // di chuyển được giữa các nút. Khi focus đã nằm trên một nút
                            // thì KHÔNG nuốt phím: rơi xuống superDispatch ở cuối hàm
                            // cho focus-search tự nhiên (nextFocusUp/Down đã khai trong XML).
                            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                                btnBackView?.requestFocus()
                            } else {
                                btnWebPlayPause?.requestFocus()
                            }
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        // Focus ở trên nút trong thanh: ◄ để di chuyển focus giữa các nút
                        // (rơi xuống superDispatch), không tua video. Focus ở WebView:
                        // tua như cũ khi JS điều khiển được video.
                        if (!isControlFocused() && hasJsControl) {
                            webSeekBy(-10)
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        // Chỉ chặn phím khi JS điều khiển được video; nếu không, để WebView/trình phát
                        // của nhà cung cấp tự nhận phím (trước đây chặn vô điều kiện mà JS lại không tìm
                        // thấy video trong iframe -> bấm tua không có tác dụng gì)
                        if (hasJsControl) {
                            webSeekBy(-10)
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!isControlFocused() && hasJsControl) {
                            webSeekBy(10)
                            return true
                        }
                    }
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
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        // Focus đang ở trên một nút (Tập trước/Tiếp, Danh sách tập, tua,
                        // Back...): để dispatch mặc định kích hoạt ĐÚNG nút đó — rơi xuống
                        // superDispatch ở cuối hàm. Trước đây nhánh này ép mọi OK thành
                        // Play/Pause nên toàn bộ nút trong thanh là "nút chết" với remote.
                        // Focus ở WebView (không ở nút nào): giữ hành vi cũ — OK = Play/Pause.
                        if (!isControlFocused()) {
                            webTogglePlayPause()
                            return true
                        }
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
        return superDispatch(event)
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
        // Embed KKPhim chạy trên player.phimapi.com — Referer phải thuộc phimapi.com
        // thì máy chủ embed mới chấp nhận (trước đây để referer của nguồn cũ đã bỏ).
        private const val EMBED_REFERER = "https://phimapi.com/"
    }
}
