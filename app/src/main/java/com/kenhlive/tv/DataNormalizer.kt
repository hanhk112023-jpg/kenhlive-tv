package com.kenhlive.tv

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Bộ chuẩn hóa dữ liệu toàn diện cho KenhLive TV.
 * Đảm bảo mọi thông tin (Thể thao, Truyền hình, Lịch thi đấu, Phim ảnh, EPG)
 * luôn chuẩn xác, sạch sẽ, không rác, đúng múi giờ Việt Nam (GMT+7).
 */
object DataNormalizer {

    val TZ_VN: TimeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")

    private val MARKS = "\\p{Mn}+".toRegex()
    private val NOISE_PATTERNS = listOf(
        Regex("""^(Tường\s*thuật|Bình\s*luận|Trực\s*tiếp)\s*:\s*""", RegexOption.IGNORE_CASE),
        Regex("""\[HOT\]""", RegexOption.IGNORE_CASE),
        Regex("""\[VIP\]""", RegexOption.IGNORE_CASE),
        Regex("""\[FULL\s*HD\]""", RegexOption.IGNORE_CASE),
        Regex("""\[HD\]""", RegexOption.IGNORE_CASE),
        Regex("""\(Link\s*\d+\)""", RegexOption.IGNORE_CASE),
        Regex("""\(Trực\s*tiếp\)""", RegexOption.IGNORE_CASE),
        Regex("""Trực\s*tiếp:?""", RegexOption.IGNORE_CASE),
        Regex("""\|\s*BLV.*$""", RegexOption.IGNORE_CASE),
        Regex("""-\s*BLV.*$""", RegexOption.IGNORE_CASE),
        Regex("""\(Bình\s*luận.*\)""", RegexOption.IGNORE_CASE)
    )

    // Bảng chuẩn hóa giải đấu phổ biến
    private val LEAGUE_MAPPING = listOf(
        listOf("ngoai hang anh", "premier league", "epl", "eng pr") to "Ngoại Hạng Anh",
        listOf("cup c1", "champions league", "ucl") to "Cúp C1 Châu Âu",
        listOf("cup c2", "europa league", "uel") to "Cúp C2 Châu Âu",
        listOf("cup c3", "conference league", "uecl") to "Cúp C3 Châu Âu",
        listOf("la liga", "laliga", "vdqg tay ban nha", "esp d1") to "La Liga",
        listOf("serie a", "vdqg y", "vdqg italia", "ita d1") to "Serie A",
        listOf("bundesliga", "vdqg duc", "ger d1") to "Bundesliga",
        listOf("ligue 1", "vdqg phap", "fra d1") to "Ligue 1",
        listOf("v-league", "vleague", "v league", "vdqg viet nam") to "V-League 1",
        listOf("saudi pro league", "spl", "a rap") to "Saudi Pro League",
        listOf("world cup") to "World Cup",
        listOf("euro") to "Euro",
        listOf("afc champions league", "cup c1 chau a") to "AFC Champions League",
        listOf("mongolia premier league", "mgl d1") to "Giải VĐQG Mông Cổ",
        listOf("nba", "bong ro my") to "Bóng Rổ NBA",
        listOf("tennis", "quan vot") to "Quần Vợt",
        listOf("f1", "cong thuc 1", "formula 1") to "Công Thức 1"
    )

    data class ParsedMatch(
        val league: String,
        val host: String,
        val guest: String,
        val cleanTitle: String,
        val hostScore: Int? = null,
        val guestScore: Int? = null
    )

    /**
     * Bỏ dấu tiếng Việt và chuyển sang chữ thường không khoảng trắng dư thừa
     */
    fun removeDiacritics(s: String): String {
        if (s.isBlank()) return ""
        val n = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(MARKS, "")
            .replace("đ", "d")
        return n.trim()
    }

    /**
     * Chuẩn hóa tên giải đấu theo định chuẩn tiếng Việt
     */
    fun normalizeLeague(rawLeague: String): String {
        val trimmed = rawLeague.trim()
        if (trimmed.isBlank() || trimmed.equals("Live", ignoreCase = true) || trimmed.equals("SocoLive", ignoreCase = true)) {
            return "Trực Tiếp Thể Thao"
        }
        val norm = removeDiacritics(trimmed)
        for ((aliases, standardName) in LEAGUE_MAPPING) {
            if (aliases.any { norm.contains(it) }) {
                return standardName
            }
        }
        return trimmed
    }

