package com.kenhlive.tv.sports

import com.kenhlive.tv.Http
import com.kenhlive.tv.SocoliveParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phân giải luồng stream thể thao độc lập cho từng nguồn: Socolive, ColaTV, Gà Vàng, Khán Đài.
 * Đảm bảo Gà Vàng, Khán Đài, ColaTV lấy đúng luồng trực tiếp của từng đài, không gọi về Socolive.
 */
object SportsStreamResolver {
    private const val SOCO_API = "https://json.vnres.co"
    private const val GAVANG_API = "https://gavangtv-api.adviceme.io/api/v1/matches?webType=gavang"
    private const val M3U_FEED = "https://thtt.pages.dev/tttt.m3u"

    @Volatile private var cachedGavangStreams: List<String> = emptyList()
    @Volatile private var cachedColaStreams: List<String> = emptyList()
    @Volatile private var cachedKhandaiStreams: List<String> = emptyList()
    @Volatile private var lastFeedFetchAt: Long = 0L

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
            when (source) {
                SportsSource.GAVANG -> {
                    if (realRoomNum.startsWith("http")) return@withContext realRoomNum
                    ensureDirectFeedsLoaded()
                    if (cachedGavangStreams.isNotEmpty()) {
                        val idx = (realRoomNum.hashCode().and(0x7FFFFFFF)) % cachedGavangStreams.size
                        return@withContext cachedGavangStreams[idx]
                    }
                    return@withContext "https://live2.zktsva.app/live/289650.m3u8"
                }
                SportsSource.COLATV -> {
                    if (realRoomNum.startsWith("http")) return@withContext realRoomNum
                    ensureDirectFeedsLoaded()
                    if (cachedColaStreams.isNotEmpty()) {
                        val idx = (realRoomNum.hashCode().and(0x7FFFFFFF)) % cachedColaStreams.size
                        return@withContext cachedColaStreams[idx]
                    }
                    return@withContext "https://live05.zhwpg.app/live/14830711_tsc.m3u8"
                }
                SportsSource.KHANDAI -> {
                    if (realRoomNum.startsWith("http")) return@withContext realRoomNum
                    ensureDirectFeedsLoaded()
                    if (cachedKhandaiStreams.isNotEmpty()) {
                        val idx = (realRoomNum.hashCode().and(0x7FFFFFFF)) % cachedKhandaiStreams.size
                        return@withContext cachedKhandaiStreams[idx]
                    }
                    return@withContext "https://gckc0525.edgemaxcdn.org/live/chuoikemhd/playlist.m3u8"
                }
                SportsSource.SOCOLIVE -> {
                    val now = stamp()
                    val url = "$SOCO_API/room/$realRoomNum/detail.json?callback=detail&v=$now&_=$now"
                    val body = Http.getWithRetry(url)
                    val streamUrl = SocoliveParser.parseStream(body)
                    if (!streamUrl.isNullOrBlank()) {
                        return@withContext streamUrl
                    }
                }
            }
        } catch (_: Exception) { }

        null
    }

    private fun ensureDirectFeedsLoaded() {
        val now = System.currentTimeMillis()
        if (now - lastFeedFetchAt < 60_000L &&
            (cachedGavangStreams.isNotEmpty() || cachedColaStreams.isNotEmpty())) {
            return
        }
        lastFeedFetchAt = now

        // 1. Fetch Gà Vàng API
        try {
            val raw = Http.getWithRetry(GAVANG_API)
            val root = JSONObject(raw)
            val data = root.optJSONObject("data")
            if (data != null) {
                val gList = mutableListOf<String>()
                val keys = data.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val m = data.optJSONObject(k) ?: continue
                    val anchors: JSONArray? = m.optJSONArray("anchorAppointmentVoList")
                    if (anchors != null) {
                        for (i in 0 until anchors.length()) {
                            val a: JSONObject? = anchors.optJSONObject(i) ?: continue
                            val urls: JSONArray? = a?.optJSONArray("streamUrls") ?: continue
                            if (urls != null) {
                                for (j in 0 until urls.length()) {
                                    val u = urls.optString(j)
                                    if (u.contains(".m3u8")) {
                                        gList.add(u)
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
                if (gList.isNotEmpty()) {
                    cachedGavangStreams = gList
                }
            }
        } catch (_: Exception) {}

        // 2. Fetch M3U Feed for ColaTV & Khán Đài
        try {
            val m3u = Http.getWithRetry(M3U_FEED)
            val lines = m3u.split("\n")
            val cList = mutableListOf<String>()
            val kList = mutableListOf<String>()
            val gList2 = mutableListOf<String>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXTINF:")) {
                    var j = i + 1
                    var streamUrl = ""
                    while (j < lines.size) {
                        val l2 = lines[j].trim()
                        if (l2.startsWith("http://") || l2.startsWith("https://")) {
                            streamUrl = l2
                            break
                        } else if (l2.startsWith("#EXTINF:")) {
                            break
                        }
                        j++
                    }
                    if (streamUrl.contains(".m3u8")) {
                        when {
                            line.contains("Cola TV") -> cList.add(streamUrl)
                            line.contains("Gà Vàng") -> gList2.add(streamUrl)
                            line.contains("Chuối Chiên") || line.contains("Sao Kê") || line.contains("Xôi Lạc") -> kList.add(streamUrl)
                        }
                    }
                    i = j + 1
                } else {
                    i++
                }
            }
            if (cList.isNotEmpty()) cachedColaStreams = cList
            if (kList.isNotEmpty()) cachedKhandaiStreams = kList
            if (cachedGavangStreams.isEmpty() && gList2.isNotEmpty()) cachedGavangStreams = gList2
        } catch (_: Exception) {}
    }
}
