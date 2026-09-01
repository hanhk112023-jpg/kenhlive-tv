package com.kenhlive.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Phòng live đang phát (row "Đang live" + hero banner). */
data class LiveRoom(
    val roomNum: String,
    val blvName: String,
    val avatar: String,
    val viewers: Int,
    val matchTitle: String,   // "A vs B"
    val league: String,        // "CHA FACup"
    val cover: String = ""     // ảnh nền phòng (hero banner)
)

/** 1 BLV phát 1 trận. */
data class AnchorInfo(val nickName: String, val icon: String, val roomNum: String)

/** Trận đấu trong lịch (tab Lịch trình). */
data class ScheduleMatch(
    val scheduleId: String,
    val host: String,
    val guest: String,
    val league: String,
    val category: String,
    val matchTimeMs: Long,
    val hostIcon: String,
    val guestIcon: String,
    val anchors: List<AnchorInfo>
) {
    val isLive: Boolean get() = matchTimeMs > 0 && System.currentTimeMillis() >= matchTimeMs
    val hasRoom: Boolean get() = anchors.any { it.roomNum.isNotBlank() }
}

data class DaySchedule(val date: Date, val matches: List<ScheduleMatch>)

/** Gọi thẳng API Socolive (json.vnres.co). */
object SocoliveRepository {
    private const val API = "https://json.vnres.co"
    private const val UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    private val TZ = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")

