package com.kenhlive.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test High cho ApiHealth (Tier 1): circuit breaker theo host.
 * ApiHealth là singleton dùng chung, nên mỗi test dùng host riêng để không
 * làm bẩn trạng thái của test khác.
 */
class ApiHealthTest {

    @Test
    fun testHostOf() {
        assertEquals("example.com", ApiHealth.hostOf("https://example.com/stream.m3u8"))
        assertEquals("not-a-url", ApiHealth.hostOf("not-a-url"))
    }

    @Test
    fun testCircuitOpensAfterThreeFails() {
        val host = "circuit-test-host-1.example"
        assertFalse(ApiHealth.isCircuitOpen(host))
        ApiHealth.record("https://$host/a", false, 10L)
        ApiHealth.record("https://$host/a", false, 10L)
        assertFalse(ApiHealth.isCircuitOpen(host))
        ApiHealth.record("https://$host/a", false, 10L)
        assertTrue(ApiHealth.isCircuitOpen(host))
        assertEquals(3, ApiHealth.lastFor(host)?.consecutiveFails)
    }

    @Test
    fun testSuccessResetsFailsAndClosesCircuit() {
        val host = "circuit-test-host-2.example"
        ApiHealth.record("https://$host/a", false, 10L)
        ApiHealth.record("https://$host/a", false, 10L)
        ApiHealth.record("https://$host/a", false, 10L)
        assertTrue(ApiHealth.isCircuitOpen(host))
        ApiHealth.record("https://$host/a", true, 10L)
        assertFalse(ApiHealth.isCircuitOpen(host))
        assertEquals(0, ApiHealth.lastFor(host)?.consecutiveFails)
    }
}
