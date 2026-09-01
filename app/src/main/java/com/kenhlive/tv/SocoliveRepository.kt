package com.kenhlive.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Gọi thẳng API Socolive (json.vnres.co) — không qua M3U trung gian. */
object SocoliveRepository {
    private const val API = "https://json.vnres.co"
    private const val UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    private fun stamp(): String = (System.currentTimeMillis() / 1000).toString()

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Referer", "https://vnres.co/")
        conn.setRequestProperty("Accept", "application/json, text/plain, */*")
        if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().readText()
    }

    private fun stripJsonp(raw: String): String {
        val s = raw.trim()
        val open = s.indexOf('(')
        val close = s.lastIndexOf(')')
        return if (open > 0 && close > open) s.substring(open + 1, close) else s
    }

    /** Danh sách phòng đang live → Channel (group = giải, name = "A vs B"). */
    suspend fun fetchRooms(): List<Channel> = withContext(Dispatchers.IO) {
        val now = stamp()
        val body = get("$API/all_live_rooms.json?callback=rooms&v=$now&_=$now")
        val data = JSONObject(stripJsonp(body)).optJSONObject("data") ?: JSONObject()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Channel>()
        for (key in data.keys()) {
            val arr = data.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val num = r.optString("roomNum", "")
                if (num.isBlank() || r.optInt("liveStatus", 0) != 1 || num in seen) continue
                seen.add(num)
                val rawTitle = r.optString("title", "Live").trim()
                // "Giải: A vs B" → group="Giải", name="A vs B"
                val idx = rawTitle.indexOf(':')
                val group = if (idx > 0) rawTitle.substring(0, idx).trim() else "SocoLive"
                val name = if (idx > 0) rawTitle.substring(idx + 1).trim() else rawTitle
                val anchor = r.optJSONObject("anchor")?.optString("nickName", "") ?: ""
                out.add(
                    Channel(
                        name = name, group = group,
                        logo = r.optString("cover", ""),
                        url = "", roomNum = num, anchor = anchor
                    )
                )
            }
        }
        out.sortedWith(compareBy({ it.group }, { it.name }))
    }

    /** Stream URL từ roomNum: hdM3u8 → m3u8 → hdFlv → flv. */
    suspend fun fetchStream(roomNum: String): String? = withContext(Dispatchers.IO) {
        try {
            val now = stamp()
            val body = get("$API/room/$roomNum/detail.json?callback=detail&v=$now&_=$now")
            val data = JSONObject(stripJsonp(body)).optJSONObject("data") ?: return@withContext null
            val stream = data.optJSONObject("stream") ?: return@withContext null
            listOf("hdM3u8", "m3u8", "hdFlv", "flv").firstNotNullOfOrNull { k ->
                stream.optString(k, "").takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) { null }
    }
}
