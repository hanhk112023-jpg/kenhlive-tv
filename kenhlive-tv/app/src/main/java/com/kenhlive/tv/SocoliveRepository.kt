package com.kenhlive.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
    val league: String,       // "CHA FACup"
    val cover: String = ""    // ảnh nền phòng (hero banner)
)

/** Gộp nhiều phòng cùng 1 trận (cùng giải + tên trận). */
data class LiveMatchGroup(
    val league: String,
    val matchTitle: String,
    val rooms: List<LiveRoom>   // sorted by viewers desc
) {
    val totalViewers: Int get() = rooms.sumOf { it.viewers }
    val top: LiveRoom get() = rooms.first()
    val count: Int get() = rooms.size
}

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
    val anchors: List<AnchorInfo>,
    val leagueCrest: String = ""
) {
    val isLive: Boolean get() = matchTimeMs > 0 && System.currentTimeMillis() >= matchTimeMs
    val hasRoom: Boolean get() = anchors.any { it.roomNum.isNotBlank() }
}

data class DaySchedule(val date: Date, val matches: List<ScheduleMatch>)

/**
 * Nguồn dữ liệu Socolive (json.vnres.co).
 * - Single-flight: nhiều màn hình cùng gọi liveRooms() chỉ sinh 1 request
 * - Cache TTL 60s: chuyển tab/rotation không bắn lại mạng
 * - Lịch 7 ngày fetch song song (BUG-18 giữ nguyên)
 */
object SocoliveRepository {
    private const val API = "https://json.vnres.co"
    private val TZ = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    private const val LIVE_TTL_MS = 60_000L

    private val liveMutex = Mutex()
    @Volatile private var liveCache: List<LiveRoom>? = null
    @Volatile private var liveCacheAt = 0L
    @Volatile private var scheduleCache: List<DaySchedule>? = null
    @Volatile private var scheduleCacheAt = 0L
    private const val SCHEDULE_TTL_MS = 5 * 60_000L

    private fun stamp(): String = (System.currentTimeMillis() / 1000).toString()

    fun invalidateLive() { liveCache = null }

    // ---------- TAB TRỰC TIẾP ----------
    /** force=true bỏ qua cache (pull-to-refresh / retry). */
    suspend fun fetchLiveRooms(force: Boolean = false): List<LiveRoom> {
        if (!force) {
            val c = liveCache
            if (c != null && System.currentTimeMillis() - liveCacheAt < LIVE_TTL_MS) return c
        }
        return liveMutex.withLock {
            // kiểm tra lại trong lock: request song song chỉ đi 1 lần
            val c = liveCache
            if (!force && c != null && System.currentTimeMillis() - liveCacheAt < LIVE_TTL_MS) return@withLock c
            withContext(Dispatchers.IO) {
                val now = stamp()
                val body = Http.getWithRetry("$API/all_live_rooms.json?callback=rooms&v=$now&_=$now")
                SocoliveParser.parseLiveRooms(body)
            }.also {
                liveCache = it
                liveCacheAt = System.currentTimeMillis()
            }
        }
    }

    /** Gom phòng live theo trận — alias giữ compat, logic trong parser. */
    fun groupRooms(rooms: List<LiveRoom>): List<LiveMatchGroup> = SocoliveParser.groupRooms(rooms)

    // ---------- TAB LỊCH TRÌNH ----------
    suspend fun fetchSchedule(days: Int = 7, force: Boolean = false): List<DaySchedule> {
        if (!force) {
            val c = scheduleCache
            if (c != null && System.currentTimeMillis() - scheduleCacheAt < SCHEDULE_TTL_MS) return c
        }
        val result = withContext(Dispatchers.IO) {
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
                    val icon = a.optString("icon", "").ifBlank { a.optString("cutOutIcon", "") }
                    anchors.add(AnchorInfo(a.optString("nickName", "BLV"), icon, room))
                }
                val timeMs = m.optLong("matchTime", 0L)
                if (timeMs > 0) {
                    val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TZ }
                        .format(Date(timeMs))
                    byDay.getOrPut(dayKey) { mutableListOf() }.add(
                        ScheduleMatch(
                            scheduleId = key, host = host, guest = guest,
                            league = m.optString("subCateName", ""),
                            category = m.optString("categoryName", ""),
                            matchTimeMs = timeMs,
                            hostIcon = m.optString("hostIcon", ""),
                            guestIcon = m.optString("guestIcon", ""),
                            anchors = anchors,
                            leagueCrest = m.optString("categoryIcon", "")
                                .ifBlank { m.optString("hostIcon", "") }
                        )
                    )
                }
            }

            val cal = Calendar.getInstance(TZ)
            val fmtKey = SimpleDateFormat("yyyyMMdd", Locale.US)
            fmtKey.timeZone = TZ
            val keys = (0 until days).map { val k = fmtKey.format(cal.time); cal.add(Calendar.DATE, 1); k }
            val bodies = keys.map { k ->
                async {
                    try {
                        val now = stamp()
                        Http.getWithRetry("$API/match/matches_$k.json?callback=matches&v=$now&_=$now")
                    } catch (_: Exception) { null }
                }
            }.awaitAll()
            for (body in bodies) {
                if (body == null) continue
                try { SocoliveParser.parseScheduleDay(body, ::addMatch) } catch (_: Exception) { }
            }

            byDay.map { (k, list) ->
                val date = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TZ }.parse(k)!!
                val sorted = list.sortedWith(
                    compareBy({ it.isLive.not() }, { leagueWeight(it.league) }, { it.matchTimeMs })
                )
                DaySchedule(date, sorted)
            }.sortedBy { it.date }
        }
        scheduleCache = result
        scheduleCacheAt = System.currentTimeMillis()
        return result
    }

    /** Stream URL từ roomNum. */
    suspend fun fetchStream(roomNum: String): String? = withContext(Dispatchers.IO) {
        try {
            val now = stamp()
            val body = Http.getWithRetry("$API/room/$roomNum/detail.json?callback=detail&v=$now&_=$now")
            SocoliveParser.parseStream(body)
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
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TZ }
        return when {
            fmt.format(d) == fmt.format(today.time) -> "Hôm nay"
            cal.timeInMillis - today.timeInMillis in 1..86400000L -> "Ngày mai"
            else -> SimpleDateFormat("EEEE, dd/MM", Locale("vi")).apply { timeZone = TZ }.format(d)
                .replaceFirstChar { it.uppercase() }
        }
    }

    fun fmtViewers(n: Int): String =
        if (n >= 1000) String.format(Locale.US, "%.1fK", n / 1000.0).replace('.', ',') else n.toString()
}
