package com.kenhlive.tv

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test cho helper retry delay thuần trong Http (Tier 0).
 * Retry mạng thật (getWithRetry/getLongWithRetry) không test bằng unit test
 * JVM thuần vì cần OkHttp/server giả; helper này đảm bảo công thức delay
 * dùng trong code và test không lệch nhau.
 */
class HttpRetryDelayTest {

    @Test
    fun testRetryDelay400Base() {
        assertEquals(400L, Http.retryDelayMs(0, 400L))
        assertEquals(800L, Http.retryDelayMs(1, 400L))
        assertEquals(1200L, Http.retryDelayMs(2, 400L))
    }

    @Test
    fun testRetryDelayOtherBases() {
        assertEquals(500L, Http.retryDelayMs(0, 500L))
        assertEquals(1000L, Http.retryDelayMs(1, 500L))
        assertEquals(300L, Http.retryDelayMs(0, 300L))
        assertEquals(600L, Http.retryDelayMs(1, 300L))
    }
}
