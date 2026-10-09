package com.kenhlive.tv

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * DeviceMode — Nhận diện nền tảng thiết bị: TV vs Phone:
 * - TV: Điều khiển bằng D-pad remote, focus rõ ràng, không kẹt focus.
 * - Phone: Cảm ứng chạm (touch), hỗ trợ xoay dọc (Portrait) và xoay ngang (Landscape), nút to dễ bấm.
 * - Tối ưu RAM cho Android TV Box cấu hình thấp (lowRam = true).
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
        val hasTouch = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        val hasLeanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        // TV chuẩn: Có Leanback feature HOẶC uiMode TV HOẶC không có màn hình cảm ứng
        val isBoxOrTv = hasLeanback || uiTv || !hasTouch

        mode = if (isBoxOrTv) {
            Mode.TV
        } else {
            Mode.PHONE
        }

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        lowRam = am.isLowRamDevice || (Runtime.getRuntime().maxMemory() / 1024 / 1024) < 192
    }

    /** Cập nhật lại mode khi cấu hình thay đổi */
    fun updateMode(context: Context) {
        init(context)
    }

    fun isLowRam(context: Context): Boolean = lowRam

    /** true nếu màn hình đang portrait (điện thoại dọc). */
    fun isPortrait(context: Context): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    /** true nếu màn hình đang landscape (xoay ngang). */
    fun isLandscape(context: Context): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Tính toán số cột lưới phù hợp theo thiết bị và chiều xoay */
    fun getGridSpanCount(context: Context, defaultTv: Int = 5, phonePortrait: Int = 2, phoneLandscape: Int = 4): Int {
        return if (isTv) {
            defaultTv
        } else if (isPortrait(context)) {
            phonePortrait
        } else {
            phoneLandscape
        }
    }
}
