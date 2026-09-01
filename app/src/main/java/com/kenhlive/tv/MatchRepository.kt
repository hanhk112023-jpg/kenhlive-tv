package com.kenhlive.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Match(
    val title: String,
    val league: String,
    val category: String,      // Bóng đá / Bóng rổ
    val matchTime: Long,       // epoch seconds
    val roomNum: String,
    val hostIcon: String,
    val guestIcon: String,
    val hasRoom: Boolean
)

data class DayMatches(val date: Date, val matches: List<Match>)

object MatchRepository {

    private const val API = "https://json.vnres.co"
    private const val UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
    private const val CALLBACK_PREFIX = "match"

    // Thứ tự ưu tiên giải đấu hiển thị trước
    private val LEAGUE_ORDER = listOf(
        "Ngoại Hạng Anh", "La Liga", "Serie A", "Bundesliga", "Ligue 1",
        "Champions League", "C1", "C2", "World Cup", "AFF", "ASEAN",
        "V-League", "Anh", "Tây Ban Nha", "Ý", "Đức", "Pháp", "Châu Á"
    )

    suspend fun fetchWeek(): List<DayMatches> = withContext(Dispatchers.IO) {
        val days = mutableListOf<DayMatches>()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"))
        val fmtKey = SimpleDateFormat("yyyyMMdd", Locale.US)
        fmtKey.timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")

        for (i in 0..6) {
            val key = fmtKey.format(cal.time)
            val list = mutableListOf<Match>()
            try {
                // JSONP: matches?callback=match
                val u = "$API/match/matches_$key.json?callback=$CALLBACK_PREFIX"
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 12000
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Referer", "https://vnres.co/")
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = stripJsonp(body)
                    val arr = JSONObject(json).optJSONArray("data") ?: JSONArray()
                    for (j in 0 until arr.length()) {
                        val m = arr.optJSONObject(j) ?: continue
                        val host = m.optString("hostName", "")
                        val guest = m.optString("guestName", "")
                        if (host.isBlank() && guest.isBlank()) continue
                        val anchors = m.optJSONArray("anchors")
                        var room = ""
                        if (anchors != null) {
                            for (k in 0 until anchors.length()) {
                                val a = anchors.optJSONObject(k)?.optJSONObject("anchor")
                                if (a != null && a.optString("roomNum", "").isNotBlank()) {
                                    room = a.optString("roomNum")
                                    break
                                }
                            }
                        }
                        val league = m.optString("subCateName", "")
                        list.add(
                            Match(
                                title = "$host vs $guest",
                                league = league,
                                category = m.optString("categoryName", "Thể thao"),
                                matchTime = m.optLong("matchTime", 0L),
                                roomNum = room,
                                hostIcon = m.optString("hostIcon", ""),
                                guestIcon = m.optString("guestIcon", ""),
                                hasRoom = room.isNotBlank()
                            )
                        )
                    }
                }
            } catch (_: Exception) { /* ngày không có dữ liệu -> bỏ qua */ }
            days.add(DayMatches(date = cal.time.clone() as Date, matches = list))
            cal.add(Calendar.DATE, 1)
        }
        days
    }

    fun stripJsonp(raw: String): String {
        val s = raw.trim()
        val open = s.indexOf('(')
        val close = s.lastIndexOf(')')
        return if (open > 0 && close > open) s.substring(open + 1, close) else s
    }

    /** Lấy stream URL phát được từ roomNum (hdM3u8 → m3u8 → hdFlv → flv). */
    suspend fun fetchStream(roomNum: String): String? = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis() / 1000
            val u = "$API/room/$roomNum/detail.json?callback=detail&v=$now&_=$now"
            val conn = URL(u).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", "https://vnres.co/")
            if (conn.responseCode != 200) return@withContext null
            val body = conn.inputStream.bufferedReader().readText()
            val data = JSONObject(stripJsonp(body)).optJSONObject("data") ?: return@withContext null
            val stream = data.optJSONObject("stream") ?: return@withContext null
            listOf("hdM3u8", "m3u8", "hdFlv", "flv").firstNotNullOfOrNull { k ->
                stream.optString(k, "").takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) { null }
    }

    fun leagueWeight(league: String): Int {
        LEAGUE_ORDER.forEachIndexed { i, name ->
            if (league.contains(name, ignoreCase = true)) return i
        }
        return LEAGUE_ORDER.size
    }

    fun formatTime(epochSec: Long): String {
        if (epochSec <= 0) return ""
        val f = SimpleDateFormat("HH:mm", Locale.US)
        f.timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        return f.format(Date(epochSec * 1000))
    }

    fun dayLabel(d: Date): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"))
        cal.time = d
        val today = Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"))
        val diff = (cal.timeInMillis - today.timeInMillis) / 86400000L
        return when {
            diff == 0L -> "Hôm nay"
            diff == 1L -> "Ngày mai"
            else -> SimpleDateFormat("EEEE, dd/MM", Locale("vi")).apply {
                timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
            }.format(d)
        }
    }
}
