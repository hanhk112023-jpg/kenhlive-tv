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
}