    private fun stamp(): String = (System.currentTimeMillis() / 1000).toString()

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Referer", "https://vnres.co/")
        conn.setRequestProperty("Accept", "application/json, text/plain, */*")
        if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().readText()
    }

    fun stripJsonp(raw: String): String {
        val s = raw.trim()
        val open = s.indexOf('(')
        val close = s.lastIndexOf(')')
        return if (open > 0 && close > open) s.substring(open + 1, close) else s
    }

    // ---------- TAB TRỰC TIẾP ----------
    suspend fun fetchLiveRooms(): List<LiveRoom> = withContext(Dispatchers.IO) {
        val now = stamp()
        val body = get("$API/all_live_rooms.json?callback=rooms&v=$now&_=$now")
        val data = JSONObject(stripJsonp(body)).optJSONObject("data") ?: JSONObject()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<LiveRoom>()
        for (key in data.keys()) {
            val arr = data.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val num = r.optString("roomNum", "")
                if (num.isBlank() || r.optInt("liveStatus", 0) != 1 || num in seen) continue
                seen.add(num)
                val a = r.optJSONObject("anchor") ?: JSONObject()
                val rawTitle = r.optString("title", "Live").trim()
                val idx = rawTitle.indexOf(':')
                val league = if (idx > 0) rawTitle.substring(0, idx).trim() else "SocoLive"
                val match = if (idx > 0) rawTitle.substring(idx + 1).trim() else rawTitle
                out.add(
                    LiveRoom(
                        roomNum = num,
                        blvName = a.optString("nickName", "BLV"),
                        avatar = a.optString("cutOutIcon", "").ifBlank { a.optString("icon", "") },
                        viewers = r.optInt("viewCount", 0),
                        matchTitle = match,
                        league = league,
                        cover = r.optString("cover", "")
                    )
                )
            }
        }
        out.sortedByDescending { it.viewers }
    }

    // ---------- TAB LỊCH TRÌNH ----------
    suspend fun fetchSchedule(days: Int = 7): List<DaySchedule> = withContext(Dispatchers.IO) {
        val byDay = linkedMapOf<String, MutableList<ScheduleMatch>>()
        val seen = mutableSetOf<String>()

        fun addMatch(m: JSONObject) {
            val host = m.optString("hostName", "").trim()
            val guest = m.optString("guestName", "").trim()
            if (host.isBlank() && guest.isBlank()) return
            val sid = m.optString("scheduleId", "")
            val key = sid.ifBlank { "$host-$guest-${m.optLong("matchTime", 0)}" }
            if (key in seen) return
            seen.add(key)
            val anchors = mutableListOf<AnchorInfo>()
            val arr = m.optJSONArray("anchors")
            if (arr != null) for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val room = a.optJSONObject("anchor")?.optString("roomNum", "") ?: ""
                val icon = a.optString("cutOutIcon", "").ifBlank { a.optString("icon", "") }
                anchors.add(AnchorInfo(a.optString("nickName", "BLV"), icon, room))
            }
            val timeMs = m.optLong("matchTime", 0L)
            val cal = Calendar.getInstance(TZ)
            if (timeMs > 0) {
                cal.time = Date(timeMs)
                val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TZ }.format(cal.time)
                byDay.getOrPut(dayKey) { mutableListOf() }.add(
                    ScheduleMatch(
                        scheduleId = key, host = host, guest = guest,
                        league = m.optString("subCateName", ""),
                        category = m.optString("categoryName", ""),
                        matchTimeMs = timeMs,
                        hostIcon = m.optString("hostIcon", ""),
                        guestIcon = m.optString("guestIcon", ""),
                        anchors = anchors
                    )
                )
            }
        }

        // hôm nay + days ngày tới
        val cal = Calendar.getInstance(TZ)
        val fmtKey = SimpleDateFormat("yyyyMMdd", Locale.US)
        fmtKey.timeZone = TZ
        for (i in 0 until days) {
            val key = fmtKey.format(cal.time)
            try {
                val now = stamp()
                val body = get("$API/match/matches_$key.json?callback=matches&v=$now&_=$now")
                val arr = JSONObject(stripJsonp(body)).optJSONArray("data")
                if (arr != null) for (j in 0 until arr.length()) {
                    arr.optJSONObject(j)?.let { addMatch(it) }
                }
            } catch (_: Exception) { }
            cal.add(Calendar.DATE, 1)
        }

        byDay.map { (k, list) ->
            val date = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TZ }.parse(k)!!
            val sorted = list.sortedWith(
                compareBy({ it.isLive.not() }, { leagueWeight(it.league) }, { it.matchTimeMs })
            )
            DaySchedule(date, sorted)
        }.sortedBy { it.date }
    }

    /** Stream URL từ roomNum: hdM3u8 → m3u8 → hdFlv → flv. */
    suspend fun fetchStream(roomNum: String): String? = withContext(Dispatchers.IO) {
        try {
            val now = stamp()
            val body = get("$API/room/$roomNum/detail.json?callback=detail&v=$now&_=$now")
            val data = JSONObject(stripJsonp(body)).optJSONObject("data") ?: return@withContext null
            val stream = data.optJSONObject("stream") ?: return@withContext null
            listOf("hdM3u8", "m3u8", "hdFlv", "flv").firstNotNullOfOrNull { k ->
                stream.optString(k, "").takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) { null }
    }

    // ---------- helpers ----------
    private val LEAGUE_ORDER = listOf(
        "Ngoại Hạng Anh", "La Liga", "Serie A", "Bundesliga", "Ligue 1",
        "Champions League", "Châu Âu", "World Cup", "AFF", "ASEAN", "V-League"
    )
    fun leagueWeight(league: String): Int {
        LEAGUE_ORDER.forEachIndexed { i, name ->
            if (league.contains(name, ignoreCase = true)) return i
        }
        return LEAGUE_ORDER.size
    }

    fun formatTime(epochMs: Long): String {
        if (epochMs <= 0) return "--:--"
        val f = SimpleDateFormat("HH:mm", Locale.US)
        f.timeZone = TZ
        return f.format(Date(epochMs))
    }

    fun dayLabel(d: Date): String {
        val cal = Calendar.getInstance(TZ)
        cal.time = d
        val today = Calendar.getInstance(TZ)
        val todayKey = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TZ }.format(today.time)
        val thisKey = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TZ }.format(d)
        return when {
            thisKey == todayKey -> "Hôm nay"
            cal.timeInMillis - today.timeInMillis in 1..86400000L -> "Ngày mai"
            else -> SimpleDateFormat("EEEE, dd/MM", Locale("vi")).apply { timeZone = TZ }.format(d)
                .replaceFirstChar { it.uppercase() }
        }
    }

    fun fmtViewers(n: Int): String =
        if (n >= 1000) String.format(Locale.US, "%.1fK", n / 1000.0).replace('.', ',') else n.toString()
}
