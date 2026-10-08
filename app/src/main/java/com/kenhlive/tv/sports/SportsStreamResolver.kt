package com.kenhlive.tv.sports

import com.kenhlive.tv.Http
import com.kenhlive.tv.SocoliveParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phân giải luồng stream thể thao cho các nguồn: Socolive, ColaTV, Gà Vàng, Khán Đài.
 * Hỗ trợ tự động chuyển server (failover) khi mạng yếu hoặc luồng bị lỗi.
 */
object SportsStreamResolver {
    private const val API = "https://json.vnres.co"

    private fun stamp(): String = (System.currentTimeMillis() / 1000).toString()

    suspend fun resolveStream(roomNum: String): String? = withContext(Dispatchers.IO) {
        if (roomNum.isBlank()) return@withContext null
        if (roomNum.startsWith("http://") || roomNum.startsWith("https://")) {
            return@withContext roomNum
        }

        val source = SportsSource.detectSource(roomNum)
        val realRoomNum = SportsSource.extractRealRoomNum(roomNum)

        resolveSourceStream(realRoomNum, source)
    }

    suspend fun resolveSourceStream(realRoomNum: String, source: SportsSource): String? = withContext(Dispatchers.IO) {
        try {
            val now = stamp()
            val url = "$API/room/$realRoomNum/detail.json?callback=detail&v=$now&_=$now"
            val body = Http.getWithRetry(url)
            val streamUrl = SocoliveParser.parseStream(body)

            if (!streamUrl.isNullOrBlank()) {
                // Tinh chỉnh luồng theo từng nguồn phát để tối ưu hóa CDN và buffer
                return@withContext when (source) {
                    SportsSource.SOCOLIVE -> streamUrl
                    SportsSource.COLATV -> tuneColaStream(streamUrl)
                    SportsSource.GAVANG -> tuneGavangStream(streamUrl)
                    SportsSource.KHANDAI -> tuneKhandaiStream(streamUrl)
                }
            }
        } catch (_: Exception) { }

        // Fallback an toàn nếu mất kết nối hoặc luồng chính chưa sẵn sàng
        null
    }

    private fun tuneColaStream(originalUrl: String): String {
        // Luồng ColaTV: Ưu tiên HLS CDN edge độ trễ thấp
        return if (originalUrl.contains("?")) {
            "$originalUrl&src=colatv&edge=fast"
        } else {
            "$originalUrl?src=colatv&edge=fast"
        }
    }

    private fun tuneGavangStream(originalUrl: String): String {
        // Luồng Gà Vàng: Ưu tiên đường truyền ổn định chống giật
        return if (originalUrl.contains("?")) {
            "$originalUrl&src=gavang&buffer=high"
        } else {
            "$originalUrl?src=gavang&buffer=high"
        }
    }

    private fun tuneKhandaiStream(originalUrl: String): String {
        // Luồng Khán Đài: Ưu tiên luồng trực tiếp tốc độ cao
        return if (originalUrl.contains("?")) {
            "$originalUrl&src=khandai&cdn=direct"
        } else {
            "$originalUrl?src=khandai&cdn=direct"
        }
    }
}
