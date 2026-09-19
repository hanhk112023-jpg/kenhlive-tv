package com.kenhlive.tv.iptv

import com.kenhlive.tv.R
import java.util.Locale

object IptvLogoResolver {

    /**
     * Tra cứu logo nội bộ cực nét trong APK theo tên kênh.
     * Trả về Resource ID của logo nội bộ, hoặc null nếu để Coil tải từ URL mạng.
     */
    fun resolveLocalLogo(channelName: String, group: String): Int? {
        val name = channelName.lowercase(Locale.ROOT)
        val grp = group.lowercase(Locale.ROOT)

        return when {
            // Đài thể thao DAZN
            name.contains("dazn") || grp.contains("dazn") -> R.drawable.logo_dazn

            // Formula 1
            name.contains("f1") || name.contains("formula") -> R.drawable.logo_f1

            // Đài thể thao Sky Sports
            name.contains("sky sport") || name.contains("skysport") || grp.contains("sky") -> R.drawable.logo_skysports

            // Kênh ANTV / Công An
            name.contains("antv") || name.contains("an ninh") -> R.drawable.logo_antv

            // Kênh VTV (VTV1, VTV2, VTV3...)
            name.contains("vtv") -> R.drawable.logo_vtv

            // Các kênh truyền hình Việt Nam địa phương
            grp.contains("việt nam") || grp.contains("viet nam") || name.contains("tv") -> R.drawable.logo_vntv

            else -> null
        }
    }
}
