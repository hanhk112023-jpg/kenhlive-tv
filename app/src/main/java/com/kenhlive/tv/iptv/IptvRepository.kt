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
    private const val FILE_CACHE_PARSED = "cache_iptv_parsed.json"
    private const val ASSET_FALLBACK_VN = "fallback_vn.m3u"

    // Bộ nhớ RAM tĩnh để PlayerActivity mở sidebar chuyển kênh tức thì
    var currentChannels: List<IptvChannel> = emptyList()

    /** Đọc nhanh danh sách kênh đã parse từ ổ đĩa (0s parsing, hiển thị tức thì) */
    fun getCachedChannelsDisk(context: Context): List<IptvChannel>? {
        if (currentChannels.isNotEmpty()) return currentChannels
        return try {
            val file = java.io.File(context.cacheDir, FILE_CACHE_PARSED)
            if (file.exists() && file.length() > 0) {
                val jsonArr = org.json.JSONArray(file.readText())
                val list = mutableListOf<IptvChannel>()
                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.optJSONObject(i) ?: continue
                    list.add(
                        IptvChannel(
                            id = obj.optString("id", ""),
                            name = obj.optString("name", ""),
                            group = obj.optString("group", ""),
                            logo = obj.optString("logo", ""),
                            url = obj.optString("url", ""),
                            isVn = obj.optBoolean("isVn", false),
                            country = obj.optString("country", "")
                        )
                    )
                }
                if (list.isNotEmpty()) {
                    currentChannels = list
                    list
                } else null
            } else null
        } catch (_: Exception) { null }
    }

    private fun saveParsedChannelsDisk(context: Context, list: List<IptvChannel>) {
        try {
            val jsonArr = org.json.JSONArray()
            for (ch in list) {
                val obj = org.json.JSONObject().apply {
                    put("id", ch.id)
                    put("name", ch.name)
                    put("group", ch.group)
                    put("logo", ch.logo)
                    put("url", ch.url)
                    put("isVn", ch.isVn)
                    put("country", ch.country)
                }
                jsonArr.put(obj)
            }
            val file = java.io.File(context.cacheDir, FILE_CACHE_PARSED)
            file.writeText(jsonArr.toString())
        } catch (_: Exception) { }
    }

    // Nguồn cố định: Việt Nam & Thể thao từ iptv-org
    const val URL_VN = "https://iptv-org.github.io/iptv/countries/vn.m3u"
    const val URL_SPORTS = "https://iptv-org.github.io/iptv/categories/sports.m3u"

    // Danh sách mã quốc gia Châu Âu & thương hiệu thể thao lớn
    private val EU_COUNTRIES = setOf(
        "UK", "GB", "ES", "FR", "DE", "IT", "PT", "NL", "BE", "CH", "AT", "SE", "NO", "DK", "FI", "PL", "RO", "CZ"
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

        if (cachedVn.isNullOrBlank()) {
            cachedVn = try {
                context.assets.open(ASSET_FALLBACK_VN).bufferedReader().use { it.readText() }
            } catch (_: Exception) { null }
        }

        val result = mutableListOf<IptvChannel>()

        // 1. Kênh Việt Nam tuyển chọn: VTV lên đầu, sau đó đến các kênh TW/HTV, cuối cùng là kênh địa phương
        if (!cachedVn.isNullOrBlank()) {
            val vnRaw = parseM3u(cachedVn!!, defaultGroup = "Việt Nam", isVn = true, filterSports = false)
            val sortedVn = vnRaw.sortedWith(
                compareBy(
                    { ch ->
                        val n = ch.name.lowercase(Locale.ROOT)
                        when {
                            n.contains("vtv") -> 0         // VTV ưu tiên số 1 trên đầu
                            n.contains("htv") || n.contains("vov") || n.contains("thvl") || n.contains("vtc") || n.contains("qpv") || n.contains("truyền hình quốc hội") -> 1 // Đài lớn
                            else -> 2                      // Kênh tỉnh/địa phương xếp xuống dưới
                        }
                    },
                    { it.name }
                )
            )
            result.addAll(sortedVn)
        }

        // 2. Kênh Thể thao Quốc tế (DAZN, Sky Sports, beIN, ESPN, FIFA+, Fight, Tennis, Golf, F1...)
        if (!cachedSports.isNullOrBlank()) {
            result.addAll(parseM3u(cachedSports!!, defaultGroup = "Thể Thao", isVn = false, filterSports = true))
        }

        val distinctList = result.distinctBy { it.url }
        currentChannels = distinctList
        saveParsedChannelsDisk(context, distinctList)
        distinctList
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

    fun parseM3u(content: String, defaultGroup: String, isVn: Boolean, filterSports: Boolean): List<IptvChannel> {
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

                // Nếu lọc thể thao: Lấy DAZN, Sky Sports, beIN, ESPN, FIFA+, Fight, Tennis, Golf, F1...
                if (filterSports) {
                    val nameLower = cleanName.lowercase(Locale.ROOT)
                    val isPopularSports = nameLower.contains("dazn") ||
                        nameLower.contains("sky sport") ||
                        nameLower.contains("skysport") ||
                        nameLower.contains("bein") ||
                        nameLower.contains("espn") ||
                        nameLower.contains("fifa") ||
                        nameLower.contains("fight") ||
                        nameLower.contains("combat") ||
                        nameLower.contains("tennis") ||
                        nameLower.contains("golf") ||
                        nameLower.contains("f1") ||
                        nameLower.contains("racing")

                    if (isGeoBlocked || !isPopularSports) {
                        curName = ""
                    } else {
                        curGroup = when {
                            nameLower.contains("dazn") -> "DAZN"
                            nameLower.contains("sky") -> "Sky Sports"
                            nameLower.contains("bein") -> "beIN Sports"
                            nameLower.contains("espn") -> "ESPN"
                            nameLower.contains("fifa") -> "FIFA+"
                            else -> "Thể Thao"
                        }
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
