package com.kenhlive.tv.phim

import android.content.Context
import com.kenhlive.tv.Http
import com.kenhlive.tv.TextNorm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * NguoncRepository — Quản lý và cung cấp dữ liệu Phim Nguồn C (phim.nguonc.com):
 * - Hỗ trợ API online: Phim mới cập nhật, Tìm kiếm, Chi tiết phim, Danh sách tập
 * - Tích hợp Fallback Cache offline (trích xuất từ HAR) với hơn 240 phim đầy đủ tập
 * - Tìm kiếm không dấu thông minh khi offline hoặc mạng chập chờn
 */
object NguoncRepository {

    const val API_BASE = "https://phim.nguonc.com/api"
    const val REFERER_NGUONC = "https://phim.nguonc.com/"

    @Volatile
    private var fallbackCache: List<NguoncFilmDetail>? = null

    /**
     * Lấy danh sách phim mới cập nhật từ API hoặc Fallback
     */
    suspend fun fetchNewFilms(context: Context? = null, page: Int = 1): List<NguoncFilm> =
        withContext(Dispatchers.IO) {
            val url = "$API_BASE/films/phim-moi-cap-nhat?page=$page"
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Referer", REFERER_NGUONC)
                    .build()
                val jsonStr = Http.get().newCall(req).execute().use { res ->
                    if (!res.isSuccessful) throw Exception("HTTP ${res.code}")
                    res.body?.string().orEmpty()
                }
                val films = parseFilmsList(jsonStr)
                if (films.isNotEmpty()) return@withContext films
            } catch (_: Exception) {}