    /**
     * Tách và làm sạch tiêu đề trận đấu, phân tích đội nhà - đội khách và tỉ số nếu có
     */
    fun parseMatch(rawTitle: String, fallbackLeague: String = ""): ParsedMatch {
        var text = rawTitle.trim()

        // 1. Lọc bỏ các từ rác
        for (pattern in NOISE_PATTERNS) {
            text = text.replace(pattern, "").trim()
        }

        var detectedLeague = fallbackLeague.ifBlank { "" }

        // 2. Phát hiện giải đấu trong ngoặc vuông [Ngoại Hạng Anh] hoặc trước dấu hai chấm
        val bracketMatch = Regex("""^\[(.*?)\]\s*(.*)$""").find(text)
        if (bracketMatch != null) {
            val l = bracketMatch.groupValues[1].trim()
            val isTime = Regex("""^\d{1,2}:\d{2}$""").matches(l)
            val isStatus = l.equals("SẮP ĐÁ", ignoreCase = true) ||
                           l.equals("SẮP DIỄN RA", ignoreCase = true) ||
                           l.equals("CHƯA ĐÁ", ignoreCase = true) ||
                           l.equals("LIVE", ignoreCase = true) ||
                           l.equals("TRỰC TIẾP", ignoreCase = true) ||
                           l.equals("HOT", ignoreCase = true) ||
                           l.equals("VIP", ignoreCase = true)
            if (l.isNotBlank() && !isTime && !isStatus) {
                detectedLeague = l
            }
            text = bracketMatch.groupValues[2].trim()
        } else {
            val timePrefixMatch = Regex("""^\s*(\d{1,2}:\d{2})\s*(.*)$""").find(text)
            if (timePrefixMatch != null) {
                // Tiền tố giờ đá "17:00 Arsenal vs Chelsea" -> bỏ tiền tố giờ khỏi text phân tích đội
                text = timePrefixMatch.groupValues[2].trim()
            } else {
                val colonIdx = text.indexOf(':')
                if (colonIdx in 1..25) {
                    val beforeChar = text.getOrNull(colonIdx - 1)
                    val afterChar = text.getOrNull(colonIdx + 1)
                    val isTimeOrRatioColon = beforeChar?.isDigit() == true && afterChar?.isDigit() == true
                    if (!isTimeOrRatioColon) {
                        val l = text.substring(0, colonIdx).trim()
                        if (l.isNotBlank() && !l.equals("Live", ignoreCase = true) && !Regex("""^\d{1,2}$""").matches(l)) {
                            detectedLeague = l
                            text = text.substring(colonIdx + 1).trim()
                        }
                    }
                }
            }
        }

        // Tách giải đấu nếu có dạng "ENG PR - Brentford vs Nottingham"
        if (text.contains(" - ") && text.contains(" vs ", ignoreCase = true)) {
            val dashIdx = text.indexOf(" - ")
            val vsIdx = text.indexOf(" vs ", ignoreCase = true)
            if (vsIdx > dashIdx) {
                val candidateLeague = text.substring(0, dashIdx).trim()
                if (candidateLeague.isNotBlank() && (detectedLeague.isBlank() || detectedLeague == "Trực Tiếp Thể Thao")) {
                    detectedLeague = candidateLeague
                }
                text = text.substring(dashIdx + 3).trim()
            }
        }

        detectedLeague = normalizeLeague(detectedLeague)

        // 3. Tìm tỉ số (ví dụ: Arsenal 2 - 1 Chelsea hoặc Real Madrid 0:0 Barca)
        var hostScore: Int? = null
        var guestScore: Int? = null
        val scoreRegex = Regex("""\b(\d{1,2})\s*[-:]\s*(\d{1,2})\b""")
        val scoreMatches = scoreRegex.findAll(text)
        for (m in scoreMatches) {
            val fullMatch = m.value.trim()
            val s1 = m.groupValues[1].toIntOrNull()
            val s2 = m.groupValues[2].toIntOrNull()
            if (s1 != null && s2 != null) {
                // Loại trừ nếu trùng khớp định dạng giờ thi đấu (ví dụ: 17:00, 19:30, 20:45)
                val isClockTime = fullMatch.contains(":") && s1 in 0..23 && s2 in 0..59 && m.groupValues[2].length == 2 && (s1 >= 10 || s2 > 10)
                if (!isClockTime) {
                    hostScore = s1
                    guestScore = s2
                    break
                }
            }
        }

        // 4. Tách đội nhà và đội khách
        var host = ""
        var guest = ""
        val vsSplit = when {
            text.contains(" vs ", ignoreCase = true) -> text.split(Regex("""\s+vs\s+""", RegexOption.IGNORE_CASE), 2)
            text.contains(" vs. ", ignoreCase = true) -> text.split(Regex("""\s+vs\.\s+""", RegexOption.IGNORE_CASE), 2)
            hostScore != null && guestScore != null -> text.split(Regex("""\b\d{1,2}\s*[-:]\s*\d{1,2}\b"""), 2)
            text.contains(" - ") -> text.split(Regex("""\s+-\s+"""), 2)
            else -> null
        }

        if (vsSplit != null && vsSplit.size >= 2) {
            host = vsSplit[0].replace(Regex("""^\d+\s*"""), "").trim()
            guest = vsSplit[1].replace(Regex("""\s*\d+$"""), "").trim()
        } else {
            host = text
            guest = ""
        }

        val cleanTitle = if (guest.isNotBlank()) "$host vs $guest" else host

        return ParsedMatch(
            league = detectedLeague,
            host = host,
            guest = guest,
            cleanTitle = cleanTitle,
            hostScore = hostScore,
            guestScore = guestScore
        )
    }

