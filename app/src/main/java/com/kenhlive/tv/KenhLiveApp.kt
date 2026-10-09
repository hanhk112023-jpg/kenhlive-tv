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

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .allowRgb565(true)
            .bitmapConfig(if (lowRam || DeviceMode.isTv) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
            .crossfade(if (lowRam || DeviceMode.isTv) false else true)
            .respectCacheHeaders(false)
            .memoryCache {
                MemoryCache.Builder(this).maxSizePercent(if (lowRam) 0.08 else 0.15).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("img_cache"))
                    .maxSizeBytes(60L * 1024 * 1024)
                    .build()
            }
            .build()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            coil.Coil.imageLoader(this).memoryCache?.clear()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        coil.Coil.imageLoader(this).memoryCache?.clear()
    }
}
