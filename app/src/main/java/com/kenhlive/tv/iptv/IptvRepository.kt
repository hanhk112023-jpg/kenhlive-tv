package com.kenhlive.tv.iptv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class IptvChannel(
    val id: String,
    val name: String,
    val group: String,
    val logo: String,
    val url: String,
    val isVn: Boolean = false
)

object IptvRepository {
    private const val PREFS = "iptv_prefs"
    private const val KEY_CUSTOM_URL = "m3u_custom_url"
    private const val KEY_CACHE_VN = "m3u_cache_vn"
    private const val KEY_CACHE_SPORTS = "m3u_cache_sports"
    private const val KEY_CACHE_CUSTOM = "m3u_cache_custom"
    private const val KEY_LAST_UPDATE = "m3u_last_update"

    // Nguồn chính: Việt Nam & Thể thao quốc tế từ iptv-org
    const val URL_VN = "https://iptv-org.github.io/iptv/countries/vn.m3u"
    const val URL_SPORTS = "https://iptv-org.github.io/iptv/categories/sports.m3u"

    fun getCustomUrl(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_URL, "") ?: ""
    }

    fun saveCustomUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_URL, url.trim())
            .remove(KEY_CACHE_CUSTOM)
            .apply()
    }

    suspend fun loadChannels(context: Context, forceRefresh: Boolean = false): List<IptvChannel> = withContext(Dispatchers.IO) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val customUrl = getCustomUrl(context)
        val lastUpdate = sp.getLong(KEY_LAST_UPDATE, 0L)
        val isExpired = System.currentTimeMillis() - lastUpdate > 3600_000L * 3 // Cache 3 tiếng

        var cachedVn = sp.getString(KEY_CACHE_VN, null)
        var cachedSports = sp.getString(KEY_CACHE_SPORTS, null)
        var cachedCustom = sp.getString(KEY_CACHE_CUSTOM, null)

        if (forceRefresh || isExpired || cachedVn == null || cachedSports == null) {
            coroutineScope {
                val jobVn = async { fetchUrl(URL_VN) }
                val jobSports = async { fetchUrl(URL_SPORTS) }
                val jobCustom = if (customUrl.isNotEmpty()) async { fetchUrl(customUrl) } else null

                val resVn = jobVn.await()
                val resSports = jobSports.await()
                val resCustom = jobCustom?.await()

                val editor = sp.edit()
                if (!resVn.isNullOrBlank()) {
                    cachedVn = resVn
                    editor.putString(KEY_CACHE_VN, resVn)
                }
                if (!resSports.isNullOrBlank()) {
                    cachedSports = resSports
                    editor.putString(KEY_CACHE_SPORTS, resSports)
                }
                if (!resCustom.isNullOrBlank()) {
                    cachedCustom = resCustom
                    editor.putString(KEY_CACHE_CUSTOM, resCustom)
                }
                editor.putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
            }
        }

        val result = mutableListOf<IptvChannel>()

        // 1. Kênh Việt Nam
        if (!cachedVn.isNullOrBlank()) {
            result.addAll(parseM3u(cachedVn!!, defaultGroup = "Việt Nam", isVn = true))
        }

        // 2. Kênh Thể thao
        if (!cachedSports.isNullOrBlank()) {
            result.addAll(parseM3u(cachedSports!!, defaultGroup = "Thể thao", isVn = false))
        }

        // 3. Kênh từ link tùy chỉnh của người dùng (nếu có)
        if (!cachedCustom.isNullOrBlank()) {
            result.addAll(parseM3u(cachedCustom!!, defaultGroup = "Kênh riêng", isVn = false))
        }

        // Loại trừ kênh trùng lặp theo stream url
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

    fun parseM3u(content: String, defaultGroup: String, isVn: Boolean): List<IptvChannel> {
        val list = mutableListOf<IptvChannel>()
        val lines = content.lines()
        var curName = ""
        var curGroup = defaultGroup
        var curLogo = ""
        var curId = ""

        val tvgNameRegex = Regex("""tvg-name="([^"]*)"""", RegexOption.IGNORE_CASE)
        val tvgLogoRegex = Regex("""tvg-logo="([^"]*)"""", RegexOption.IGNORE_CASE)
        val groupRegex = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE)
        val tvgIdRegex = Regex("""tvg-id="([^"]*)"""", RegexOption.IGNORE_CASE)

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                curLogo = tvgLogoRegex.find(trimmed)?.groupValues?.get(1) ?: ""
                val foundGroup = groupRegex.find(trimmed)?.groupValues?.get(1) ?: ""
                curId = tvgIdRegex.find(trimmed)?.groupValues?.get(1) ?: ""
                val tvgName = tvgNameRegex.find(trimmed)?.groupValues?.get(1) ?: ""

                // Làm sạch tên nhóm
                curGroup = when {
                    isVn -> "Việt Nam"
                    foundGroup.contains("Sport", ignoreCase = true) -> "Thể thao"
                    foundGroup.isNotBlank() && !foundGroup.equals("Undefined", true) -> foundGroup
                    else -> defaultGroup
                }

                val commaIdx = trimmed.lastIndexOf(',')
                val dispName = if (commaIdx != -1 && commaIdx < trimmed.length - 1) {
                    trimmed.substring(commaIdx + 1).trim()
                } else tvgName

                // Lọc bỏ hậu tố phân giải không cần thiết để tên kênh gọn đẹp
                var cleanName = (if (dispName.isNotEmpty()) dispName else tvgName).ifEmpty { "Kênh TV" }
                cleanName = cleanName.replace(Regex("""\[Geo-blocked\]""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""\[Not 24/7\]""", RegexOption.IGNORE_CASE), "")
                    .trim()

                curName = cleanName
            } else if (!trimmed.startsWith("#")) {
                if (curName.isNotEmpty() && (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true))) {
                    list.add(
                        IptvChannel(
                            id = curId.ifEmpty { curName },
                            name = curName,
                            group = curGroup,
                            logo = curLogo,
                            url = trimmed,
                            isVn = isVn
                        )
                    )
                }
                curName = ""
                curLogo = ""
                curGroup = defaultGroup
                curId = ""
            }
        }
        return list
    }
}
