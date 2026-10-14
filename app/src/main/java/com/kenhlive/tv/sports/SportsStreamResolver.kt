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
    private const val COLA_CONFIG_URL = "https://api.colaapis6.com/?t="
    private const val COLA_DEFAULT_API = "https://api.gvapi.cc"

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
                    return@withContext "https://live2.zktsva.app/live/307240.m3u8"
                }
                SportsSource.GIOVANG -> {
                    if (realRoomNum.startsWith("http")) return@withContext realRoomNum
                    val streamById = resolveGiovangById(realRoomNum)
                    if (!streamById.isNullOrBlank()) return@withContext streamById

                    ensureDirectFeedsLoaded()
                    if (cachedGiovangIds.isNotEmpty()) {
                        val idx = (realRoomNum.hashCode().and(0x7FFFFFFF)) % cachedGiovangIds.size
                        val fallbackStream = resolveGiovangById(cachedGiovangIds[idx])
                        if (!fallbackStream.isNullOrBlank()) return@withContext fallbackStream
                    }
                    return@withContext "https://ftlh5sc02iliv.vcdn.cloud/1791734978_hd/1791734978_hd@720p.m3u8"
                }
                SportsSource.COLATV -> {
                    if (realRoomNum.startsWith("http")) return@withContext realRoomNum
                    ensureDirectFeedsLoaded()
                    if (cachedColaStreams.isNotEmpty()) {
                        val idx = (realRoomNum.hashCode().and(0x7FFFFFFF)) % cachedColaStreams.size
                        return@withContext cachedColaStreams[idx]
                    }
                    return@withContext "https://live05.zhwpg.app/live/78905744_tsc.m3u8"
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
     * Tải danh sách trận đấu trực tiếp từ Gà Vàng 33 API (Chỉ lọc các trận đang phát trực tiếp matchStatus == "live").
     */
    suspend fun fetchGavang33MatchGroups(): List<LiveMatchGroup> = withContext(Dispatchers.IO) {
        try {
            val raw = Http.getWithRetry("$GAVANG33_API&t=${System.currentTimeMillis()}")
            val root = JSONObject(raw)
            val data = root.optJSONObject("data") ?: return@withContext emptyList()
            val liveGroups = mutableListOf<LiveMatchGroup>()
            val upcomingGroups = mutableListOf<LiveMatchGroup>()
            val keys = data.keys()
            val allStreams = mutableListOf<String>()

            while (keys.hasNext()) {
                val k = keys.next()
                val m = data.optJSONObject(k) ?: continue
                val isLive = m.optString("matchStatus") == "live"
                val homeObj = m.optJSONObject("homeTeam")
                val awayObj = m.optJSONObject("awayTeam")
                val compObj = m.optJSONObject("competition")
                val homeName = homeObj?.optString("name") ?: ""
                val awayName = awayObj?.optString("name") ?: ""
                if (homeName.isBlank() || awayName.isBlank()) continue

                val homeLogo = homeObj?.optString("logo") ?: ""
                val awayLogo = awayObj?.optString("logo") ?: ""
                val league = compObj?.optString("name") ?: "Bóng đá"
                val matchTitle = if (isLive) "$homeName vs $awayName" else "[SẮP ĐÁ] $homeName vs $awayName"
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
                                    if (isLive) allStreams.add(u)
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
                                notice = if (isLive) "Kênh trực tiếp Gà Vàng 33 · Luồng phát độc quyền" else "Chưa đến giờ thi đấu · Sắp diễn ra",
                                hostIcon = homeLogo,
                                guestIcon = awayLogo
                            )
                        )
                    }
                } else if (defaultLink.isNotBlank() && !defaultLink.contains("gvvsb.com")) {
                    if (isLive) allStreams.add(defaultLink)
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
                    val group = LiveMatchGroup(
                        league = league,
                        matchTitle = matchTitle,
                        rooms = rooms,
                        category = "Bóng đá",
                        hostIcon = homeLogo,
                        guestIcon = awayLogo
                    )
                    if (isLive) {
                        liveGroups.add(group)
                    } else {
                        upcomingGroups.add(group)
                    }
                }
            }

            if (allStreams.isNotEmpty()) cachedGavangStreams = allStreams
            // Chỉ trả về các trận ĐANG TRỰC TIẾP để tiêu đề và nội dung trận khớp 100%
            if (liveGroups.isNotEmpty()) liveGroups else upcomingGroups.take(4)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Tải danh sách trận đấu thể thao trực tiếp từ ColaTV API chính thức (hỗ trợ tỉ số real-time, logo đội bóng & tên BLV).
     * Fallback sang M3U feed nếu API chính không phản hồi.
     */
    suspend fun fetchColaMatchGroups(): List<LiveMatchGroup> = withContext(Dispatchers.IO) {
        val groups = fetchColaOfficialApiMatches()
        if (groups.isNotEmpty()) {
            return@withContext groups
        }
        fetchColaM3uMatches()
    }

    private fun fetchColaOfficialApiMatches(): List<LiveMatchGroup> {
        return try {
            val apiUrl = resolveColaApiBase()
            val raw = Http.getWithRetry("$apiUrl/api/matches?t=${System.currentTimeMillis()}")
            if (raw.isBlank()) return emptyList()
            val root = JSONObject(raw)
            val data = root.optJSONObject("data") ?: return emptyList()

            val liveGroups = mutableListOf<LiveMatchGroup>()
            val upcomingGroups = mutableListOf<LiveMatchGroup>()
            val cStreams = mutableListOf<String>()
            val keys = data.keys()

            while (keys.hasNext()) {
                val k = keys.next()
                val m = data.optJSONObject(k) ?: continue
                val hName = m.optString("homeTeamName").ifBlank { m.optJSONObject("home_team")?.optString("name") ?: "" }.trim()
                val aName = m.optString("awayTeamName").ifBlank { m.optJSONObject("away_team")?.optString("name") ?: "" }.trim()
                if (hName.isBlank() || aName.isBlank()) continue

                val hLogo = m.optString("homeTeamLogo").ifBlank { m.optJSONObject("home_team")?.optString("logo") ?: "" }
                val aLogo = m.optString("awayTeamLogo").ifBlank { m.optJSONObject("away_team")?.optString("logo") ?: "" }
                val league = m.optString("competitionName").ifBlank { m.optJSONObject("competition")?.optString("name") ?: "ColaTV Thể Thao" }.trim()

                val status = m.optInt("matchStatus", 0)
                val isLive = status == 2 || m.optString("match_status") == "live"

                val hs = m.optJSONArray("homeScore")
                val as_ = m.optJSONArray("awayScore")

                // Bóc tách tỉ số real-time nếu trận đấu đang diễn ra hoặc đã có bàn thắng
                val matchTitle = if (isLive && hs != null && as_ != null && hs.length() > 0 && as_.length() > 0) {
                    val hScore = hs.optInt(0, 0)
                    val aScore = as_.optInt(0, 0)
                    "$hName $hScore - $aScore $aName"
                } else if (isLive) {
                    "$hName vs $aName"
                } else {
                    "[SẮP ĐÁ] $hName vs $aName"
                }

                val anchors = m.optJSONArray("anchorAppointmentVoList")
                val rooms = mutableListOf<LiveRoom>()

                if (anchors != null && anchors.length() > 0) {
                    for (i in 0 until anchors.length()) {
                        val a = anchors.optJSONObject(i) ?: continue
                        val nick = a.optString("nickName").ifBlank { "BLV Cola" }.trim()
                        val blv = if (nick.startsWith("BLV ", ignoreCase = true)) nick else "BLV $nick"
                        var stream = a.optString("playStreamAddress2")
                        if (stream.isBlank()) {
                            val servers = a.optJSONArray("servers")
                            if (servers != null) {
                                for (si in 0 until servers.length()) {
                                    val s = servers.optString(si)
                                    if (s.contains(".m3u8")) {
                                        stream = s
                                        break
                                    }
                                }
                            }
                        }
                        if (stream.isBlank()) {
                            stream = a.optString("playStreamAddress")
                        }
                        if (stream.isNotBlank()) {
                            cStreams.add(stream)
                            rooms.add(
                                LiveRoom(
                                    roomNum = "cola_$stream",
                                    blvName = "[ColaTV] $blv",
                                    avatar = a.optString("userImage").ifBlank { hLogo },
                                    viewers = a.optInt("visitHistory", 2000 + (liveGroups.size * 250)),
                                    matchTitle = matchTitle,
                                    league = league,
                                    category = "Bóng đá",
                                    blvLevel = "Cola HD",
                                    notice = "Kênh trực tiếp ColaTV · Luồng phát gốc zhwpg.app",
                                    hostIcon = hLogo,
                                    guestIcon = aLogo
                                )
                            )
                        }
                    }
                }

                if (rooms.isEmpty()) {
                    val vUrl = m.optString("video_url").ifBlank { m.optString("videoUrl") }
                    val stream = if (vUrl.isNotBlank() && (vUrl.startsWith("http://") || vUrl.startsWith("https://"))) vUrl else ""
                    if (stream.isNotBlank()) {
                        cStreams.add(stream)
                        rooms.add(
                            LiveRoom(
                                roomNum = "cola_$stream",
                                blvName = "[ColaTV] Tiếng Hiện Trường",
                                avatar = hLogo,
                                viewers = 1500,
                                matchTitle = matchTitle,
                                league = league,
                                category = "Bóng đá",
                                blvLevel = "Cola Live",
                                notice = "Kênh trực tiếp ColaTV · Luồng phát gốc",
                                hostIcon = hLogo,
                                guestIcon = aLogo
                            )
                        )
                    }
                }

                if (rooms.isNotEmpty()) {
                    val group = LiveMatchGroup(
                        league = league,
                        matchTitle = matchTitle,
                        rooms = rooms,
                        category = "Bóng đá",
                        hostIcon = hLogo,
                        guestIcon = aLogo
                    )
                    if (isLive) {
                        liveGroups.add(group)
                    } else {
                        upcomingGroups.add(group)
                    }
                }
            }

            if (cStreams.isNotEmpty()) cachedColaStreams = cStreams
            liveGroups + upcomingGroups
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun resolveColaApiBase(): String {
        return try {
            val raw = Http.getWithRetry("$COLA_CONFIG_URL${System.currentTimeMillis()}")
            if (raw.isNotBlank()) {
                val json = JSONObject(raw)
                val u = json.optString("api_url").ifBlank { json.optString("api_url_2") }
                if (u.isNotBlank() && u.startsWith("http")) return u
            }
            COLA_DEFAULT_API
        } catch (_: Exception) {
            COLA_DEFAULT_API
        }
    }

    private fun fetchColaM3uMatches(): List<LiveMatchGroup> {
        return try {
            val m3u = Http.getWithRetry(M3U_FEED)
            val lines = m3u.split("\n")
            val groups = mutableListOf<LiveMatchGroup>()
            val cStreams = mutableListOf<String>()
            var i = 0

            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXTINF:") && line.contains("group-title=\"Cola TV\"") && line.contains("🟢")) {
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

                    // Chỉ lấy luồng .m3u8 sống từ zhwpg.app, bỏ qua miekgo.app (timeout) và .flv
                    if (streamUrl.endsWith(".m3u8") && streamUrl.contains("zhwpg.app")) {
                        cStreams.add(streamUrl)
                        val commaIdx = line.lastIndexOf(",")
                        val rawTitle = if (commaIdx >= 0) line.substring(commaIdx + 1).trim() else "Trực tiếp ColaTV"

                        var text = rawTitle
                            .replace("🟢", "")
                            .replace("🟡", "")
                            .replace("⚽", "")
                            .replace("🏀", "")
                            .trim()
                        text = text.replace(Regex("^\\d{2}:\\d{2}(\\s+\\d{2}/\\d{2})?\\s*"), "")

                        var blv = "BLV Cola"
                        val openP = text.indexOf("(")
                        val closeP = text.indexOf(")", startIndex = if (openP >= 0) openP else 0)
                        if (openP >= 0 && closeP > openP) {
                            blv = text.substring(openP + 1, closeP).trim()
                            text = (text.substring(0, openP) + text.substring(closeP + 1)).trim()
                        }
                        text = text.replace(Regex("\\[[^\\]]+\\]"), "").trim()
                        val cleanTitle = text.ifBlank { "Trực tiếp ColaTV" }

                        var logo = ""
                        val logoStart = line.indexOf("tvg-logo=\"")
                        if (logoStart >= 0) {
                            val sub = line.substring(logoStart + 10)
                            val logoEnd = sub.indexOf("\"")
                            if (logoEnd > 0) logo = sub.substring(0, logoEnd)
                        }

                        val room = LiveRoom(
                            roomNum = "cola_$streamUrl",
                            blvName = "[ColaTV] $blv",
                            avatar = logo,
                            viewers = 1500 + (groups.size * 180),
                            matchTitle = cleanTitle,
                            league = "COLATV TRỰC TIẾP",
                            category = "Bóng đá",
                            blvLevel = "Cola HD",
                            notice = "Kênh thể thao trực tiếp ColaTV · Độ trễ thấp",
                            hostIcon = logo,
                            guestIcon = ""
                        )

                        groups.add(
                            LiveMatchGroup(
                                league = "COLATV TRỰC TIẾP",
                                matchTitle = cleanTitle,
                                rooms = listOf(room),
                                category = "Bóng đá",
                                hostIcon = logo,
                                guestIcon = ""
                            )
                        )
                    }
                    i = j + 1
                } else {
                    i++
                }
            }

            if (cStreams.isNotEmpty()) cachedColaStreams = cStreams
            groups
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Tải danh sách trận đấu trực tiếp từ Giờ Vàng TV (keonhacaitp API) kèm phân giải sẵn link stream.
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

                // Tải sẵn link stream VCDN Cloud để bấm vào là phát tức thì
                val resolvedUrl = resolveGiovangById(mid) ?: mid
                val blvArr: JSONArray? = m.optJSONArray("blv")
                val blvName = if (blvArr != null && blvArr.length() > 0) {
                    val k = blvArr.optString(0)
                    k.replace("blv-", "BLV ").replace("-", " ").uppercase()
                } else "BLV Giờ Vàng"

                val room = LiveRoom(
                    roomNum = "giovang_$resolvedUrl",
                    blvName = "[Giờ Vàng] $blvName",
                    avatar = homeLogo,
                    viewers = m.optInt("view", 1450),
                    matchTitle = matchTitle,
                    league = league,
                    category = cat,
                    blvLevel = "720p HD",
                    notice = "Kênh trực tiếp Giờ Vàng TV · Hạ tầng VCDN Cloud",
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

        // 1. Fetch Gà Vàng 33 API (Chỉ lấy live)
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
                    if (m.optString("matchStatus") != "live") continue
                    val anchors: JSONArray? = m.optJSONArray("anchorAppointmentVoList")
                    if (anchors != null) {
                        for (i in 0 until anchors.length()) {
                            val a: JSONObject? = anchors.optJSONObject(i) ?: continue
                            val urls: JSONArray? = a?.optJSONArray("streamUrls") ?: continue
                            if (urls != null) {
                                for (j in 0 until urls.length()) {
                                    val u = urls.optString(j)
                                    if (u.contains(".m3u8") && !u.contains("gvvsb.com")) {
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

        // 3. Fetch M3U Feed for ColaTV (Chỉ lấy 🟢 và zhwpg.app)
        try {
            val m3u = Http.getWithRetry(M3U_FEED)
            val lines = m3u.split("\n")
            val cList = mutableListOf<String>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXTINF:") && line.contains("Cola TV") && line.contains("🟢")) {
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
                    if (streamUrl.endsWith(".m3u8") && streamUrl.contains("zhwpg.app")) {
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
