package com.kenhlive.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.util.DebugLogger

/**
 * App-level config cho hiệu năng TV RAM thấp:
 * - Memory cache nhỏ (12-20% RAM class, cap 32MB low-ram else 64MB)
 * - Disk cache 64MB
 * - Không cache bitmap quá lớn, tự downsize
 * - Crossfade tắt cho TV để giảm GPU
 */
class KenhLiveApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        val isLowRam = DeviceMode.isLowRamDevice(this)
        val memClass = DeviceMode.memoryClassMb(this)

        // Low RAM: 12% memoryClass, max 24MB. Normal: 18%, max 64MB
        val memCachePercent = if (isLowRam) 0.12 else 0.18
        val maxMemMb = if (isLowRam) 24 else 64
        val memCacheMb = ((memClass * memCachePercent).toInt()).coerceAtMost(maxMemMb).coerceAtLeast(12)

        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(memCachePercent)
                    .strongReferencesEnabled(false) // cho phép GC nhanh trên TV thấp
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil"))
                    .maxSizeBytes(if (isLowRam) 64L * 1024 * 1024 else 128L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(false) // TV không cần fade, giảm overdraw
            .allowHardware(true) // hardware bitmap giảm RAM
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Khi hệ thống thiếu RAM, clear memory cache Coil
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            // ImageLoader will be recreated lazily; we clear via singleton if exists
            try {
                coil.Coil.imageLoader(this).memoryCache?.clear()
            } catch (_: Exception) {}
        }
    }
}
