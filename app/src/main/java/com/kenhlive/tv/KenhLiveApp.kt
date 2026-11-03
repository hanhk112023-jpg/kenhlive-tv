package com.kenhlive.tv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import coil.disk.DiskCache

/**
 * Tối ưu RAM cho TV box/điện thoại yếu (1–2GB):
 * - Memory cache ảnh: 8% (low-RAM) / 15% heap — ảnh vốn downsample theo view size
 * - Disk cache giới hạn 60MB
 * - Định dạng Bitmap RGB_565: Cắt giảm 50% RAM bộ đệm đồ hoạ cho toàn bộ logo/avatar/poster
 * - Tắt crossfade trên TV mode để loại bỏ animation giật cục trên GPU Mali yếu
 * - Khởi động DeviceMode + Http một lần duy nhất
 */
class KenhLiveApp : Application(), ImageLoaderFactory {

    companion object {
        lateinit var app: KenhLiveApp
            private set
        val appContext: Context get() = app.applicationContext

        val lowRam: Boolean get() = DeviceMode.lowRam
        fun isLowRam(ctx: Context): Boolean = DeviceMode.lowRam
    }

    override fun onCreate() {
        super.onCreate()
        app = this
        DeviceMode.init(this)
        Http.init(this)
        com.kenhlive.tv.phim.WatchHistoryManager.init(this)
    }

    override fun newImageLoader(): ImageLoader {
        val okHttpClient = okhttp3.OkHttpClient.Builder()
            // KHÔNG ép NO_PROXY nữa (v0.1.2): bản cũ cấm Coil dùng proxy hệ thống khiến
            // ảnh không đi theo proxy VN như các API còn lại — trên môi trường có proxy
            // (CI/giả lập) request ảnh đi thẳng bằng IP máy chủ và bị Cloudflare của
            // img.nguonc.com chặn 403 -> poster trắng toàn bộ dù dữ liệu phim vẫn tải.
            // Máy người dùng thật không đặt proxy nên hành vi ngoài đời không đổi.
            .addInterceptor { chain ->
                val original = chain.request()
                val host0 = original.url.host
                // Referer phải khớp host ảnh (v0.1.2): bản cũ gắn Referer colatv.org cho
                // MỌI request ảnh — CDN NguonC kiểm tra referer chéo domain sẽ trả 403.
                val referer = when {
                    host0.equals("img.nguonc.com", ignoreCase = true) ||
                        host0.endsWith(".nguonc.com", ignoreCase = true) -> "https://phim.nguonc.com/"
                    host0.equals("phimimg.com", ignoreCase = true) ||
                        host0.endsWith(".phimimg.com", ignoreCase = true) -> "https://phimapi.com/"
                    else -> null
                }
                val reqB = original.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                if (referer != null) reqB.header("Referer", referer) else reqB.removeHeader("Referer")
                val req = reqB.build()
                val host = req.url.host
                val isPhimImg = host.equals("phimimg.com", ignoreCase = true) ||
                    host.endsWith(".phimimg.com", ignoreCase = true)
                if (!isPhimImg) {
                    chain.proceed(req)
                } else {
                    // Host ảnh phimimg.com hay lỗi trên máy cũ/mạng yếu: nếu lần tải đầu ném
                    // IOException hoặc trả HTTP >= 400 thì thử lại MỘT lần với cùng đường dẫn
                    // trên phimapi.com (phục vụ cùng kho ảnh). Host khác giữ nguyên hành vi.
                    var firstError: java.io.IOException? = null
                    var firstResponse: okhttp3.Response? = null
                    try {
                        firstResponse = chain.proceed(req)
                    } catch (e: java.io.IOException) {
                        firstError = e
                    }
                    if (firstError == null && firstResponse != null && firstResponse.code < 400) {
                        firstResponse
                    } else {
                        // Đóng response lỗi trước khi retry để không rò kết nối
                        firstResponse?.close()
                        val apiHost = if (host.equals("phimimg.com", ignoreCase = true)) {
                            "phimapi.com"
                        } else {
                            host.removeSuffix("phimimg.com") + "phimapi.com"
                        }
                        val retryReq = req.newBuilder()
                            .url(req.url.newBuilder().host(apiHost).build())
                            .header("Referer", "https://phimapi.com/")
                            .build()
                        try {
                            chain.proceed(retryReq)
                        } catch (e: java.io.IOException) {
                            throw (firstError ?: e)
                        }
                    }
                }
            }
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .allowRgb565(true)
            .bitmapConfig(if (lowRam || DeviceMode.isTv) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
            .crossfade(if (lowRam || DeviceMode.isTv) false else true)
            .respectCacheHeaders(false)
            .memoryCache {
                MemoryCache.Builder(this).maxSizePercent(if (lowRam) 0.12 else 0.18).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("img_cache"))
                    // v7.9: trần cache ảnh do người dùng đặt trong Cài đặt -> Dung lượng (mặc định 45MB)
                    .maxSizeBytes(StorageManager.imageCapMb(this@KenhLiveApp).coerceIn(10, 200) * 1024L * 1024L)
                    .build()
            }
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // CHỈ xả memory cache ảnh khi hệ thống báo cạn kiệt thật sự (TRIM_MEMORY_COMPLETE).
        // Điều kiện cũ (>= TRIM_MEMORY_RUNNING_LOW / UI_HIDDEN / BACKGROUND) khớp gần như MỌI
        // callback trim: trên TV box 1GB các callback này bắn liên tục, khiến Coil memory cache
        // bị xoá không ngừng -> poster không bao giờ kịp sống trong cache, thẻ phim hiện trống.
        // Cache danh sách phim (NguoncRepository) rất nhẹ nên không xoá ở đây nữa.
        if (level >= TRIM_MEMORY_COMPLETE) {
            coil.Coil.imageLoader(this).memoryCache?.clear()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        coil.Coil.imageLoader(this).memoryCache?.clear()
    }
}
