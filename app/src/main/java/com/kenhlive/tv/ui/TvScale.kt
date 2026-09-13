package com.kenhlive.tv.ui

import android.content.Context
import android.content.res.Configuration
import com.kenhlive.tv.DeviceMode

/**
 * TV 10-foot chuan: UI thiet ke theo px 1080p@320dpi.
 * Nhieu ROM TV khai bao density thap (213dpi = emulator CI) -> toan bo dp/sp teo 66%,
 * user cam giac "giao dien nho qua". Clamp densityDpi ve muc chuan theo do phan rong.
 */
fun Context.applyTvDensity() {
    if (!DeviceMode.isTv) return
    val dm = resources.displayMetrics
    val target = when {
        dm.widthPixels >= 3840 -> 640
        dm.widthPixels >= 1708 -> 320
        else -> return
    }
    if (dm.densityDpi in 1 until (target * 0.85).toInt()) {
        @Suppress("DEPRECATION")
        resources.updateConfiguration(
            Configuration(resources.configuration).apply { densityDpi = target }, dm
        )
    }
}