    // ===== Cửa sổ suy luận trạng thái theo giờ — HẰNG SỐ DUY NHẤT cho toàn app =====
    // Mọi nơi cần đoán "đang đá / kết thúc" theo đồng hồ (DataNormalizer, MatchCardsAdapter,
    // tab Lịch) đều phải dùng các hằng số này, không tự chế số phút riêng (trước đây từng
    // tồn tại song song 120'/140'/180' ở ba nơi khiến cùng một trận LIVE ở màn này
    // nhưng KẾT THÚC ở màn khác).
    /** Tối đa 180' kể từ giờ bóng lăn còn coi là đang đá (khi có phòng live thật). */
    const val MATCH_LIVE_WINDOW_MS = 180 * 60_000L
    /** Không có phòng live: thu hẹp cửa sổ đoán xuống 120'. */
    const val MATCH_LIVE_NO_ROOM_WINDOW_MS = 120 * 60_000L
    /** Cho phép coi là live sớm tối đa 10' trước giờ bóng lăn (sai số đồng hồ/nguồn). */
    const val MATCH_KICKOFF_TOLERANCE_MS = 10 * 60_000L

    /**
     * Xác định trạng thái trận đấu chuẩn xác theo múi giờ & thời lượng thi đấu.
     * LƯU Ý: đây chỉ là FALLBACK cuối cùng — nơi nào có dữ kiện thật (trạng thái API,
     * phòng live thật, meta registry) phải ưu tiên dữ kiện thật trước khi gọi hàm này.
     */
    fun isMatchLive(matchTimeMs: Long, hasRoom: Boolean = false): Boolean {
        if (matchTimeMs <= 0) return false
        val now = System.currentTimeMillis()
        val diff = now - matchTimeMs
        return diff in 0..MATCH_LIVE_WINDOW_MS && (hasRoom || diff < MATCH_LIVE_NO_ROOM_WINDOW_MS)
    }

    fun isMatchFinished(matchTimeMs: Long): Boolean {
        if (matchTimeMs <= 0) return false
        val now = System.currentTimeMillis()
        return (now - matchTimeMs) > MATCH_LIVE_WINDOW_MS
    }

    fun isMatchUpcoming(matchTimeMs: Long): Boolean {
        if (matchTimeMs <= 0) return false
        val now = System.currentTimeMillis()
        return matchTimeMs > now
    }

    /**
     * Định dạng giờ đá trận đấu hiển thị trên thẻ trận (VD: "19:30", "19:30 · Ngày mai", "19:30 · 04/11")
     */
    fun formatKickoffTime(matchTimeMs: Long): String {
        if (matchTimeMs <= 0L) return ""
        val ms = if (matchTimeMs in 1..100_000_000_000L) matchTimeMs * 1000L else matchTimeMs
        val tz = TZ_VN
        val calMatch = Calendar.getInstance(tz).apply { timeInMillis = ms }
        val calNow = Calendar.getInstance(tz)

        val timeFmt = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }
        val timeStr = timeFmt.format(Date(ms))

