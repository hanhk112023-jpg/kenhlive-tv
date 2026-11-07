package com.kenhlive.tv.sports

import com.kenhlive.tv.LiveMatchGroup
import com.kenhlive.tv.LiveRoom

/**
 * Bộ tổng hợp và làm giàu dữ liệu thể thao đa nguồn (Multi-source Sports Aggregator).
 * Tích hợp song song 4 hạ tầng phát sóng: Socolive, ColaTV, Gà Vàng 33, Giờ Vàng TV.
 */
object SportsAggregator {

    /**
     * Làm giàu một nhóm trận (logo/tỉ số/đồng hồ từ registry đa nguồn).
     *
     * ĐÃ BỎ cơ chế tự chế phòng ColaTV/Gà Vàng/Giờ Vàng cho mọi trận Socolive bằng cách
     * tiền tố số phòng Socolive kèm viewers nhân hệ số bịa: các phòng đó không tồn tại ở
     * đài kia, khi phát sẽ rơi vào resolver đoán stream -> người dùng xem trận khác dưới
     * nhãn trận này. Từ nay chỉ giữ các phòng THẬT đã có sẵn trong group — hiển thị
     * thiếu còn hơn bịa.
     */
    fun enrichMatchGroup(group: LiveMatchGroup): LiveMatchGroup {
        if (group.rooms.isEmpty()) return group
        val enriched = TeamLogoRegistry.enrich(group)
        // Lan truyen du kien tran (ti so / dong ho / gio da / logo doi) xuong tung
        // phong: truoc day chi group co, nen card tung phong BLV va player khong
        // bao gio thay duoc ti so that. Phong van uu tien du lieu rieng neu co.
        return enriched.copy(
            rooms = enriched.rooms.map { r ->
                r.copy(
                    hostIcon = r.hostIcon.ifBlank { enriched.hostIcon },
                    guestIcon = r.guestIcon.ifBlank { enriched.guestIcon },
                    kickoffTime = r.kickoffTime.ifBlank { enriched.kickoffTime },
                    matchTimeMs = if (r.matchTimeMs > 0L) r.matchTimeMs else enriched.matchTimeMs,
                    isLiveGame = r.isLiveGame ?: enriched.isLiveGame,
                    hostScore = r.hostScore ?: enriched.hostScore,
                    guestScore = r.guestScore ?: enriched.guestScore,
                    liveClock = r.liveClock.ifBlank { enriched.liveClock }
                )
            }
        )
    }

    /**
     * Làm giàu danh sách các nhóm trận đấu trực tiếp.
     */
    fun enrichMatchGroups(groups: List<LiveMatchGroup>): List<LiveMatchGroup> {
        return groups.map { enrichMatchGroup(it) }
    }

    /**
     * Tạo danh sách trận đấu chuyên biệt cho từng nguồn phát sóng (không gộp chung vào Socolive).
     *
     * CHỈ giữ các phòng THẬT thuộc đúng nguồn đó đã có sẵn trong group. Trước đây hàm này
     * tự chế một phòng cho đài được chọn từ số phòng Socolive (kèm viewers bịa) — phòng
     * không tồn tại ở đài kia và khi phát sẽ thành stream của trận khác. Không có phòng
     * thật của nguồn -> loại trận khỏi danh sách của nguồn đó.
     */
    fun createProviderMatchGroups(
        baseGroups: List<LiveMatchGroup>,
        source: SportsSource
    ): List<LiveMatchGroup> {
        return baseGroups.mapNotNull { g ->
            if (g.rooms.isEmpty()) return@mapNotNull null
            val rooms = when (source) {
                SportsSource.SOCOLIVE -> g.rooms.filter {
                    !it.roomNum.startsWith("cola_") &&
                    !it.roomNum.startsWith("gavang") &&
                    !it.roomNum.startsWith("giovang") &&
                    !it.roomNum.startsWith("khandai_")
                }
                SportsSource.COLATV -> g.rooms.filter { it.roomNum.startsWith("cola_") }
                SportsSource.GAVANG33 -> g.rooms.filter {
                    it.roomNum.startsWith("gavang33_") || it.roomNum.startsWith("gavang_")
                }
                SportsSource.GIOVANG -> g.rooms.filter {
                    it.roomNum.startsWith("giovang_") || it.roomNum.startsWith("khandai_")
                }
            }
            if (rooms.isEmpty()) null else g.copy(rooms = rooms)
        }
    }

    /**
     * Danh sách máy chủ khả dụng cho một phòng: chỉ gồm máy chủ của ĐÚNG nguồn sở hữu
     * phòng đó. Trước đây hàm này luôn liệt kê thêm 3 máy chủ dự phòng bịa bằng cách
     * tiền tố số phòng — các máy chủ đó không tồn tại và resolver không còn đoán
     * stream thay thế, nên liệt kê chúng chỉ dẫn người dùng vào ngõ cụt.
     */
    fun getAvailableServers(baseRoomNum: String): List<SportsServer> {
        val source = SportsSource.detectSource(baseRoomNum)
        val label = when (source) {
            SportsSource.SOCOLIVE -> "Máy Chủ Socolive (Chính)"
            SportsSource.COLATV -> "Máy Chủ ColaTV"
            SportsSource.GAVANG33 -> "Máy Chủ Gà Vàng 33"
            SportsSource.GIOVANG -> "Máy Chủ Giờ Vàng"
        }
        return listOf(SportsServer(source, label, baseRoomNum, isPrimary = true))
    }
}
