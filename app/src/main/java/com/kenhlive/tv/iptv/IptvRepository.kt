package com.kenhlive.tv.iptv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class IptvChannel(
    val id: String,
    val name: String,
    val group: String,
    val logo: String,
    val url: String,
    val isVn: Boolean = false,
    val country: String = ""
)

object IptvRepository {
    private const val PREFS = "iptv_prefs"
    private const val KEY_CACHE_VN = "m3u_cache_vn"
    private const val KEY_CACHE_SPORTS = "m3u_cache_sports"
    private const val KEY_LAST_UPDATE = "m3u_last_update"

    // Nguồn cố định: Việt Nam & Thể thao từ iptv-org
    const val URL_VN = "https://iptv-org.github.io/iptv/countries/vn.m3u"
    const val URL_SPORTS = "https://iptv-org.github.io/iptv/categories/sports.m3u"

    // Danh sách mã quốc gia Châu Âu & thương hiệu thể thao lớn
    private val EU_COUNTRIES = setOf(
        "UK", "GB", "ES", "FR", "DE", "IT", "PT", "NL", "BE", "CH", "AT", "SE", "NO", "DK", "FI", "PL", "RO", "CZ"
    )

    private val PREMIUM_SPORTS_KEYWORDS = listOf(
        "bein", "dazn", "canal+", "eurosport", "sky", "barca", "real madrid", "fifa", "uefa",
        "red bull", "formula", "f1", "motogp", "wrc", "fight", "combat", "tennis", "golf",
        "digi sport", "movistar", "sport", "football", "soccer", "racing"
    )

    suspend fun loadChannels(context: Context, forceRefresh: Boolean = false): List<IptvChannel> = withContext(Dispatchers.IO) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastUpdate = sp.getLong(KEY_LAST_UPDATE, 0L)
        val isExpired = System.currentTimeMillis() - lastUpdate > 3600_000L * 3 // Tự động làm mới mỗi 3 tiếng

        var cachedVn = sp.getString(KEY_CACHE_VN, null)
        var cachedSports = sp.getString(KEY_CACHE_SPORTS, null)

        if (forceRefresh || isExpired || cachedVn == null || cachedSports == null) {
            coroutineScope {
                val jobVn = async { fetchUrl(URL_VN) }
                val jobSports = async { fetchUrl(URL_SPORTS) }

                val resVn = jobVn.await()
                val resSports = jobSports.await()

                val editor = sp.edit()
                if (!resVn.isNullOrBlank()) {
                    cachedVn = resVn
                    editor.putString(KEY_CACHE_VN, resVn)
                }
                if (!resSports.isNullOrBlank()) {
                    cachedSports = resSports
                    editor.putString(KEY_CACHE_SPORTS, resSports)
                }
                editor.putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
            }
        }

        val result = mutableListOf<IptvChannel>()

        // 1. Kênh Việt Nam tuyển chọn
        if (!cachedVn.isNullOrBlank()) {
            result.addAll(parseM3u(cachedVn!!, defaultGroup = "Việt Nam", isVn = true, filterEuSports = false))
        }

        // 2. Kênh Thể thao Châu Âu & Premium quốc tế (chất lượng > số lượng)
        if (!cachedSports.isNullOrBlank()) {
            result.addAll(parseM3u(cachedSports!!, defaultGroup = "Thể thao Châu Âu", isVn = false, filterEuSports = true))
        }

        result.distinctBy { it.url }
    }

    private fun fetchUrl(urlStr: String): String? {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun parseM3u(content: String, defaultGroup: String, isVn: Boolean, filterEuSports: Boolean): List<IptvChannel> {
        val list = mutableListOf<IptvChannel>()
        val lines = content.lines()
        var curName = ""
        var curGroup = defaultGroup
        var curLogo = ""
        var curId = ""
        var curCountry = ""

        val tvgNameRegex = Regex("""tvg-name="([^"]*)"""", RegexOption.IGNORE_CASE)
        val tvgLogoRegex = Regex("""tvg-logo="([^"]*)"""", RegexOption.IGNORE_CASE)
        val groupRegex = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE)
        val tvgIdRegex = Regex("""tvg-id="([^"]*)"""", RegexOption.IGNORE_CASE)
        val countryRegex = Regex("""tvg-country="([^"]*)"""", RegexOption.IGNORE_CASE)
        val idCountryRegex = Regex("""tvg-id="[^"]*\.([a-zA-Z]{2})@""", RegexOption.IGNORE_CASE)

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                curLogo = tvgLogoRegex.find(trimmed)?.groupValues?.get(1) ?: ""
                val foundGroup = groupRegex.find(trimmed)?.groupValues?.get(1) ?: ""
                curId = tvgIdRegex.find(trimmed)?.groupValues?.get(1) ?: ""
                val tvgName = tvgNameRegex.find(trimmed)?.groupValues?.get(1) ?: ""

                // Trích xuất quốc gia
                var country = countryRegex.find(trimmed)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: ""
                if (country.isEmpty()) {
                    country = idCountryRegex.find(trimmed)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: ""
                }
                curCountry = country

                val commaIdx = trimmed.lastIndexOf(',')
                val dispName = if (commaIdx != -1 && commaIdx < trimmed.length - 1) {
                    trimmed.substring(commaIdx + 1).trim()
                } else tvgName

                var cleanName = (if (dispName.isNotEmpty()) dispName else tvgName).ifEmpty { "Kênh TV" }
                val isGeoBlocked = cleanName.contains("[Geo-blocked]", ignoreCase = true)
                cleanName = cleanName.replace(Regex("""\[Geo-blocked\]""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""\[Not 24/7\]""", RegexOption.IGNORE_CASE), "")
                    .trim()

                curName = cleanName

                curGroup = when {
                    isVn -> "Việt Nam"
                    curCountry in EU_COUNTRIES -> "Châu Âu (${curCountry})"
                    else -> defaultGroup
                }

                // Nếu lọc thể thao: Bỏ geo-block và chỉ giữ Châu Âu / thương hiệu thể thao lớn
                if (filterEuSports) {
                    val nameLower = cleanName.lowercase(Locale.ROOT)
                    val isPremiumBrand = PREMIUM_SPORTS_KEYWORDS.any { nameLower.contains(it) }
                    val isEu = curCountry in EU_COUNTRIES
                    if (isGeoBlocked || (!isEu && !isPremiumBrand)) {
                        curName = ""
                    }
                }
            } else if (!trimmed.startsWith("#")) {
                if (curName.isNotEmpty() && (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true))) {
                    list.add(
                        IptvChannel(
                            id = curId.ifEmpty { curName },
                            name = curName,
                            group = curGroup,
                            logo = curLogo,
                            url = trimmed,
                            isVn = isVn,
                            country = curCountry
                        )
                    )
                }
                curName = ""
                curLogo = ""
                curCountry = ""
                curGroup = defaultGroup
                curId = ""
            }
        }
        return list
    }
}
