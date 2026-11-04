package com.kenhlive.tv.phim

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test cho phần thuần của WatchHistoryManager có thể test được mà không cần
 * Context/SQLite: định dạng tiến độ xem trong WatchHistoryItem.
 * Toàn bộ vòng đời DB (lưu/đọc/xóa/backup/migration) không test được bằng
 * unit test JVM thuần vì cần SQLite/Context; xem báo cáo cuối để biết
 * cần Robolectric hoặc test instrumented cho phần đó.
 */
class WatchHistoryItemTest {

    private fun item(positionMs: Long, durationMs: Long, progressPct: Int): WatchHistoryItem {
        return WatchHistoryItem(
            slug = "test-film",
            filmName = "Test Film",
            posterUrl = "",
            episodeName = "Tập 1",
            episodeSlug = "tap-1",
            embedUrl = "",
            positionMs = positionMs,
            durationMs = durationMs,
            progressPct = progressPct,
            lastWatchedAt = 0L,
            isCompleted = false
        )
    }

    @Test
    fun testFormattedProgressWithDuration() {
        assertEquals("15:20 / 45:00 (34%)", item(920_000L, 2_700_000L, 34).formattedProgress())
    }

    @Test
    fun testFormattedProgressWithoutDuration() {
        assertEquals("Đã xem 15:20", item(920_000L, 0L, 0).formattedProgress())
    }

    @Test
    fun testFormattedProgressZero() {
        assertEquals("Đã lưu tập", item(0L, 0L, 0).formattedProgress())
    }

    @Test
    fun testFormattedTimeOnly() {
        assertEquals("15:20/45:00", item(920_000L, 2_700_000L, 34).formattedTimeOnly())
        assertEquals("15:20", item(920_000L, 0L, 0).formattedTimeOnly())
        assertEquals("", item(0L, 0L, 0).formattedTimeOnly())
    }

    @Test
    fun testFormattedProgressOverOneHour() {
        assertEquals("01:24:30 / 02:05:00 (67%)", item(5_070_000L, 7_500_000L, 67).formattedProgress())
    }
}
