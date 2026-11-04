package com.kenhlive.tv.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test cho việc nâng http:// lên https:// trong IptvRepository (Tier 0).
 * Chỉ host đã chứng minh hỗ trợ HTTPS trong code mới được nâng; host lạ
 * giữ nguyên và sẽ lỗi ở tầng cleartext, đúng chủ đích.
 */
class IptvUpgradeTest {

    @Test
    fun testUpgradeKnownHost() {
        assertEquals(
            "https://live.canthotv.vn/live/tv/chunklist.m3u8",
            IptvRepository.upgradeToHttps("http://live.canthotv.vn/live/tv/chunklist.m3u8")
        )
        assertEquals(
            "https://phimimg.com/a.jpg",
            IptvRepository.upgradeToHttps("http://phimimg.com/a.jpg")
        )
        assertEquals(
            "https://tv360.vn/x",
            IptvRepository.upgradeToHttps("http://tv360.vn/x")
        )
    }

    @Test
    fun testUpgradeUnknownHostKeepsHttp() {
        assertEquals(
            "http://unknown.example.com/stream.m3u8",
            IptvRepository.upgradeToHttps("http://unknown.example.com/stream.m3u8")
        )
    }

    @Test
    fun testUpgradeHttpsUnchanged() {
        assertEquals(
            "https://live.canthotv.vn/live/tv/chunklist.m3u8",
            IptvRepository.upgradeToHttps("https://live.canthotv.vn/live/tv/chunklist.m3u8")
        )
    }

    @Test
    fun testParseM3uUpgradesKnownHosts() {
        val m3u = "#EXTM3U\n" +
            "#EXTINF:-1 tvg-name=\"Test\" tvg-logo=\"http://phimimg.com/a.jpg\" group-title=\"Việt Nam\",Test\n" +
            "http://live.canthotv.vn/live/tv/chunklist.m3u8\n" +
            "#EXTINF:-1 tvg-name=\"Other\" tvg-logo=\"\" group-title=\"Việt Nam\",Other\n" +
            "http://unknown.example.com/stream.m3u8\n"
        val channels = IptvRepository.parseM3u(m3u, "Việt Nam", true, false)
        assertEquals(2, channels.size)
        assertEquals("https://live.canthotv.vn/live/tv/chunklist.m3u8", channels[0].url)
        assertEquals("https://phimimg.com/a.jpg", channels[0].logo)
        assertEquals("http://unknown.example.com/stream.m3u8", channels[1].url)
    }
}
