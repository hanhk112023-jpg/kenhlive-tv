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
 * - Memory cache ảnh: 12% heap (mặc định Coil 25%) — ảnh vốn downsample theo view size
 * - RGB565 trên máy low-RAM: giảm 50% bộ nhớ mỗi bitmap (mất chút dải màu, ảnh thumbnail không đáng kể)
 * - Disk cache giới hạn 60MB
 */
class KenhLiveApp : Application(), ImageLoaderFactory {

    companion object {
        @Volatile var lowRam = false; private set
        fun isLowRam(ctx: Context): Boolean {
            if (!lowRam) {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                lowRam = am.isLowRamDevice ||
                    (Runtime.getRuntime().maxMemory() / 1024 / 1024) < 192 // heap < 192MB
            }
            return lowRam
        }
    }

    override fun onCreate() {
        super.onCreate()
        isLowRam(this)
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
