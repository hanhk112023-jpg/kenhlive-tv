package com.kenhlive.tv

import org.json.JSONObject

/**
 * Parser thuần JVM (không phụ thuộc Android) — test được bằng unit test.
 * Tách JSON → model cho phòng live / lịch / stream.
 */
object SocoliveParser {

    /** Bọc JSONP: `rooms({...})` → `{...}`. */
    fun stripJsonp(raw: String): String {
        val s = raw.trim()
        val open = s.indexOf('(')
        val close = s.lastIndexOf(')')
        return if (open > 0 && close > open) s.substring(open + 1, close) else s
    }

    /** "Ngoại Hạng Anh: Real vs Barca" → league="Ngoại Hạng Anh", match="Real vs Barca". */
    fun splitTitle(rawTitle: String): Pair<String, String> {
        val t = rawTitle.trim()
        val idx = t.indexOf(':')
        return if (idx > 0) t.substring(0, idx).trim() to t.substring(idx + 1).trim()
        else "SocoLive" to t.ifBlank { "Live" }
    }

    fun parseLiveRooms(body: String): List<LiveRoom> {
        val data = JSONObject(stripJsonp(body)).optJSONObject("data") ?: JSONObject()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<LiveRoom>()
        for (key in data.keys()) {
            val arr = data.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val num = r.optString("roomNum", "")
                if (num.isBlank() || r.optInt("liveStatus", 0) != 1 || num in seen) continue
                seen.add(num)
                val a = r.optJSONObject("anchor") ?: JSONObject()
                val (league, match) = splitTitle(r.optString("title", "Live"))
                out.add(
                    LiveRoom(
                        roomNum = num,
                        blvName = a.optString("nickName", "BLV"),
                        avatar = a.optString("icon", "").ifBlank { a.optString("cutOutIcon", "") },
                        viewers = r.optInt("viewCount", 0),
                        matchTitle = match,
                        league = league,
                        cover = r.optString("cover", "")
                    )
                )
            }
        }
        return out.sortedByDescending { it.viewers }
    }

    /** Gom phòng live theo trận (giải + tên trận), phòng sort theo viewers desc. */
    fun groupRooms(rooms: List<LiveRoom>): List<LiveMatchGroup> =
        rooms.groupBy { it.league to it.matchTitle }
            .values
            .map { g -> LiveMatchGroup(g.first().league, g.first().matchTitle, g.sortedByDescending { it.viewers }) }
            .sortedByDescending { it.totalViewers }

    fun parseScheduleDay(body: String, addMatch: (JSONObject) -> Unit) {
        val arr = JSONObject(stripJsonp(body)).optJSONArray("data") ?: return
        for (j in 0 until arr.length()) arr.optJSONObject(j)?.let(addMatch)
    }

    /** Stream URL ưu tiên: hdM3u8 → m3u8 → hdFlv → flv. */
    fun parseStream(body: String): String? {
        val data = JSONObject(stripJsonp(body)).optJSONObject("data") ?: return null
        val stream = data.optJSONObject("stream") ?: return null
        return listOf("hdM3u8", "m3u8", "hdFlv", "flv").firstNotNullOfOrNull { k ->
            stream.optString(k, "").takeIf { it.isNotBlank() }
        }
    }
}
