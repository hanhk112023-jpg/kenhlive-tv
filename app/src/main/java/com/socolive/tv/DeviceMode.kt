package com.socolive.tv

import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration

object DeviceMode {
    /** true nếu chạy trên Android TV (leanback). */
    fun isTv(activity: Activity): Boolean =
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** true nếu màn hình đang portrait (điện thoại dọc). */
    fun isPortrait(activity: Activity): Boolean =
        activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    /** true nếu nên dùng layout phone (grid 2 cột, cảm ứng). */
    fun usePhoneLayout(activity: Activity): Boolean = !isTv(activity)
}