            // Fallback offline từ Assets nếu lỗi mạng
            if (context != null) {
                val cached = loadFallbackFilms(context)
                if (cached.isNotEmpty()) {
                    return@withContext cached.map { it.film }
                }
            }
            emptyList()
        }

    /**
     * Lấy chi tiết phim và danh sách tập phim theo slug
     */
    suspend fun fetchFilmDetail(slug: String, context: Context? = null): NguoncFilmDetail? =
        withContext(Dispatchers.IO) {
            val url = "$API_BASE/film/$slug"
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Referer", REFERER_NGUONC)
                    .build()
                val jsonStr = Http.get().newCall(req).execute().use { res ->
                    if (!res.isSuccessful) throw Exception("HTTP ${res.code}")
                    res.body?.string().orEmpty()
                }
                val detail = parseFilmDetail(jsonStr)
                if (detail != null) return@withContext detail
            } catch (_: Exception) {}

            // Tìm trong fallback nếu không gọi được online
            if (context != null) {
                val cached = loadFallbackFilms(context)
                return@withContext cached.firstOrNull { it.film.slug == slug }
            }
            null
        }

    /**
     * Tìm kiếm phim theo từ khóa
     */
    suspend fun searchFilms(query: String, context: Context? = null, page: Int = 1): List<NguoncFilm> =
        withContext(Dispatchers.IO) {
            val qTrim = query.trim()
            if (qTrim.isEmpty()) return@withContext emptyList()

            try {
                val encoded = URLEncoder.encode(qTrim, "UTF-8")
                val url = "$API_BASE/films/search?keyword=$encoded&page=$page"
                val req = Request.Builder()
                    .url(url)
                    .header("Referer", REFERER_NGUONC)
                    .build()
                val jsonStr = Http.get().newCall(req).execute().use { res ->
                    if (!res.isSuccessful) throw Exception("HTTP ${res.code}")
                    res.body?.string().orEmpty()
                }
                val films = parseFilmsList(jsonStr)
                if (films.isNotEmpty()) return@withContext films
            } catch (_: Exception) {}

            // Fallback: Tìm kiếm không dấu trong kho offline
            if (context != null) {
                val cached = loadFallbackFilms(context)
                val qNorm = TextNorm.norm(qTrim)
                return@withContext cached
                    .map { it.film }
                    .filter {
                        TextNorm.norm(it.name).contains(qNorm) ||
                                TextNorm.norm(it.originalName).contains(qNorm) ||
                                TextNorm.norm(it.casts).contains(qNorm) ||
                                TextNorm.norm(it.director).contains(qNorm) ||
                                it.categories.any { c -> TextNorm.norm(c).contains(qNorm) }
                    }
            }
            emptyList()
        }

    /**
     * Parse danh sách phim từ JSON response của phim.nguonc.com
     */
    fun parseFilmsList(jsonStr: String): List<NguoncFilm> {
        val list = mutableListOf<NguoncFilm>()
        try {
            val root = JSONObject(jsonStr)
            val items = root.optJSONArray("items") ?: return emptyList()
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                list.add(parseFilmObject(obj))
            }
        } catch (_: Exception) {}
        return list
    }

    /**
     * Parse chi tiết phim và tập phim từ JSON response của /api/film/{slug}
     */
    fun parseFilmDetail(jsonStr: String): NguoncFilmDetail? {
        try {
            val root = JSONObject(jsonStr)
            val movieObj = root.optJSONObject("movie") ?: return null
            val film = parseFilmObject(movieObj)

            val servers = mutableListOf<NguoncServer>()
            val episodesArr = movieObj.optJSONArray("episodes")
            if (episodesArr != null) {
                for (s in 0 until episodesArr.length()) {
                    val srvObj = episodesArr.optJSONObject(s) ?: continue
                    val serverName = srvObj.optString("server_name", "Server $s")
                    val itemsArr = srvObj.optJSONArray("items") ?: JSONArray()
                    val episodeItems = mutableListOf<NguoncEpisodeItem>()
                    for (e in 0 until itemsArr.length()) {
                        val epObj = itemsArr.optJSONObject(e) ?: continue
                        val name = epObj.optString("name", "Tập ${e + 1}")
                        val epSlug = epObj.optString("slug", "tap-${e + 1}")
                        val embed = epObj.optString("embed", "")
                        if (embed.isNotBlank()) {
                            episodeItems.add(NguoncEpisodeItem(name = name, slug = epSlug, embed = embed))
                        }
                    }
                    if (episodeItems.isNotEmpty()) {
                        servers.add(NguoncServer(serverName = serverName, items = episodeItems))
                    }
                }
            }
            return NguoncFilmDetail(film = film, episodes = servers)
        } catch (_: Exception) {
            return null
        }
    }

    private fun parseFilmObject(obj: JSONObject): NguoncFilm {
        val catList = mutableListOf<String>()
        val catObj = obj.opt("category")
        if (catObj is JSONObject) {
            val keys = catObj.keys()
            while (keys.hasNext()) {
                val groupObj = catObj.optJSONObject(keys.next())
                val listArr = groupObj?.optJSONArray("list")
                if (listArr != null) {
                    for (c in 0 until listArr.length()) {
                        val itemObj = listArr.optJSONObject(c)
                        val catName = itemObj?.optString("name").orEmpty()
                        if (catName.isNotBlank()) catList.add(catName)
                    }
                }
            }
        } else if (catObj is JSONArray) {
            for (c in 0 until catObj.length()) {
                val item = catObj.opt(c)
                if (item is String) catList.add(item)
                else if (item is JSONObject) {
                    val n = item.optString("name", "")
                    if (n.isNotBlank()) catList.add(n)
                }
            }
        }

        val catsFromProp = obj.optJSONArray("categories")
        if (catsFromProp != null) {
            for (c in 0 until catsFromProp.length()) {
                val name = catsFromProp.optString(c, "")
                if (name.isNotBlank() && !catList.contains(name)) catList.add(name)
            }
        }

        return NguoncFilm(
            id = obj.optString("id", ""),
            name = obj.optString("name", ""),
            originalName = obj.optString("original_name", ""),
            slug = obj.optString("slug", ""),
            year = obj.optString("year", ""),
            description = obj.optString("description", ""),
            totalEpisodes = obj.opt("total_episodes")?.toString().orEmpty(),
            currentEpisode = obj.optString("current_episode", ""),
            time = obj.optString("time", ""),
            quality = obj.optString("quality", "HD"),
            language = obj.optString("language", "Vietsub"),
            director = obj.optString("director", ""),
            casts = obj.optString("casts", ""),
            categories = catList,
            thumbUrl = obj.optString("thumb_url", ""),
            posterUrl = obj.optString("poster_url", "")
        )
    }

    /**
     * Nạp danh sách phim dự phòng từ assets/fallback_nguonc_films.json
     */
    fun loadFallbackFilms(context: Context): List<NguoncFilmDetail> {
        fallbackCache?.let { return it }
        synchronized(this) {
            fallbackCache?.let { return it }
            val list = mutableListOf<NguoncFilmDetail>()
            try {
                val jsonStr = context.assets.open("fallback_nguonc_films.json").bufferedReader().use { it.readText() }
                val rootArr = JSONArray(jsonStr)
                for (i in 0 until rootArr.length()) {
                    val mObj = rootArr.optJSONObject(i) ?: continue
                    val film = parseFilmObject(mObj)
                    val srvList = mutableListOf<NguoncServer>()
                    val epArr = mObj.optJSONArray("episodes")
                    if (epArr != null) {
                        for (s in 0 until epArr.length()) {
                            val sObj = epArr.optJSONObject(s) ?: continue
                            val sName = sObj.optString("server_name", "Vietsub #1")
                            val itArr = sObj.optJSONArray("items") ?: JSONArray()
                            val items = mutableListOf<NguoncEpisodeItem>()
                            for (e in 0 until itArr.length()) {
                                val itObj = itArr.optJSONObject(e) ?: continue
                                val name = itObj.optString("name", "")
                                val slug = itObj.optString("slug", "")
                                val embed = itObj.optString("embed", "")
                                if (embed.isNotBlank()) {
                                    items.add(NguoncEpisodeItem(name = name, slug = slug, embed = embed))
                                }
                            }
                            if (items.isNotEmpty()) {
                                srvList.add(NguoncServer(serverName = sName, items = items))
                            }
                        }
                    }
                    list.add(NguoncFilmDetail(film = film, episodes = srvList))
                }
            } catch (_: Exception) {}
            fallbackCache = list
            return list
        }
    }
}
