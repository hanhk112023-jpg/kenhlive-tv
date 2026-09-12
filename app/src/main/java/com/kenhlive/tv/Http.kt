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
            if (client == null) client = build(context.cacheDir)
        }
    }

    private fun build(cacheDir: File): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .cache(Cache(File(cacheDir, "http_cache"), 8L * 1024 * 1024))
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", UA)
                    .header("Referer", "https://vnres.co/")
                    .header("Accept", "application/json, text/plain, */*")
                    .build()
                chain.proceed(req)
            }
            .build()

    fun get(): OkHttpClient = client ?: error("Http.init() must be called from Application")

    /** GET 1 lần, ném IOException nếu khác 200. */
    @Throws(IOException::class)
    fun getOnce(url: String): String {
        val call = get().newCall(Request.Builder().url(url).build())
        call.execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            return res.body?.string() ?: throw IOException("empty body")
        }
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
                if (i < attempts - 1) Thread.sleep(400L * (i + 1))
            }
        }
        throw last ?: IOException("network error")
    }
}
