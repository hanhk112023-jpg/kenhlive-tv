package com.kenhlive.tv.iptv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class IptvChannel(
    val id: String,
    val name: String,
    val group: String,
    val logo: String,
    val url: String
)

object IptvRepository {
    private const val PREFS = "iptv_prefs"
    private const val KEY_URL = "m3u_url"
    private const val KEY_CACHE = "m3u_cached_content"
    private const val KEY_LAST_UPDATE = "m3u_last_update"

    // Playlist m3u tự động cập nhật mỗi 6h từ vbskycn/iptv (492 kênh CCTV, vệ tinh, phim, tài liệu, thể thao)
    const val DEFAULT_M3U_URL = "https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u"
    const val BACKUP_M3U_URL = "https://live.zbds.top/tv/iptv4.m3u"

    fun getM3uUrl(context: Context): String {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getString(KEY_URL, DEFAULT_M3U_URL) ?: DEFAULT_M3U_URL
    }

    fun saveM3uUrl(context: Context, url: String) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_URL, url.trim()).apply()
    }

    suspend fun loadChannels(context: Context, forceRefresh: Boolean = false): List<IptvChannel> = withContext(Dispatchers.IO) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val m3uUrl = getM3uUrl(context)
        val cached = sp.getString(KEY_CACHE, null)
        val lastUpdate = sp.getLong(KEY_LAST_UPDATE, 0L)
        val isExpired = System.currentTimeMillis() - lastUpdate > 3600_000L // 1 tiếng tự động cập nhật

        if (!forceRefresh && !cached.isNullOrEmpty() && !isExpired) {
            val list = parseM3u(cached)
            if (list.isNotEmpty()) return@withContext list
        }

        // Tải m3u/m3u8 từ mạng
        var content = fetchUrl(m3uUrl)
        if (content.isNullOrBlank() && m3uUrl == DEFAULT_M3U_URL) {
            content = fetchUrl(BACKUP_M3U_URL)
        }
        if (!content.isNullOrBlank()) {
            sp.edit()
                .putString(KEY_CACHE, content)
                .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                .apply()
            val list = parseM3u(content)
            if (list.isNotEmpty()) return@withContext list
        }

        // Nếu lỗi mạng hoặc link người dùng lỗi mà có cache cũ thì dùng cache
        if (!cached.isNullOrEmpty()) {
            return@withContext parseM3u(cached)
        }

        emptyList()
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

    fun parseM3u(content: String): List<IptvChannel> {
        val list = mutableListOf<IptvChannel>()
        val lines = content.lines()
        var curName = ""
        var curGroup = "Truyền hình"
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
                curGroup = groupRegex.find(trimmed)?.groupValues?.get(1) ?: "Truyền hình"
                curId = tvgIdRegex.find(trimmed)?.groupValues?.get(1) ?: ""
                val tvgName = tvgNameRegex.find(trimmed)?.groupValues?.get(1) ?: ""
                
                // Lấy tên sau dấu phẩy cuối cùng nếu có
                val commaIdx = trimmed.lastIndexOf(',')
                val dispName = if (commaIdx != -1 && commaIdx < trimmed.length - 1) {
                    trimmed.substring(commaIdx + 1).trim()
                } else tvgName

                curName = dispName.ifEmpty { tvgName.ifEmpty { "Kênh TV" } }
            } else if (!trimmed.startsWith("#")) {
                // Đây là dòng link URL (m3u8, flv, rtmp, mp4, etc.)
                if (curName.isNotEmpty() && (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true))) {
                    list.add(
                        IptvChannel(
                            id = curId.ifEmpty { curName },
                            name = curName,
                            group = curGroup.ifEmpty { "Truyền hình" },
                            logo = curLogo,
                            url = trimmed
                        )
                    )
                }
                curName = ""
                curLogo = ""
                curGroup = "Truyền hình"
                curId = ""
            }
        }
        return list
    }
}
