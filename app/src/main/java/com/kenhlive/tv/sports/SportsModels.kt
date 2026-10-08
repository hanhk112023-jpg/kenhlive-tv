package com.kenhlive.tv.sports

import android.graphics.Color
import com.kenhlive.tv.LiveRoom

/**
 * Các nguồn phát sóng trực tiếp thể thao / bóng đá được tích hợp trong KenhLive TV.
 * Hỗ trợ đa nguồn song song: Socolive, ColaTV, Gà Vàng TV, Khán Đài TV.
 */
enum class SportsSource(
    val id: String,
    val displayName: String,
    val badgeColor: Int,
    val shortTag: String,
    val description: String
) {
    SOCOLIVE(
        id = "socolive",
        displayName = "Socolive",
        badgeColor = Color.parseColor("#10B981"), // Emerald Green
        shortTag = "SOCO",
        description = "Bình luận tiếng Việt sôi động & chuyên sâu"
    ),
    COLATV(
        id = "colatv",
        displayName = "ColaTV",
        badgeColor = Color.parseColor("#FF6500"), // Orange Brand
        shortTag = "COLA",
        description = "Kênh thể thao trực tiếp ColaTV · Độ trễ thấp"
    ),
    GAVANG(
        id = "gavang",
        displayName = "Gà Vàng TV",
        badgeColor = Color.parseColor("#F59E0B"), // Amber / Gold
        shortTag = "GÀ VÀNG",
        description = "Kênh trực tiếp Gà Vàng TV · Âm thanh sống động"
    ),
    KHANDAI(
        id = "khandai",
        displayName = "Khán Đài TV",
        badgeColor = Color.parseColor("#3B82F6"), // Blue Neon
        shortTag = "KHÁN ĐÀI",
        description = "Kênh trực tiếp Khán Đài TV · Đường truyền tốc độ cao"
    );

    companion object {
        fun fromId(id: String): SportsSource = values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: SOCOLIVE

        fun detectSource(roomNum: String, blvName: String = ""): SportsSource = when {
            roomNum.startsWith("cola_") || blvName.contains("Cola", ignoreCase = true) -> COLATV
            roomNum.startsWith("gavang_") || blvName.contains("Gà Vàng", ignoreCase = true) || blvName.contains("Gavang", ignoreCase = true) -> GAVANG
            roomNum.startsWith("khandai_") || blvName.contains("Khán Đài", ignoreCase = true) || blvName.contains("Khandai", ignoreCase = true) -> KHANDAI
            else -> SOCOLIVE
        }

        fun extractRealRoomNum(roomNum: String): String = when {
            roomNum.startsWith("cola_") -> roomNum.removePrefix("cola_")
            roomNum.startsWith("gavang_") -> roomNum.removePrefix("gavang_")
            roomNum.startsWith("khandai_") -> roomNum.removePrefix("khandai_")
            else -> roomNum
        }
    }
}

/**
 * Thông tin một máy chủ / nguồn phát cho trận đấu.
 */
data class SportsServer(
    val source: SportsSource,
    val name: String,
    val roomNum: String,
    val isPrimary: Boolean = false
)
