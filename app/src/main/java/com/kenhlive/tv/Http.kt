package com.kenhlive.tv

import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client dùng chung (repository + UpdateManager):
 * - OkHttp: connection pool, gzip tự động, disk cache 8MB cho JSON GET
 * - Interceptor thêm UA/Referer (API Socolive chặn request trần)
 * - Retry có backoff cho lỗi transient (mạng TV/Wi-Fi chập chờn)
 */
object Http {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    @Volatile private var client: OkHttpClient? = null

    fun init(context: android.content.Context) {
        if (client == null) synchronized(this) {
            if (client == null) {
                // v7.9: trần cache HTTP do người dùng đặt trong Cài đặt -> Dung lượng (mặc định 8MB)
                val capMb = context.getSharedPreferences(StorageManager.PREFS, android.content.Context.MODE_PRIVATE)
                    .getInt(StorageManager.KEY_HTTP_CACHE_MB, StorageManager.DEFAULT_HTTP_CACHE_MB)
                    .coerceIn(4, 64)
                client = build(context.cacheDir, capMb * 1024L * 1024L)
            }
        }
    }

    private fun build(cacheDir: File, cacheBytes: Long = 8L * 1024 * 1024): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .cache(Cache(File(cacheDir, "http_cache"), cacheBytes))
            .addInterceptor { chain ->
                val orig = chain.request()
                val req = orig.newBuilder()
                if (orig.header("User-Agent") == null) req.header("User-Agent", UA)
                if (orig.header("Referer") == null) req.header("Referer", "https://vnres.co/")
                if (orig.header("Accept") == null) req.header("Accept", "application/json, text/plain, */*")
                chain.proceed(req.build())
            }
            .build()

    fun get(): OkHttpClient = client ?: error("Http.init() must be called from Application")

    /**
     * Độ trễ retry thuần, tách riêng để unit test được mà không cần mạng:
     * lần thử thứ i (bắt đầu từ 0) chờ baseMs * (i + 1). Các hàm retry bên
     * dưới dùng đúng helper này, không tự tính lại, để test và code không lệch.
     */
    internal fun retryDelayMs(attemptIndex: Int, baseMs: Long): Long =
        baseMs * (attemptIndex + 1L)

    /** Client kết nối trực tiếp không qua proxy (bỏ qua ProxySelector / http_proxy hệ thống) */
    fun getDirect(): OkHttpClient = get().newBuilder().proxy(java.net.Proxy.NO_PROXY).build()

    /** GET 1 lần, ném IOException nếu khác 200. Ghi sức khỏe API + tôn trọng cầu dao theo host. */
    @Throws(IOException::class)
    fun getOnce(url: String): String = executeTimed(get(), url)

    /** Client timeout dài cho playlist M3U / EPG (file lớn, mạng TV yếu): connect 15s, read 30s, tổng 45s. */
    fun getLong(): OkHttpClient = get().newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    /** GET 1 lần bằng client timeout dài. */
    @Throws(IOException::class)
    fun getLongOnce(url: String): String = executeTimed(getLong(), url)

    @Throws(IOException::class)
    private fun executeTimed(httpClient: OkHttpClient, url: String): String {
        val host = ApiHealth.hostOf(url)
        if (ApiHealth.isCircuitOpen(host)) {
            throw IOException("circuit open for $host (API đang nghỉ sau nhiều lần lỗi)")
        }
        val started = System.currentTimeMillis()
        try {
            val call = httpClient.newCall(Request.Builder().url(url).build())
            call.execute().use { res ->
                if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                val body = res.body?.string() ?: throw IOException("empty body")
                ApiHealth.record(url, ok = true, latencyMs = System.currentTimeMillis() - started)
                return body
            }
        } catch (e: Exception) {
            ApiHealth.record(url, ok = false, latencyMs = System.currentTimeMillis() - started, error = e.message)
            throw if (e is IOException) e else IOException(e)
        }
    }

    /** Retry 3 lần với backoff cho file lớn (playlist/EPG) bằng client timeout dài. */
    @Throws(IOException::class)
    fun getLongWithRetry(url: String, attempts: Int = 3): String {
        var last: Exception? = null
        repeat(attempts) { i ->
            try {
                return getLongOnce(url)
            } catch (e: Exception) {
                last = e
                if (i < attempts - 1) Thread.sleep(retryDelayMs(i, 500L))
            }
        }
        throw last ?: IOException("network error")
    }

    /**
     * Thực thi một [Request] có sẵn (giữ nguyên header tùy biến của nơi gọi) qua client dùng chung,
     * CÓ ghi sức khỏe API + tôn trọng cầu dao theo host — cùng cơ chế với [getOnce].
     * Ném IOException khi: cầu dao đang mở, HTTP khác 2xx (kể cả 5xx), hoặc lỗi mạng.
     */
    @Throws(IOException::class)
    fun executeRequest(request: Request): String {
        val url = request.url.toString()
        val host = ApiHealth.hostOf(url)
        if (ApiHealth.isCircuitOpen(host)) {
            throw IOException("circuit open for $host (API đang nghỉ sau nhiều lần lỗi)")
        }
        val started = System.currentTimeMillis()
        try {
            get().newCall(request).execute().use { res ->
                if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                val body = res.body?.string() ?: throw IOException("empty body")
                ApiHealth.record(url, ok = true, latencyMs = System.currentTimeMillis() - started)
                return body
            }
        } catch (e: Exception) {
            ApiHealth.record(url, ok = false, latencyMs = System.currentTimeMillis() - started, error = e.message)
            throw if (e is IOException) e else IOException(e)
        }
    }

    /**
     * GET trực tiếp không qua proxy (dùng cho các API quốc tế như ColaTV/GVAPI).
     * v7.13: ghi kết quả vào ApiHealth (thành công/thất bại/5xx) và tôn trọng cầu dao theo host,
     * giống đường [executeTimed] — trước đây đường này vòng qua hoàn toàn.
     */
    @Throws(IOException::class)
    fun getDirectWithRetry(url: String, attempts: Int = 2): String {
        val host = ApiHealth.hostOf(url)
        var last: Exception? = null
        repeat(attempts) { i ->
            if (ApiHealth.isCircuitOpen(host)) {
                throw IOException("circuit open for $host (API đang nghỉ sau nhiều lần lỗi)")
            }
            val started = System.currentTimeMillis()
            try {
                val call = getDirect().newCall(Request.Builder().url(url).build())
                call.execute().use { res ->
                    if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                    val body = res.body?.string() ?: throw IOException("empty body")
                    ApiHealth.record(url, ok = true, latencyMs = System.currentTimeMillis() - started)
                    return body
                }
            } catch (e: Exception) {
                ApiHealth.record(url, ok = false, latencyMs = System.currentTimeMillis() - started, error = e.message)
                last = e
                if (i < attempts - 1) Thread.sleep(retryDelayMs(i, 300L))
            }
        }
        throw last ?: IOException("network error")
    }

    /** Retry 3 lần với backoff 400/800ms — lỗi transient là chuyện thường trên mạng TV. */
    @Throws(IOException::class)
    fun getWithRetry(url: String, attempts: Int = 3): String {
        var last: Exception? = null
        repeat(attempts) { i ->
            try {
                return getOnce(url)
            } catch (e: Exception) {
                last = e
                if (i < attempts - 1) Thread.sleep(retryDelayMs(i, 400L))
            }
        }
        throw last ?: IOException("network error")
    }
}
