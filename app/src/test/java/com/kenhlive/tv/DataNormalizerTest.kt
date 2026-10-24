package com.kenhlive.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class DataNormalizerTest {

    @Test
    fun testRemoveDiacritics() {
        assertEquals("dong thap", DataNormalizer.removeDiacritics("Đồng Tháp"))
        assertEquals("ngoai hang anh", DataNormalizer.removeDiacritics("Ngoại Hạng Anh"))
        assertEquals("duc", DataNormalizer.removeDiacritics("Đức"))
    }

    @Test
    fun testNormalizeLeague() {
        assertEquals("Ngoại Hạng Anh", DataNormalizer.normalizeLeague("Premier League"))
        assertEquals("Ngoại Hạng Anh", DataNormalizer.normalizeLeague("EPL"))
        assertEquals("Cúp C1 Châu Âu", DataNormalizer.normalizeLeague("Champions League"))
        assertEquals("Cúp C1 Châu Âu", DataNormalizer.normalizeLeague("Cup C1"))
        assertEquals("La Liga", DataNormalizer.normalizeLeague("LaLiga"))
        assertEquals("Serie A", DataNormalizer.normalizeLeague("Serie A"))
        assertEquals("V-League 1", DataNormalizer.normalizeLeague("V-League"))
    }

    @Test
    fun testParseMatchTitle() {
        val parsed1 = DataNormalizer.parseMatch("[Ngoại Hạng Anh] Arsenal vs Chelsea - Trực tiếp")
        assertEquals("Ngoại Hạng Anh", parsed1.league)
        assertEquals("Arsenal", parsed1.host)
        assertEquals("Chelsea", parsed1.guest)
        assertEquals("Arsenal vs Chelsea", parsed1.cleanTitle)

        val parsed2 = DataNormalizer.parseMatch("Cúp C1: Real Madrid 2 - 1 Barcelona | BLV Giàng A Phò")
        assertEquals("Cúp C1 Châu Âu", parsed2.league)
        assertEquals("Real Madrid", parsed2.host)
        assertEquals("Barcelona", parsed2.guest)
        assertEquals(2, parsed2.hostScore)
        assertEquals(1, parsed2.guestScore)
        assertEquals("Real Madrid vs Barcelona", parsed2.cleanTitle)

        // Test format từ ColaTV Official API kèm tỉ số thời gian thực
        val parsedCola = DataNormalizer.parseMatch("CLB Persib Bandung 1 - 0 Thể Công - Viettel", "AFC Champions League Two")
        assertEquals("CLB Persib Bandung", parsedCola.host)
        assertEquals("Thể Công - Viettel", parsedCola.guest)
        assertEquals(1, parsedCola.hostScore)
        assertEquals(0, parsedCola.guestScore)
        assertEquals("CLB Persib Bandung vs Thể Công - Viettel", parsedCola.cleanTitle)
    }

    @Test
    fun testMatchTiming() {
        val now = System.currentTimeMillis()
        // Đang đá 45 phút trước
        assertTrue(DataNormalizer.isMatchLive(now - 45 * 60_000L, true))
        assertFalse(DataNormalizer.isMatchFinished(now - 45 * 60_000L))

        // Đã đá xong 5 tiếng trước
        assertFalse(DataNormalizer.isMatchLive(now - 5 * 3600_000L, false))
        assertTrue(DataNormalizer.isMatchFinished(now - 5 * 3600_000L))

        // Sắp đá 2 tiếng nữa
        assertTrue(DataNormalizer.isMatchUpcoming(now + 2 * 3600_000L))
        assertFalse(DataNormalizer.isMatchLive(now + 2 * 3600_000L, false))
    }

    @Test
    fun testNormalizeImageUrl() {
        assertEquals("https://img.nguonc.com/uploads/poster.jpg", DataNormalizer.normalizeImageUrl("/uploads/poster.jpg"))
        assertEquals("https://img.nguonc.com/poster.jpg", DataNormalizer.normalizeImageUrl("poster.jpg"))
        assertEquals("https://example.com/poster.jpg", DataNormalizer.normalizeImageUrl("https://example.com/poster.jpg"))
    }

    @Test
    fun testNormalizeChannelKey() {
        assertEquals("vtv1", DataNormalizer.normalizeChannelKey("VTV1 HD"))
        assertEquals("vtv1", DataNormalizer.normalizeChannelKey("vtv1.vn@HD"))
        assertEquals("vtv1", DataNormalizer.normalizeChannelKey("Kênh VTV1"))
        assertEquals("htv7", DataNormalizer.normalizeChannelKey("HTV7 FHD"))
        assertEquals("thvl1", DataNormalizer.normalizeChannelKey("Truyền hình Vĩnh Long 1"))
    }

    @Test
    fun testIsMatchLiveDetection() {
        val dummyRoom = LiveRoom("101", "BLV Batman", "", 10, "Arsenal vs Chelsea", "Ngoại Hạng Anh", notice = "")
        
        // 1. Explicit upcoming markers -> Must be FALSE (Upcoming)
        val groupUpcoming1 = LiveMatchGroup("[SẮP ĐÁ] 21:00 Arsenal vs Chelsea", "Ngoại Hạng Anh", dummyRoom, 5, 1)
        val parsed1 = DataNormalizer.parseMatch(groupUpcoming1.matchTitle, groupUpcoming1.league)
        assertFalse(MatchCardsAdapter.isMatchLive(groupUpcoming1, parsed1))

        val groupUpcoming2 = LiveMatchGroup("Man City vs Liverpool", "Ngoại Hạng Anh", dummyRoom.copy(notice = "Trận đấu chưa bắt đầu"), 5, 1)
        val parsed2 = DataNormalizer.parseMatch(groupUpcoming2.matchTitle, groupUpcoming2.league)
        assertFalse(MatchCardsAdapter.isMatchLive(groupUpcoming2, parsed2))

        // 2. Score available -> Must be TRUE (Live)
        val groupLiveWithScore = LiveMatchGroup("Real Madrid 2 - 1 Barcelona", "La Liga", dummyRoom, 1500, 1)
        val parsedScore = DataNormalizer.parseMatch(groupLiveWithScore.matchTitle, groupLiveWithScore.league)
        assertTrue(MatchCardsAdapter.isMatchLive(groupLiveWithScore, parsedScore))

        // 3. Explicit Live tag -> Must be TRUE (Live)
        val groupExplicitLive = LiveMatchGroup("[LIVE] Bayern Munich vs Dortmund", "Bundesliga", dummyRoom, 800, 1)
        val parsedLive = DataNormalizer.parseMatch(groupExplicitLive.matchTitle, groupExplicitLive.league)
        assertTrue(MatchCardsAdapter.isMatchLive(groupExplicitLive, parsedLive))
    }

    @Test
    fun testExtractKickoffTime() {
        assertEquals("21:00", MatchCardsAdapter.extractKickoffTime("[SẮP ĐÁ] 21:00 Arsenal vs Chelsea", ""))
        assertEquals("19:30", MatchCardsAdapter.extractKickoffTime("Man City vs Liverpool", "Lên sóng lúc 19:30"))
        assertEquals("02:45", MatchCardsAdapter.extractKickoffTime("02:45 Real Madrid vs AC Milan", ""))
        assertEquals("", MatchCardsAdapter.extractKickoffTime("Hà Nội vs HAGL", "Đang trực tiếp"))
    }
}
