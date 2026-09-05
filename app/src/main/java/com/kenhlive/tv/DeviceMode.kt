package com.kenhlive.tv

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build

object DeviceMode {
    /** true nếu chạy trên Android TV (leanback). */
    fun isTv(activity: Activity): Boolean =
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun isTv(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** true nếu màn hình đang portrait (điện thoại dọc). */
    fun isPortrait(activity: Activity): Boolean =
        activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    /** true nếu nên dùng layout phone (grid 2 cột, cảm ứng). */
    fun usePhoneLayout(activity: Activity): Boolean = !isTv(activity)

    /** Detect low RAM device: ActivityManager.isLowRamDevice hoặc RAM < 2GB */
    fun isLowRamDevice(ctx: Context): Boolean {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am.isLowRamDevice) return true
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            // totalMem có từ API 16, nếu < 1.8GB coi là low
            memInfo.totalMem > 0 && memInfo.totalMem < 1_900_000_000L
        } catch (e: Exception) {
            false
        }
    }

    fun isLowRam(ctx: Context): Boolean = isLowRamDevice(ctx)

    /** Memory class để tính cache size */
    fun memoryClassMb(ctx: Context): Int {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.memoryClass
        } catch (e: Exception) { 128 }
    }
}
