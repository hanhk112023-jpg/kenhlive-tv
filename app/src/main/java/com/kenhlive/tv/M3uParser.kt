package com.kenhlive.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class Channel(
    val name: String,
    val group: String,
    val logo: String,
    val url: String
)

object M3uParser {
    suspend fun fetch(urlStr: String): List<Channel> = withContext(Dispatchers.IO) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0.0.0")
        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        val channels = mutableListOf<Channel>()
        var pendingGroup = "SocoLive"
        var pendingLogo = ""
        var pendingName = ""
        reader.readLines().forEach { line ->
            if (line.startsWith("#EXTINF")) {
                pendingName = line.substringAfterLast(",").trim()
                pendingGroup = extractAttr(line, "group-title") ?: "SocoLive"
                pendingLogo = extractAttr(line, "tvg-logo") ?: ""
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                if (pendingName.isNotBlank()) {
                    channels.add(Channel(pendingName, pendingGroup, pendingLogo, line.trim()))
                    pendingName = ""
                }
            }
        }
        reader.close()
        conn.disconnect()
        channels
    }

    private fun extractAttr(line: String, key: String): String? {
        val idx = line.indexOf(key + "=\"")
        if (idx < 0) return null
        val start = idx + key.length + 2
        val end = line.indexOf("\"", start)
        return if (end < 0) null else line.substring(start, end)
    }
}
