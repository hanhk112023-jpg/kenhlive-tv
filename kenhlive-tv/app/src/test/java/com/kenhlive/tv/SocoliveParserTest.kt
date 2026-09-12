package com.kenhlive.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocoliveParserTest {

    @Test
    fun `stripJsonp unwraps callback wrapper`() {
        assertEquals("{\"a\":1}", SocoliveParser.stripJsonp("rooms({\"a\":1})"))
        assertEquals("{\"a\":1}", SocoliveParser.stripJsonp("{\"a\":1}"))
    }

    @Test
    fun `splitTitle separates league and match`() {
        val (league, match) = SocoliveParser.splitTitle("Ngoại Hạng Anh: Arsenal vs Chelsea")
        assertEquals("Ngoại Hạng Anh", league)
        assertEquals("Arsenal vs Chelsea", match)
    }

    @Test
    fun `splitTitle falls back when no colon`() {
        val (league, match) = SocoliveParser.splitTitle("Giao hữu quốc tế")
        assertEquals("SocoLive", league)
        assertEquals("Giao hữu quốc tế", match)
    }

    @Test
    fun `parseLiveRooms filters offline and duplicate rooms`() {
        val body = """
            {"data":{"cat1":[
              {"roomNum":"r1","liveStatus":1,"viewCount":120,"title":"V-League: A vs B",
               "anchor":{"nickName":"BLV One","icon":"http://x/1.png"},"cover":"http://x/c1.jpg"},
              {"roomNum":"r2","liveStatus":0,"viewCount":999,"title":"V-League: C vs D","anchor":{}},
              {"roomNum":"r1","liveStatus":1,"viewCount":1,"title":"V-League: A vs B","anchor":{}},
              {"roomNum":"r3","liveStatus":1,"viewCount":300,"title":"La Liga: E vs F",
               "anchor":{"nickName":"BLV Two","icon":""},"cover":""}
            ]}}
        """.trimIndent()
        val rooms = SocoliveParser.parseLiveRooms(body)
        assertEquals(2, rooms.size)
        // sorted by viewers desc
        assertEquals("r3", rooms[0].roomNum)
        assertEquals("BLV One", rooms[1].blvName)
        assertEquals("V-League", rooms[1].league)
        assertEquals("A vs B", rooms[1].matchTitle)
        assertEquals("http://x/c1.jpg", rooms[1].cover)
    }

    @Test
    fun `groupRooms merges rooms of same match and sums viewers`() {
        val rooms = listOf(
            LiveRoom("r1", "BLV1", "", 100, "A vs B", "V-League"),
            LiveRoom("r2", "BLV2", "", 400, "A vs B", "V-League"),
            LiveRoom("r3", "BLV3", "", 50, "C vs D", "La Liga")
        )
        val groups = SocoliveParser.groupRooms(rooms)
        assertEquals(2, groups.size)
        val top = groups[0]
        assertEquals("A vs B", top.matchTitle)
        assertEquals(500, top.totalViewers)
        assertEquals(2, top.count)
        assertEquals("BLV2", top.top.blvName) // phòng nhiều view nhất lên đầu
    }

    @Test
    fun `parseStream prefers hdM3u8`() {
        val body = """{"data":{"stream":{"flv":"http://x/f","m3u8":"http://x/m","hdM3u8":"http://x/hd"}}}"""
        assertEquals("http://x/hd", SocoliveParser.parseStream(body))
        val body2 = """{"data":{"stream":{"flv":"http://x/f"}}}"""
        assertEquals("http://x/f", SocoliveParser.parseStream(body2))
        val body3 = """{"data":{}}"""
        assertNull(SocoliveParser.parseStream(body3))
    }

    @Test
    fun `fmtViewers formats thousands with comma decimal`() {
        assertEquals("999", SocoliveRepository.fmtViewers(999))
        assertEquals("1,2K", SocoliveRepository.fmtViewers(1200))
        assertEquals("12,3K", SocoliveRepository.fmtViewers(12345))
    }

    @Test
    fun `leagueWeight orders major leagues first`() {
        assertTrue(
            SocoliveRepository.leagueWeight("Ngoại Hạng Anh") <
                SocoliveRepository.leagueWeight("Giải vô địch Qatar")
        )
    }

    @Test
    fun `textNorm strips vietnamese diacritics`() {
        assertEquals("real", TextNorm.norm("Real"))
        assertEquals("chuc", TextNorm.norm("Chúc"))
        assertEquals("da", TextNorm.norm("Đá"))
        assertTrue(TextNorm.norm("Ngoại Hạng Anh").contains("ngoai hang"))
    }
}
