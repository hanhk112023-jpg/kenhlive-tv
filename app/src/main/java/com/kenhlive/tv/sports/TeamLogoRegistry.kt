package com.kenhlive.tv.sports

import com.kenhlive.tv.DataNormalizer
import com.kenhlive.tv.KenhLiveApp
import com.kenhlive.tv.LiveMatchGroup
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Kho dữ liệu Logo Đội Bóng Thời Gian Thực (Real-Time Team Logo Registry).
 * Thu thập và đồng bộ logo các đội bóng từ:
 * 1. API các nhà đài (ColaTV, Gà Vàng 33, Giờ Vàng TV, Socolive Schedule)
 * 2. Kho logo chính thức 500x500 PNG sắc nét chuẩn 4K Smart TV từ ESPN CDN cho các CLB hàng đầu thế giới
 */
object TeamLogoRegistry {

    private val registry = ConcurrentHashMap<String, String>()

    private val STRIP_REGEX = Pattern.compile(
        """\b(fc|cf|sc|ac|clb|club|u\d+|united|city|real|sporting|atletico|borussia)\b""",
        Pattern.CASE_INSENSITIVE
    )
    private val NON_ALPHANUM = Pattern.compile("""[^a-zA-Z0-9\u00C0-\u1EF9]""")

    init {
        seedTopClubs()
        seedFromFallbackSchedule()
    }

    fun normalize(name: String): String {
        if (name.isBlank()) return ""
        var n = name.lowercase().trim()
        n = n.replace(Regex("""\[.*?\]|\(.*?\)|vs\.?|-"""), " ")
        n = NON_ALPHANUM.matcher(n).replaceAll("")
        return n.trim()
    }

    private fun simplify(name: String): String {
        if (name.isBlank()) return ""
        var n = name.lowercase().trim()
        n = STRIP_REGEX.matcher(n).replaceAll(" ")
        n = NON_ALPHANUM.matcher(n).replaceAll("")
        return n.trim()
    }

    fun register(teamName: String, logoUrl: String) {
        if (teamName.isBlank() || logoUrl.isBlank()) return
        val norm = normalize(teamName)
        if (norm.isNotBlank()) registry[norm] = logoUrl
        val simp = simplify(teamName)
        if (simp.isNotBlank() && !registry.containsKey(simp)) {
            registry[simp] = logoUrl
        }
    }

    fun findLogo(teamName: String): String? {
        if (teamName.isBlank()) return null
        val norm = normalize(teamName)
        registry[norm]?.let { return it }

        val simp = simplify(teamName)
        registry[simp]?.let { return it }

        // Tìm kiếm đối sánh gần đúng (prefix hoặc substring)
        if (norm.length >= 4) {
            for ((key, url) in registry) {
                if (key.length >= 4 && (norm.contains(key) || key.contains(norm))) {
                    return url
                }
            }
        }
        if (simp.length >= 4) {
            for ((key, url) in registry) {
                if (key.length >= 4 && (simp.contains(key) || key.contains(simp))) {
                    return url
                }
            }
        }
        return null
    }

    /**
     * Tự động bổ sung logo đội nhà & đội khách nếu nhóm trận chưa có
     */
    fun enrich(group: LiveMatchGroup): LiveMatchGroup {
        val parsed = DataNormalizer.parseMatch(group.matchTitle, group.league)
        val hIcon = if (group.hostIcon.isNotBlank()) group.hostIcon else findLogo(parsed.host).orEmpty()
        val gIcon = if (group.guestIcon.isNotBlank()) group.guestIcon else findLogo(parsed.guest).orEmpty()

        if (hIcon == group.hostIcon && gIcon == group.guestIcon) {
            return group
        }
        return group.copy(
            hostIcon = hIcon,
            guestIcon = gIcon
        )
    }

    private fun seedFromFallbackSchedule() {
        try {
            val ctx = KenhLiveApp.appContext
            val text = ctx.assets.open("fallback_schedule.json").bufferedReader().use { it.readText() }
            val m = Regex("""^[a-zA-Z0-9_]+\((.*)\)\s*;?\s*""", RegexOption.DOT_MATCHES_ALL).find(text)
            val jsonStr = m?.groupValues?.get(1) ?: text
            val root = JSONObject(jsonStr)
            val matches = root.optJSONArray("data") ?: return
            for (i in 0 until matches.length()) {
                val item = matches.optJSONObject(i) ?: continue
                val h = item.optString("hostName")
                val g = item.optString("guestName")
                val hLogo = item.optString("hostIcon")
                val gLogo = item.optString("guestIcon")
                if (h.isNotBlank() && hLogo.isNotBlank()) register(h, hLogo)
                if (g.isNotBlank() && gLogo.isNotBlank()) register(g, gLogo)
            }
        } catch (_: Exception) {}
    }

    private fun seedTopClubs() {
        // Kho logo chất lượng cao 500x500 PNG sắc nét từ ESPN CDN
        val espnSoccer = mapOf(
            "manchestercity" to "382",
            "mancity" to "382",
            "manchesterunited" to "360",
            "manutd" to "360",
            "mu" to "360",
            "liverpool" to "364",
            "arsenal" to "359",
            "chelsea" to "363",
            "tottenham" to "367",
            "spurs" to "367",
            "astonvilla" to "362",
            "newcastle" to "379",
            "realmadrid" to "86",
            "barcelona" to "83",
            "barca" to "83",
            "atleticomadrid" to "1068",
            "atletico" to "1068",
            "bayernmunich" to "132",
            "bayern" to "132",
            "borussiadortmund" to "124",
            "dortmund" to "124",
            "bayerleverkusen" to "131",
            "leverkusen" to "131",
            "juventus" to "110",
            "acmilan" to "103",
            "milan" to "103",
            "intermilan" to "104",
            "inter" to "104",
            "psg" to "160",
            "parissaintgermain" to "160",
            "intermiami" to "20232",
            "sportingcp" to "3008",
            "benfica" to "1929",
            "porto" to "435",
            "ajax" to "139",
            "napoli" to "114",
            "asroma" to "105",
            "roma" to "105",
            "lazio" to "111",
            "rbleipzig" to "125",
            "marseille" to "164",
            "monaco" to "174"
        )
        for ((name, id) in espnSoccer) {
            register(name, "https://a.espncdn.com/i/teamlogos/soccer/500/$id.png")
        }
    }
}