        val isSameDay = calMatch.get(Calendar.YEAR) == calNow.get(Calendar.YEAR) &&
                calMatch.get(Calendar.DAY_OF_YEAR) == calNow.get(Calendar.DAY_OF_YEAR)

        calNow.add(Calendar.DAY_OF_YEAR, 1)
        val isTomorrow = calMatch.get(Calendar.YEAR) == calNow.get(Calendar.YEAR) &&
                calMatch.get(Calendar.DAY_OF_YEAR) == calNow.get(Calendar.DAY_OF_YEAR)

        return when {
            isSameDay -> timeStr
            isTomorrow -> "$timeStr · Ngày mai"
            else -> {
                val dateFmt = SimpleDateFormat("dd/MM", Locale.US).apply { timeZone = tz }
                "$timeStr · ${dateFmt.format(Date(ms))}"
            }
        }
    }

    /**
     * Định dạng nhãn ngày chuẩn xác so sánh theo Calendar Ngày/Tháng/Năm
     */
    fun formatScheduleDay(date: Date): String {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TZ_VN }
        val dateKey = fmt.format(date)

        val calNow = Calendar.getInstance(TZ_VN)
        val todayKey = fmt.format(calNow.time)

        calNow.add(Calendar.DATE, 1)
        val tomorrowKey = fmt.format(calNow.time)

        return when (dateKey) {
            todayKey -> "Hôm nay"
            tomorrowKey -> "Ngày mai"
            else -> {
                val dowFmt = SimpleDateFormat("EEEE, dd/MM", Locale("vi")).apply { timeZone = TZ_VN }
                dowFmt.format(date).replaceFirstChar { it.uppercase() }
            }
        }
    }

    /**
     * Chuẩn hóa URL ảnh (poster/thumbnail) của phim ảnh
     */
    fun normalizeImageUrl(url: String): String {
        val u = url.trim()
        if (u.isBlank()) return ""
        return when {
            u.startsWith("http://") || u.startsWith("https://") -> u
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> "https://phimimg.com$u"
            else -> "https://phimimg.com/$u"
        }
    }

    /**
     * Chuẩn hóa hiển thị tập phim NguonC
     */
    fun normalizeEpisodeName(rawEpisode: String): String {
        val ep = rawEpisode.trim()
        if (ep.isBlank()) return "Tập 1"
        return when {
            ep.contains("Hoàn tất", ignoreCase = true) || ep.contains("Full", ignoreCase = true) ->
                ep.replace("Hoàn tất", "Trọn bộ", ignoreCase = true)
            ep.matches(Regex("""^\d+$""")) -> "Tập $ep"
            else -> ep
        }
    }

    /**
     * Chuẩn hóa chất lượng và phụ đề phim
     */
    fun normalizeQuality(raw: String): String {
        val q = raw.trim()
        return when {
            q.isBlank() || q.equals("HD", ignoreCase = true) -> "Full HD"
            q.equals("FHD", ignoreCase = true) || q.equals("1080p", ignoreCase = true) -> "Full HD 1080p"
            q.equals("4K", ignoreCase = true) -> "4K Ultra HD"
            else -> q
        }
    }

    fun normalizeLanguage(raw: String): String {
        val l = raw.trim()
        return if (l.isBlank()) "Vietsub" else l
    }

    /**
     * Chuẩn hóa key của kênh truyền hình IPTV để so khớp lịch EPG chính xác 100%
     */
    fun normalizeChannelKey(raw: String): String {
        val noAccents = removeDiacritics(raw)
        return noAccents
            .replace(".vn", "")
            .replace("@sd", "")
            .replace("@hd", "")
            .replace("1080p", "")
            .replace("720p", "")
            .replace("fhd", "")
            .replace("hd", "")
            .replace("sd", "")
            .replace("vietnam", "")
            .replace("truyen hinh", "")
            .replace("kenh", "")
            .replace(Regex("""[^a-z0-9]"""), "")
            .trim()
    }
}
