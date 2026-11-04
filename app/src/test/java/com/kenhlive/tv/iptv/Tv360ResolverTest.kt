package com.kenhlive.tv.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test High cho phần thuần của Tv360Resolver (Tier 1): nhận dạng kênh và
 * trích channelId. Giải mã AES và gọi API không test bằng unit test JVM
 * thuần vì cần mạng và khóa phía server; chỉ test logic không cần mạng.
 */
class Tv360ResolverTest {

    @Test
    fun testIsTv360() {
        assertTrue(Tv360Resolver.isTv360("tv360://20"))
        assertTrue(Tv360Resolver.isTv360("https://tv360.vn/live?ch=20"))
        assertFalse(Tv360Resolver.isTv360("https://live.canthotv.vn/live/tv/chunklist.m3u8"))
        assertFalse(Tv360Resolver.isTv360(""))
    }

    @Test
    fun testExtractChannelIdFromScheme() {
        assertEquals("20", Tv360Resolver.extractChannelId("tv360://20"))
        assertEquals("10043", Tv360Resolver.extractChannelId("tv360://10043?foo=bar"))
    }

    @Test
    fun testExtractChannelIdFromUrl() {
        assertEquals("20", Tv360Resolver.extractChannelId("https://tv360.vn/live?ch=20"))
        assertEquals("33", Tv360Resolver.extractChannelId("https://tv360.vn/live?ch=33&x=1"))
    }

    @Test
    fun testGetBackupForKnownChannel() {
        // Kênh 20 có fallback trực tiếp trong code; chỉ khẳng định có fallback
        // https và khác URL gốc, không khẳng định host cụ thể để test không
        // vỡ khi fallback thay đổi host.
        val backup = Tv360Resolver.getBackupForChannel("tv360://20")
        assertTrue(backup != null)
        assertTrue(backup!!.startsWith("https://"))
        assertTrue(backup != "tv360://20")
    }
}
