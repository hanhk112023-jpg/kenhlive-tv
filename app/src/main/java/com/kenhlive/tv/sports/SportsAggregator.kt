package com.kenhlive.tv.sports

import com.kenhlive.tv.LiveMatchGroup
import com.kenhlive.tv.LiveRoom

/**
 * Bộ tổng hợp và làm giàu dữ liệu thể thao đa nguồn (Multi-source Sports Aggregator).
 * Tích hợp song song 4 hạ tầng phát sóng: Socolive, ColaTV, Gà Vàng TV, Khán Đài TV.
 */
object SportsAggregator {

    /**
     * Bổ sung các server phát sóng ColaTV, Gà Vàng, Khán Đài vào mỗi trận đấu.
     */
    fun enrichMatchGroup(group: LiveMatchGroup): LiveMatchGroup {
        if (group.rooms.isEmpty()) return group

        val top = group.top
        val baseRoomNum = SportsSource.extractRealRoomNum(top.roomNum)

        // Kiểm tra xem đã có các nguồn phụ chưa
        val hasCola = group.rooms.any { it.roomNum.startsWith("cola_") }
        val hasGavang = group.rooms.any { it.roomNum.startsWith("gavang_") }
        val hasKhandai = group.rooms.any { it.roomNum.startsWith("khandai_") }

        val extraRooms = mutableListOf<LiveRoom>()

        if (!hasCola) {
            extraRooms.add(
                LiveRoom(
                    roomNum = "cola_$baseRoomNum",
                    blvName = "[ColaTV] BLV Cola HD",
                    avatar = top.avatar,
                    viewers = (top.viewers * 0.88).toInt().coerceAtLeast(1200),
                    matchTitle = group.matchTitle,
                    league = group.league,
                    cover = top.cover,
                    category = top.category,
                    blvLevel = "Server 1",
                    score = top.score,
                    focusCount = top.focusCount,
                    notice = "Kênh thể thao trực tiếp ColaTV · Luồng phát tốc độ cao",
                    hostIcon = group.hostIcon,
                    guestIcon = group.guestIcon
                )
            )
        }

        if (!hasGavang) {
            extraRooms.add(
                LiveRoom(
                    roomNum = "gavang_$baseRoomNum",
                    blvName = "[Gà Vàng] BLV Gà Rừng HD",
                    avatar = top.avatar,
                    viewers = (top.viewers * 0.76).toInt().coerceAtLeast(980),
                    matchTitle = group.matchTitle,
                    league = group.league,
                    cover = top.cover,
                    category = top.category,
                    blvLevel = "Server 2",
                    score = top.score,
                    focusCount = top.focusCount,
                    notice = "Kênh trực tiếp Gà Vàng TV · Âm thanh vòm sống động",
                    hostIcon = group.hostIcon,
                    guestIcon = group.guestIcon
                )
            )
        }

        if (!hasKhandai) {
            extraRooms.add(
                LiveRoom(
                    roomNum = "khandai_$baseRoomNum",
                    blvName = "[Khán Đài] BLV Khán Đài 1 HD",
                    avatar = top.avatar,
                    viewers = (top.viewers * 0.65).toInt().coerceAtLeast(850),
                    matchTitle = group.matchTitle,
                    league = group.league,
                    cover = top.cover,
                    category = top.category,
                    blvLevel = "Server 3",
                    score = top.score,
                    focusCount = top.focusCount,
                    notice = "Kênh trực tiếp Khán Đài TV · Bình luận chuyên sâu",
                    hostIcon = group.hostIcon,
                    guestIcon = group.guestIcon
                )
            )
        }

        val allRooms = group.rooms + extraRooms
        return group.copy(rooms = allRooms)
    }

    /**
     * Làm giàu danh sách các nhóm trận đấu trực tiếp.
     */
    fun enrichMatchGroups(groups: List<LiveMatchGroup>): List<LiveMatchGroup> {
        return groups.map { enrichMatchGroup(it) }
    }

    /**
     * Lấy danh sách 4 máy chủ khả dụng cho một phòng/trận đấu bất kỳ.
     */
    fun getAvailableServers(baseRoomNum: String): List<SportsServer> {
        val cleanRoom = SportsSource.extractRealRoomNum(baseRoomNum)
        return listOf(
            SportsServer(SportsSource.SOCOLIVE, "Máy Chủ Socolive (Chính)", cleanRoom, isPrimary = true),
            SportsServer(SportsSource.COLATV, "Máy Chủ ColaTV (Dự phòng 1)", "cola_$cleanRoom"),
            SportsServer(SportsSource.GAVANG, "Máy Chủ Gà Vàng (Dự phòng 2)", "gavang_$cleanRoom"),
            SportsServer(SportsSource.KHANDAI, "Máy Chủ Khán Đài (Dự phòng 3)", "khandai_$cleanRoom")
        )
    }
}
