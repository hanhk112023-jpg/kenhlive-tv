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

    val isTv: Boolean get() = mode == Mode.TV
    val isPhone: Boolean get() = mode == Mode.PHONE

    fun init(context: Context) {
        val pm = context.packageManager
        val uiTv = (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        mode = if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || uiTv) Mode.TV else Mode.PHONE
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        lowRam = am.isLowRamDevice || (Runtime.getRuntime().maxMemory() / 1024 / 1024) < 192
    }

    fun isLowRam(context: Context): Boolean = lowRam

    /** true nếu màn hình đang portrait (điện thoại dọc). */
    fun isPortrait(context: Context): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
}
