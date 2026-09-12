package com.kenhlive.tv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import coil.disk.DiskCache

/**
 * Tối ưu RAM cho TV box/điện thoại yếu (1–2GB):
 * - Memory cache ảnh: 8% (low-RAM) / 15% heap — ảnh vốn downsample theo view size
 * - Disk cache giới hạn 60MB
 * - Khởi động DeviceMode + Http một lần duy nhất
 */
class KenhLiveApp : Application(), ImageLoaderFactory {

    companion object {
        val lowRam: Boolean get() = DeviceMode.lowRam
        fun isLowRam(ctx: Context): Boolean = DeviceMode.lowRam
    }

    override fun onCreate() {
        super.onCreate()
        DeviceMode.init(this)
        Http.init(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
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
}
