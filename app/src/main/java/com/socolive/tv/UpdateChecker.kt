package com.socolive.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppRelease(
    val version: String,
    val apkUrl: String,
    val pageUrl: String
)

object UpdateChecker {
    private const val LATEST =
        "https://api.github.com/repos/hanhk112023-jpg/socolive-tv/releases/latest"

    suspend fun latest(): AppRelease? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(LATEST).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "SocoliveTV-app")
            if (conn.responseCode != 200) return@withContext null
            val body = conn.inputStream.bufferedReader().readText()
            val j = JSONObject(body)
            val tag = j.optString("tag_name", "")
            var apk = ""
            val assets = j.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name", "").endsWith(".apk")) {
                        apk = a.optString("browser_download_url", "")
                        break
                    }
                }
            }
            if (tag.isBlank() || apk.isBlank()) return@withContext null
            AppRelease(
                version = tag.removePrefix("v").removePrefix("V"),
                apkUrl = apk,
                pageUrl = j.optString("html_url", "")
            )
        } catch (e: Exception) {
            null
        }
    }

    /** true nếu remote mới hơn current (so sánh từng số: 1.10 > 1.9). */
    fun isNewer(remote: String, current: String): Boolean {
        fun parse(v: String) = v.removePrefix("v").removePrefix("V")
            .split('.').map { it.trim().toIntOrNull() ?: 0 }
        val r = parse(remote)
        val c = parse(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
