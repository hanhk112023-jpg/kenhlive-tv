package com.kenhlive.tv

import android.content.Context
import java.io.File

/**
 * v7.9 — Kiểm soát dung lượng hoàn toàn từ trong app.
 *
 * Đo được: APK đã cài, cache ảnh (Coil, thư mục img_cache), cache HTTP (http_cache),
 * phần còn lại của cacheDir (JSON API, EPG, cache đĩa của repository...), cơ sở dữ
 * liệu lịch sử xem (kenhlive_cinema.db) và tổng dữ liệu người dùng.
 *
 * Đặt trần được (SharedPreferences "storage", áp dụng khi app khởi động lại):
 * - Trần cache ảnh: 25 / 45 / 80 / 120 MB (mặc định 45MB như v7.8)
 * - Trần cache HTTP: 8 / 16 / 32 MB (mặc định 8MB)
 */
object StorageManager {

    const val PREFS = "storage"
    const val KEY_IMAGE_CACHE_MB = "image_cache_mb"
    const val KEY_HTTP_CACHE_MB = "http_cache_mb"
    const val DEFAULT_IMAGE_CACHE_MB = 45
    const val DEFAULT_HTTP_CACHE_MB = 8

    val IMAGE_CAP_OPTIONS_MB = intArrayOf(25, 45, 80, 120)
    val HTTP_CAP_OPTIONS_MB = intArrayOf(8, 16, 32)

    data class Usage(
        val apkBytes: Long,
        val imageCacheBytes: Long,
        val httpCacheBytes: Long,
        val otherCacheBytes: Long,
        val historyDbBytes: Long
    ) {
        val totalCacheBytes: Long get() = imageCacheBytes + httpCacheBytes + otherCacheBytes
    }

    fun imageCapMb(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_IMAGE_CACHE_MB, DEFAULT_IMAGE_CACHE_MB)

    fun httpCapMb(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_HTTP_CACHE_MB, DEFAULT_HTTP_CACHE_MB)

    fun setImageCapMb(ctx: Context, mb: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_IMAGE_CACHE_MB, mb).apply()
    }

    fun setHttpCapMb(ctx: Context, mb: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_HTTP_CACHE_MB, mb).apply()
    }

    private fun dirSize(f: File?): Long {
        if (f == null || !f.exists()) return 0L
        if (f.isFile) return f.length()
        var total = 0L
        val stack = java.util.ArrayDeque<File>()
        stack.add(f)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (cur.isFile) total += cur.length()
            else cur.listFiles()?.forEach { stack.add(it) }
        }
        return total
    }

    fun measure(ctx: Context): Usage {
        val apk = try {
            File(ctx.applicationInfo.sourceDir).length()
        } catch (_: Exception) {
            0L
        }
        val imgDir = File(ctx.cacheDir, "img_cache")
        val httpDir = File(ctx.cacheDir, "http_cache")
        val img = dirSize(imgDir)
        val http = dirSize(httpDir)
        val allCache = dirSize(ctx.cacheDir)
        val db = try {
            dirSize(ctx.getDatabasePath("kenhlive_cinema.db")) +
                dirSize(File(ctx.getDatabasePath("kenhlive_cinema.db").path + "-wal")) +
                dirSize(File(ctx.getDatabasePath("kenhlive_cinema.db").path + "-shm"))
        } catch (_: Exception) {
            0L
        }
        return Usage(
            apkBytes = apk,
            imageCacheBytes = img,
            httpCacheBytes = http,
            otherCacheBytes = (allCache - img - http).coerceAtLeast(0L),
            historyDbBytes = db
        )
    }

    /** Xóa toàn bộ cache (ảnh + HTTP + cache đĩa khác). Không đụng vào lịch sử xem / cài đặt. */
    fun clearAllCaches(ctx: Context) {
        try {
            coil.Coil.imageLoader(ctx).memoryCache?.clear()
            coil.Coil.imageLoader(ctx).diskCache?.clear()
        } catch (_: Exception) {
        }
        try {
            Http.get().cache?.evictAll()
        } catch (_: Exception) {
        }
        try {
            ctx.cacheDir.listFiles()?.forEach { child ->
                // img_cache/http_cache đã xử lý ở trên; xóa phần còn lại (JSON, EPG...)
                if (child.name != "img_cache" && child.name != "http_cache") {
                    if (child.isDirectory) child.deleteRecursively() else child.delete()
                }
            }
        } catch (_: Exception) {
        }
        try {
            com.kenhlive.tv.phim.NguoncRepository.clearCache()
        } catch (_: Exception) {
        }
    }

    fun format(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
        else String.format(java.util.Locale.US, "%.1f MB", mb)
    }
}
