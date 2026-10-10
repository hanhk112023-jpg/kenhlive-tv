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
        listOf("ngoai hang anh", "premier league", "epl") to "Ngoại Hạng Anh",
        listOf("cup c1", "champions league", "ucl") to "Cúp C1 Châu Âu",
        listOf("cup c2", "europa league", "uel") to "Cúp C2 Châu Âu",
        listOf("cup c3", "conference league", "uecl") to "Cúp C3 Châu Âu",
        listOf("la liga", "laliga", "vdqg tay ban nha") to "La Liga",
        listOf("serie a", "vdqg y", "vdqg italia") to "Serie A",
        listOf("bundesliga", "vdqg duc") to "Bundesliga",
        listOf("ligue 1", "vdqg phap") to "Ligue 1",
        listOf("v-league", "vleague", "v league", "vdqg viet nam") to "V-League 1",
        listOf("saudi pro league", "spl", "a rap") to "Saudi Pro League",
        listOf("world cup") to "World Cup",
        listOf("euro") to "Euro",
        listOf("afc champions league", "cup c1 chau a") to "AFC Champions League",
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
            if (l.isNotBlank()) detectedLeague = l
            text = bracketMatch.groupValues[2].trim()
        } else {
            val colonIdx = text.indexOf(':')
            if (colonIdx in 1..25) {
                val l = text.substring(0, colonIdx).trim()
                if (l.isNotBlank() && !l.equals("Live", ignoreCase = true)) {
                    detectedLeague = l
                    text = text.substring(colonIdx + 1).trim()
                }
            }
        }

        detectedLeague = normalizeLeague(detectedLeague)

        // 3. Tìm tỉ số (ví dụ: Arsenal 2 - 1 Chelsea hoặc Real Madrid 0:0 Barca)
        var hostScore: Int? = null
        var guestScore: Int? = null
        val scoreRegex = Regex("""\b(\d+)\s*[-:]\s*(\d+)\b""")
        val scoreMatch = scoreRegex.find(text)
        if (scoreMatch != null) {
            hostScore = scoreMatch.groupValues[1].toIntOrNull()
            guestScore = scoreMatch.groupValues[2].toIntOrNull()
        }

        // 4. Tách đội nhà và đội khách
        var host = ""
        var guest = ""
        val vsSplit = when {
            text.contains(" vs ", ignoreCase = true) -> text.split(Regex("""\s+vs\s+""", RegexOption.IGNORE_CASE), 2)
            text.contains(" vs. ", ignoreCase = true) -> text.split(Regex("""\s+vs\.\s+""", RegexOption.IGNORE_CASE), 2)
            scoreMatch != null -> text.split(scoreRegex, 2)
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

    /**
     * Xác định trạng thái trận đấu chuẩn xác theo múi giờ & thời lượng thi đấu
     */
    fun isMatchLive(matchTimeMs: Long, hasRoom: Boolean = false): Boolean {
        if (matchTimeMs <= 0) return false
        val now = System.currentTimeMillis()
        val diff = now - matchTimeMs
        // Đang diễn ra trong khoảng từ thời điểm bắt đầu đến 180 phút (3 tiếng)
        return diff in 0..(180 * 60_000L) && (hasRoom || diff < 120 * 60_000L)
    }

    fun isMatchFinished(matchTimeMs: Long): Boolean {
        if (matchTimeMs <= 0) return false
        val now = System.currentTimeMillis()
        return (now - matchTimeMs) > (180 * 60_000L)
    }

    fun isMatchUpcoming(matchTimeMs: Long): Boolean {
        if (matchTimeMs <= 0) return false
        val now = System.currentTimeMillis()
        return matchTimeMs > now
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
            u.startsWith("/") -> "https://img.nguonc.com$u"
            else -> "https://img.nguonc.com/$u"
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
