package com.kenhlive.tv

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Nhận diện nền tảng CHẠY MỘT LẦN lúc Application khởi động.
 * - TV: có leanback feature HOẶC uiMode == television (Google TV, box, emulator ATV)
 * - Phone/tablet: phần còn lại
 * Mọi quyết định layout/interaction đọc từ đây — không gọi PackageManager lặp lại.
 */
object DeviceMode {

    enum class Mode { PHONE, TV }

    @Volatile
    var mode: Mode = Mode.PHONE
        private set

    @Volatile
    var lowRam: Boolean = false
        private set

    /** RAM vật lý (MB) — thiết bị mục tiêu v7: box/TV 1GB. */
    @Volatile
    var totalRamMb: Long = 0L
        private set

    /** true nếu RAM vật lý ≤ ~1GB (mục tiêu tối ưu chính của v7). */
    val ultraLowRam: Boolean get() = totalRamMb in 1..1200

    val isTv: Boolean get() = mode == Mode.TV
    val isPhone: Boolean get() = mode == Mode.PHONE

    fun init(context: Context) {
        val pm = context.packageManager
        val uiTv = (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        val dm = context.resources.displayMetrics
        val isPortraitOrientation = dm.heightPixels > dm.widthPixels
        mode = if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || uiTv) {
            if (isPortraitOrientation) Mode.PHONE else Mode.TV
        } else {
            Mode.PHONE
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        // v7: heap limit KHÔNG phản ánh RAM thật (box 1GB vẫn có thể cấp heap 256MB) → đo RAM vật lý
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        totalRamMb = mi.totalMem / 1024 / 1024
        lowRam = am.isLowRamDevice ||
            (Runtime.getRuntime().maxMemory() / 1024 / 1024) < 192 ||
            totalRamMb in 1..1600
    }

    /** Cập nhật lại mode động khi xoay màn hình hoặc thay đổi kích thước wm size */
    fun updateMode(context: Context) {
        val pm = context.packageManager
        val uiTv = (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        val dm = context.resources.displayMetrics
        val isPortraitOrientation = dm.heightPixels > dm.widthPixels
        mode = if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || uiTv) {
            if (isPortraitOrientation) Mode.PHONE else Mode.TV
        } else {
            Mode.PHONE
        }
    }

    fun isLowRam(context: Context): Boolean = lowRam

    /** true nếu màn hình đang portrait (điện thoại dọc). */
    fun isPortrait(context: Context): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
}
