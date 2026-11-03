package com.kenhlive.tv

import java.util.concurrent.ConcurrentHashMap

/**
 * v7.9 — Sổ theo dõi sức khỏe API + cầu dao (circuit breaker) theo host.
 *
 * - Mọi request qua [Http] đều ghi lại: host, thành công/thất bại, độ trễ, thời điểm.
 * - Host lỗi liên tiếp >= [FAIL_THRESHOLD] lần -> mở cầu dao [COOLDOWN_MS]:
 *   [Http] từ chối gọi ngay (ném IOException) để repository rơi về cache/fallback
 *   thay vì bắt người dùng chờ timeout nhiều lần. Hết hạn nghỉ tự đóng cầu dao.
 * - Màn Cài đặt dùng [snapshot] + [ApiHealthChecker] để hiển thị trạng thái nguồn.
 */
object ApiHealth {

    data class Entry(
        val host: String,
        val ok: Boolean,
        val latencyMs: Long,
        val atMs: Long,
        val error: String? = null,
        val consecutiveFails: Int = 0
    )

    private const val FAIL_THRESHOLD = 3
    private const val COOLDOWN_MS = 2 * 60 * 1000L

    private val lastByHost = ConcurrentHashMap<String, Entry>()
    private val openUntilMs = ConcurrentHashMap<String, Long>()

    fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: url
    } catch (_: Exception) {
        url
    }

    /** Cầu dao đang mở cho host này? (đang trong thời gian nghỉ) */
    fun isCircuitOpen(host: String): Boolean {
        val until = openUntilMs[host] ?: return false
        if (System.currentTimeMillis() >= until) {
            openUntilMs.remove(host)
            return false
        }
        return true
    }

    fun record(url: String, ok: Boolean, latencyMs: Long, error: String? = null) {
        val host = hostOf(url)
        val prev = lastByHost[host]
        val fails = if (ok) 0 else (prev?.consecutiveFails ?: 0) + 1
        lastByHost[host] = Entry(
            host = host,
            ok = ok,
            latencyMs = latencyMs,
            atMs = System.currentTimeMillis(),
            error = error,
            consecutiveFails = fails
        )
        if (!ok && fails >= FAIL_THRESHOLD) {
            openUntilMs[host] = System.currentTimeMillis() + COOLDOWN_MS
        }
        if (ok) openUntilMs.remove(host)
    }

    /** Trạng thái gần nhất của từng host đã từng gọi, xếp theo tên host. */
    fun snapshot(): List<Entry> = lastByHost.values.sortedBy { it.host }

    fun lastFor(host: String): Entry? = lastByHost[host]
}

/**
 * v7.9 — Kiểm tra sống/chết chủ động từng nguồn dữ liệu chính.
 * Dùng cho màn Cài đặt -> "Trạng thái nguồn dữ liệu". Mỗi nguồn đo bằng
 * request thật qua [Http] (JSON nhỏ dùng timeout chuẩn, playlist/EPG dùng nhóm dài).
 */
object ApiHealthChecker {

    data class Source(val name: String, val url: String, val longTimeout: Boolean = false)

    data class Result(
        val name: String,
        val host: String,
        val ok: Boolean,
        val latencyMs: Long,
        val detail: String
    )

    val SOURCES: List<Source> = listOf(
        Source("Socolive (trận đấu & phòng live)", "https://json.vnres.co/match_recommend.json?callback=recommend"),
        Source("KKPhim (phim, nguồn duy nhất)", "https://phimapi.com/v1/api/danh-sach/phim-moi-cap-nhat?page=1&limit=1"),
        Source("Gà Vàng 33 (thể thao)", "https://gavangtv-api.adviceme.io/api/v1/matches?webType=gavang33"),
        Source("Giờ Vàng TV (thể thao)", "https://live-api.keonhacaitp.one/storage/livestream/live.json"),
        Source("ColaTV (thể thao)", "https://api.colaapis6.com/?t=1"),
        Source("IPTV-org (danh sách kênh)", "https://iptv-org.github.io/iptv/countries/vn.m3u", longTimeout = true),
        Source("EPG (lịch phát sóng)", "https://epgshare01.online/epgshare01/epg_ripper_VN1.xml.gz", longTimeout = true),
        Source("Máy chủ cập nhật APK", "https://kenhlive-dl.htuananh153.workers.dev/version")
    )

    suspend fun checkAll(): List<Result> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        SOURCES.map { src ->
            val started = System.currentTimeMillis()
            try {
                if (src.longTimeout) Http.getLongOnce(src.url) else Http.getOnce(src.url)
                Result(src.name, ApiHealth.hostOf(src.url), true, System.currentTimeMillis() - started, "OK")
            } catch (e: Exception) {
                Result(
                    src.name, ApiHealth.hostOf(src.url), false,
                    System.currentTimeMillis() - started, e.message ?: e.javaClass.simpleName
                )
            }
        }
    }
}
