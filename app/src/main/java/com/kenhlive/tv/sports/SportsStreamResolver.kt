package com.kenhlive.tv.sports

import com.kenhlive.tv.Http
import com.kenhlive.tv.LiveMatchGroup
import com.kenhlive.tv.LiveRoom
import com.kenhlive.tv.SocoliveParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phân giải luồng stream và tải danh sách trận đấu thể thao độc lập cho từng nguồn:
 * Socolive, ColaTV, Gà Vàng 33 (gavang33), Giờ Vàng TV (giovang).
 */
object SportsStreamResolver {
    private const val SOCO_API = "https://json.vnres.co"
    private const val GAVANG33_API = "https://gavangtv-api.adviceme.io/api/v1/matches?webType=gavang33"
    private const val GIOVANG_LIVE_JSON = "https://live-api.keonhacaitp.one/storage/livestream/live.json"
    private const val GIOVANG_FIXTURE_API = "https://live-api.keonhacaitp.one/api/fixtures/"
    private const val M3U_FEED = "https://thtt.pages.dev/tttt.m3u"

    @Volatile private var cachedGavangStreams: List<String> = emptyList()
    @Volatile private var cachedColaStreams: List<String> = emptyList()
    @Volatile private var cachedGiovangIds: List<String> = emptyList()
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
                SportsSource.GAVANG33 -> {
                    if (realRoomNum.startsWith("http")) return@withContext realRoomNum
                    ensureDirectFeedsLoaded()
                    if (cachedGavangStreams.isNotEmpty()) {
                        val idx = (realRoomNum.hashCode().and(0x7FFFFFFF)) % cachedGavangStreams.size
                        return@withContext cachedGavangStreams[idx]
                    }
                    return@withContext "https://live2.zktsva.app/live/741294.m3u8"
                }
                SportsSource.GIOVANG -> {
                    if (realRoomNum.startsWith("http")) return@withContext realRoomNum
                    // 1. Thử truy vấn trực tiếp theo fixture ID từ Giờ Vàng API
                    val streamById = resolveGiovangById(realRoomNum)
                    if (!streamById.isNullOrBlank()) return@withContext streamById

                    // 2. Nếu realRoomNum là ID của đài khác chuyển sang (backup server), lấy 1 fixture đang live của Giờ Vàng
                    ensureDirectFeedsLoaded()
                    if (cachedGiovangIds.isNotEmpty()) {
                        val idx = (realRoomNum.hashCode().and(0x7FFFFFFF)) % cachedGiovangIds.size
                        val fallbackStream = resolveGiovangById(cachedGiovangIds[idx])
                        if (!fallbackStream.isNullOrBlank()) return@withContext fallbackStream
                    }
                    return@withContext "https://ftlh5sc02iliv.vcdn.cloud/1791734902_hd/1791734902_hd@720p.m3u8"
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

    private fun resolveGiovangById(fixtureId: String): String? {
        if (fixtureId.isBlank()) return null
        return try {
            val raw = Http.getWithRetry("$GIOVANG_FIXTURE_API$fixtureId?t=${System.currentTimeMillis()}")
            val root = JSONObject(raw)
            val resp = root.optJSONObject("response") ?: return null
            val blvs = resp.optJSONArray("blv") ?: return null
            for (i in 0 until blvs.length()) {
                val b = blvs.optJSONObject(i) ?: continue
                val pc = b.optString("pc_stream_url")
                if (pc.isNotBlank() && pc.startsWith("http")) return pc
                val mob = b.optString("mobile_stream_url")
                if (mob.isNotBlank() && mob.startsWith("http")) return mob
                val hd = b.optString("link_stream_hd")
                if (hd.isNotBlank() && hd.startsWith("http")) return hd
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Tải danh sách trận đấu trực tiếp từ Gà Vàng 33 API.
     */
    suspend fun fetchGavang33MatchGroups(): List<LiveMatchGroup> = withContext(Dispatchers.IO) {
        try {
            val raw = Http.getWithRetry("$GAVANG33_API&t=${System.currentTimeMillis()}")
            val root = JSONObject(raw)
            val data = root.optJSONObject("data") ?: return@withContext emptyList()
            val groups = mutableListOf<LiveMatchGroup>()
            val keys = data.keys()
            val allStreams = mutableListOf<String>()

            while (keys.hasNext()) {
                val k = keys.next()
                val m = data.optJSONObject(k) ?: continue
                val homeObj = m.optJSONObject("homeTeam")
                val awayObj = m.optJSONObject("awayTeam")
                val compObj = m.optJSONObject("competition")
                val homeName = homeObj?.optString("name") ?: ""
                val awayName = awayObj?.optString("name") ?: ""
                if (homeName.isBlank() || awayName.isBlank()) continue

                val homeLogo = homeObj?.optString("logo") ?: ""
                val awayLogo = awayObj?.optString("logo") ?: ""
                val league = compObj?.optString("name") ?: "Bóng đá"
                val matchTitle = "$homeName vs $awayName"
                val defaultLink = m.optString("defaultLink")
                val anchors: JSONArray? = m.optJSONArray("anchorAppointmentVoList")

                val rooms = mutableListOf<LiveRoom>()
                if (anchors != null && anchors.length() > 0) {
                    for (i in 0 until anchors.length()) {
                        val a = anchors.optJSONObject(i) ?: continue
                        val nick = a.optString("nickName").ifBlank { "BLV Gà Vàng 33" }
                        val avatar = a.optString("userImage")
                        var stream = ""
                        val urls: JSONArray? = a.optJSONArray("streamUrls")
                        if (urls != null) {
                            for (j in 0 until urls.length()) {
                                val u = urls.optString(j)
                                if (u.contains(".m3u8") && !u.contains("gvvsb.com")) {
                                    stream = u
                                    allStreams.add(u)
                                    break
                                }
                            }
                        }
                        if (stream.isBlank()) continue

                        rooms.add(
                            LiveRoom(
                                roomNum = "gavang33_$stream",
                                blvName = "[Gà Vàng 33] $nick",
                                avatar = avatar,
                                viewers = 1200 + (Math.abs(k.hashCode()) % 800),
                                matchTitle = matchTitle,
                                league = league,
                                category = "Bóng đá",
                                blvLevel = "Gà Vàng HD",
                                notice = "Kênh trực tiếp Gà Vàng 33 · Luồng phát độc quyền",
                                hostIcon = homeLogo,
                                guestIcon = awayLogo
                            )
                        )
                    }
                } else if (defaultLink.isNotBlank() && !defaultLink.contains("gvvsb.com")) {
                    allStreams.add(defaultLink)
                    rooms.add(
                        LiveRoom(
                            roomNum = "gavang33_$defaultLink",
                            blvName = "[Gà Vàng 33] Kênh Trực Tiếp",
                            avatar = "",
                            viewers = 950,
                            matchTitle = matchTitle,
                            league = league,
                            category = "Bóng đá",
                            blvLevel = "Full HD",
                            notice = "Kênh trực tiếp Gà Vàng 33 · Không bình luận",
                            hostIcon = homeLogo,
                            guestIcon = awayLogo
                        )
                    )
                }

                if (rooms.isNotEmpty()) {
                    val isLive = m.optString("matchStatus") == "live"
                    val group = LiveMatchGroup(
                        league = league,
                        matchTitle = matchTitle,
                        rooms = rooms,
                        category = "Bóng đá",
                        hostIcon = homeLogo,
                        guestIcon = awayLogo
                    )
                    if (isLive) {
                        groups.add(0, group)
                    } else {
                        groups.add(group)
                    }
                }
            }

            if (allStreams.isNotEmpty()) cachedGavangStreams = allStreams
            groups.take(30)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Tải danh sách trận đấu trực tiếp từ Giờ Vàng TV (keonhacaitp API).
     */
    suspend fun fetchGiovangMatchGroups(): List<LiveMatchGroup> = withContext(Dispatchers.IO) {
        try {
            val raw = Http.getWithRetry("$GIOVANG_LIVE_JSON?t=${System.currentTimeMillis()}")
            val root = JSONObject(raw)
            val resp: JSONArray? = root.optJSONArray("response")
            if (resp == null) return@withContext emptyList()
            val groups = mutableListOf<LiveMatchGroup>()
            val ids = mutableListOf<String>()

            for (i in 0 until resp.length()) {
                val m = resp.optJSONObject(i) ?: continue
                val mid = m.optString("id").ifBlank { m.optString("fi") }
                if (mid.isBlank()) continue
                ids.add(mid)

                val teams = m.optJSONObject("teams")
                val homeObj = teams?.optJSONObject("home")
                val awayObj = teams?.optJSONObject("away")
                val homeName = homeObj?.optString("name")?.trim() ?: ""
                val awayName = awayObj?.optString("name")?.trim() ?: ""
                if (homeName.isBlank() || awayName.isBlank()) continue

                val homeLogo = homeObj?.optString("logo") ?: ""
                val awayLogo = awayObj?.optString("logo") ?: ""
                val league = m.optJSONObject("league")?.optString("title") ?: "Thể thao"
                val matchTitle = "$homeName vs $awayName"
                val cat = when (m.optString("type")) {
                    "football" -> "Bóng đá"
                    "basketball" -> "Bóng rổ"
                    "bongchuyen" -> "Bóng chuyền"
                    "tennis" -> "Tennis"
                    "esport" -> "Esports"
                    else -> "Thể thao"
                }

                val blvArr: JSONArray? = m.optJSONArray("blv")
                val blvName = if (blvArr != null && blvArr.length() > 0) {
                    val k = blvArr.optString(0)
                    k.replace("blv-", "BLV ").replace("-", " ").uppercase()
                } else "BLV Giờ Vàng"

                val room = LiveRoom(
                    roomNum = "giovang_$mid",
                    blvName = "[Giờ Vàng] $blvName HD",
                    avatar = homeLogo,
                    viewers = m.optInt("view", 450).coerceAtLeast(350),
                    matchTitle = matchTitle,
                    league = league,
                    category = cat,
                    blvLevel = "720p HD",
                    notice = "Kênh trực tiếp Giờ Vàng TV · Phát sóng tốc độ cao",
                    hostIcon = homeLogo,
                    guestIcon = awayLogo
                )

                groups.add(
                    LiveMatchGroup(
                        league = league,
                        matchTitle = matchTitle,
                        rooms = listOf(room),
                        category = cat,
                        hostIcon = homeLogo,
                        guestIcon = awayLogo
                    )
                )
            }

            if (ids.isNotEmpty()) cachedGiovangIds = ids
            groups
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun ensureDirectFeedsLoaded() {
        val now = System.currentTimeMillis()
        if (now - lastFeedFetchAt < 60_000L &&
            (cachedGavangStreams.isNotEmpty() || cachedColaStreams.isNotEmpty() || cachedGiovangIds.isNotEmpty())) {
            return
        }
        lastFeedFetchAt = now

        // 1. Fetch Gà Vàng 33 API
        try {
            val raw = Http.getWithRetry("$GAVANG33_API&t=$now")
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
                if (gList.isNotEmpty()) cachedGavangStreams = gList
            }
        } catch (_: Exception) {}

        // 2. Fetch Giờ Vàng Live IDs
        try {
            val raw = Http.getWithRetry("$GIOVANG_LIVE_JSON?t=$now")
            val root = JSONObject(raw)
            val resp: JSONArray? = root.optJSONArray("response")
            if (resp != null) {
                val ids = mutableListOf<String>()
                for (i in 0 until resp.length()) {
                    val m = resp.optJSONObject(i) ?: continue
                    val mid = m.optString("id").ifBlank { m.optString("fi") }
                    if (mid.isNotBlank()) ids.add(mid)
                }
                if (ids.isNotEmpty()) cachedGiovangIds = ids
            }
        } catch (_: Exception) {}

        // 3. Fetch M3U Feed for ColaTV
        try {
            val m3u = Http.getWithRetry(M3U_FEED)
            val lines = m3u.split("\n")
            val cList = mutableListOf<String>()
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
                    if (streamUrl.contains(".m3u8") && line.contains("Cola TV")) {
                        cList.add(streamUrl)
                    }
                    i = j + 1
                } else {
                    i++
                }
            }
            if (cList.isNotEmpty()) cachedColaStreams = cList
        } catch (_: Exception) {}
    }
}
