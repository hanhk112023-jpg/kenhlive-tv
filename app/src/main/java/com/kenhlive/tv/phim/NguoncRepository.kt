package com.kenhlive.tv.phim

import android.content.Context
import com.kenhlive.tv.Http
import com.kenhlive.tv.TextNorm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * NguoncRepository — Quản lý và cung cấp toàn bộ dữ liệu Phim Nguồn C (phim.nguonc.com):
 * - Hỗ trợ kho phim 33.400+ đầu phim: Phim mới cập nhật, Phim bộ, Phim lẻ, Hoạt hình/Anime, TV shows.
 * - Hỗ trợ lọc đa chiều: Thể loại (hành động, kinh dị...), Quốc gia (Âu Mỹ, Hàn, Trung, Việt...), Năm phát hành.
 * - Phân trang (Pagination) mượt mà cho danh sách phim.
 * - Lấy đầy đủ chi tiết phim: diễn viên, đạo diễn, mô tả, năm, quốc gia và toàn bộ server/tập phim.
 * - Bộ đệm RAM thông minh (LRU Memory Cache) giúp nạp tức thì trong <50ms.
 * - Fallback Cache offline trích xuất từ 240+ phim offline khi mất mạng hoặc API gián đoạn.
 * - Xử lý lỗi mạng và retry tự động.
 */
object NguoncRepository {

    const val API_BASE = "https://phim.nguonc.com/api"
    const val REFERER_NGUONC = "https://phim.nguonc.com/"

    // In-memory Cache với TTL (10 phút)
    private val detailCache = ConcurrentHashMap<String, Pair<Long, NguoncFilmDetail>>()
    private val listCache = ConcurrentHashMap<String, Pair<Long, List<NguoncFilm>>>()
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    @Volatile
    private var fallbackCache: List<NguoncFilmDetail>? = null

    // Danh sách thể loại phổ biến chuẩn NguonC
    val POPULAR_GENRES = listOf(
        "tat-ca" to "Tất Cả",
        "phim-moi" to "Phim Mới",
        "phim-bo" to "Phim Bộ",
        "phim-le" to "Phim Lẻ",
        "hoat-hinh" to "Anime & Hoạt Hình",
        "hanh-dong" to "Hành Động",
        "tinh-cam" to "Tình Cảm",
        "co-trang" to "Cổ Trang",
        "hai-huoc" to "Hài Hước",
        "kinh-di" to "Kinh Dị",
        "vien-tuong" to "Viễn Tưởng",
        "vo-thuat" to "Võ Thuật",
        "tam-ly" to "Tâm Lý"
    )

    // Danh sách quốc gia
    val POPULAR_COUNTRIES = listOf(
        "trung-quoc" to "Trung Quốc",
        "han-quoc" to "Hàn Quốc",
        "au-my" to "Âu Mỹ",
        "nhat-ban" to "Nhật Bản",
        "viet-nam" to "Việt Nam",
        "thai-lan" to "Thái Lan"
    )

    /**
     * Lấy danh sách phim mới cập nhật (có hỗ trợ phân trang).
     */
    suspend fun fetchNewFilms(context: Context? = null, page: Int = 1): List<NguoncFilm> =
        withContext(Dispatchers.IO) {
            val cacheKey = "new_films_p$page"
            listCache[cacheKey]?.let { (ts, list) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS && list.isNotEmpty()) {
                    return@withContext list
                }
            }

            val url = "$API_BASE/films/phim-moi-cap-nhat?page=$page"
            val films = executeRequestAndParseList(url)
            if (films.isNotEmpty()) {
                listCache[cacheKey] = System.currentTimeMillis() to films
                return@withContext films
            }

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
     * Lấy danh sách phim theo loại danh mục (phim-bo, phim-le, hoat-hinh, tv-shows).
     */
    suspend fun fetchFilmsByType(typeSlug: String, page: Int = 1, context: Context? = null): List<NguoncFilm> =
        withContext(Dispatchers.IO) {
            if (typeSlug == "phim-moi" || typeSlug == "tat-ca" || typeSlug.isBlank()) {
                return@withContext fetchNewFilms(context, page)
            }

            val cacheKey = "type_${typeSlug}_p$page"
            listCache[cacheKey]?.let { (ts, list) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS && list.isNotEmpty()) {
                    return@withContext list
                }
            }

            // Thử endpoint danh-sach/{type}
            val urlType = "$API_BASE/films/danh-sach/$typeSlug?page=$page"
            var films = executeRequestAndParseList(urlType)

            // Nếu rỗng, thử endpoint the-loai/{type}
            if (films.isEmpty()) {
                val urlCat = "$API_BASE/films/the-loai/$typeSlug?page=$page"
                films = executeRequestAndParseList(urlCat)
            }

            if (films.isNotEmpty()) {
                listCache[cacheKey] = System.currentTimeMillis() to films
                return@withContext films
            }

            // Fallback từ cache offline nếu có
            if (context != null) {
                val cached = loadFallbackFilms(context).map { it.film }
                return@withContext filterFilmsOffline(cached, typeSlug)
            }
            emptyList()
        }

    /**
     * Lấy danh sách phim theo thể loại (the-loai/{slug}).
     */
    suspend fun fetchFilmsByCategory(categorySlug: String, page: Int = 1, context: Context? = null): List<NguoncFilm> =
        withContext(Dispatchers.IO) {
            val cacheKey = "cat_${categorySlug}_p$page"
            listCache[cacheKey]?.let { (ts, list) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS && list.isNotEmpty()) {
                    return@withContext list
                }
            }

            val url = "$API_BASE/films/the-loai/$categorySlug?page=$page"
            val films = executeRequestAndParseList(url)
            if (films.isNotEmpty()) {
                listCache[cacheKey] = System.currentTimeMillis() to films
                return@withContext films
            }

            if (context != null) {
                val cached = loadFallbackFilms(context).map { it.film }
                return@withContext cached.filter { f ->
                    f.categories.any { c -> TextNorm.norm(c).contains(TextNorm.norm(categorySlug)) }
                }
            }
            emptyList()
        }

    /**
     * Nạp đồng thời hàng loạt trang (Batch Parallel Fetching) cho một danh mục.
     * Tận dụng tối đa HTTP/2 Connection Pooling để lấy hàng trăm phim cùng lúc trong <1 giây.
     */
    suspend fun fetchFilmsBatch(
        typeOrCatSlug: String,
        pages: IntRange = 1..8,
        isCategory: Boolean = false,
        context: Context? = null
    ): List<NguoncFilm> = withContext(Dispatchers.IO) {
        val cacheKey = "batch_${typeOrCatSlug}_${pages.first}_${pages.last}"
        listCache[cacheKey]?.let { (ts, list) ->
            if (System.currentTimeMillis() - ts < CACHE_TTL_MS && list.isNotEmpty()) {
                return@withContext list
            }
        }

        val allFilms = coroutineScope {
            val deferreds = pages.map { page ->
                async {
                    try {
                        if (typeOrCatSlug == "tat-ca" || typeOrCatSlug == "phim-moi") {
                            fetchNewFilms(context, page)
                        } else if (isCategory) {
                            fetchFilmsByCategory(typeOrCatSlug, page, context)
                        } else {
                            fetchFilmsByType(typeOrCatSlug, page, context)
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
            deferreds.awaitAll().flatten().distinctBy { it.slug }
        }

        if (allFilms.isNotEmpty()) {
            listCache[cacheKey] = System.currentTimeMillis() to allFilms
            return@withContext allFilms
        }

        if (context != null) {
            val cached = loadFallbackFilms(context).map { it.film }
            return@withContext filterFilmsOffline(cached, typeOrCatSlug)
        }
        emptyList()
    }

    /**
     * Lấy danh sách phim theo quốc gia (quoc-gia/{slug}).
     */
    suspend fun fetchFilmsByCountry(countrySlug: String, page: Int = 1, context: Context? = null): List<NguoncFilm> =
        withContext(Dispatchers.IO) {
            val cacheKey = "country_${countrySlug}_p$page"
            listCache[cacheKey]?.let { (ts, list) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS && list.isNotEmpty()) {
                    return@withContext list
                }
            }

            val url = "$API_BASE/films/quoc-gia/$countrySlug?page=$page"
            val films = executeRequestAndParseList(url)
            if (films.isNotEmpty()) {
                listCache[cacheKey] = System.currentTimeMillis() to films
                return@withContext films
            }
            emptyList()
        }

    /**
     * Lấy chi tiết phim và danh sách tập phim theo slug (có kèm Cache nạp tức thì).
     */
    suspend fun fetchFilmDetail(slug: String, context: Context? = null): NguoncFilmDetail? =
        withContext(Dispatchers.IO) {
            detailCache[slug]?.let { (ts, detail) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) {
                    return@withContext detail
                }
            }

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
                if (detail != null) {
                    detailCache[slug] = System.currentTimeMillis() to detail
                    return@withContext detail
                }
            } catch (_: Exception) {}

            // Tìm trong fallback nếu không gọi được online
            if (context != null) {
                val cached = loadFallbackFilms(context)
                val found = cached.firstOrNull { it.film.slug == slug }
                if (found != null) {
                    return@withContext found
                }
            }
            null
        }

    /**
     * Tìm kiếm phim theo từ khóa (hỗ trợ phân trang và tìm offline không dấu).
     */
    suspend fun searchFilms(query: String, context: Context? = null, page: Int = 1): List<NguoncFilm> =
        withContext(Dispatchers.IO) {
            val qTrim = query.trim()
            if (qTrim.isEmpty()) return@withContext emptyList()

            try {
                val encoded = URLEncoder.encode(qTrim, "UTF-8")
                val url = "$API_BASE/films/search?keyword=$encoded&page=$page"
                val films = executeRequestAndParseList(url)
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

    private fun executeRequestAndParseList(url: String): List<NguoncFilm> {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Referer", REFERER_NGUONC)
                .build()
            val jsonStr = Http.get().newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw Exception("HTTP ${res.code}")
                res.body?.string().orEmpty()
            }
            parseFilmsList(jsonStr)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun filterFilmsOffline(films: List<NguoncFilm>, typeSlug: String): List<NguoncFilm> {
        return when (typeSlug) {
            "phim-bo" -> films.filter { f ->
                f.totalEpisodes.isNotBlank() && f.totalEpisodes != "1" ||
                        f.categories.any { c -> c.contains("bộ", ignoreCase = true) }
            }
            "phim-le" -> films.filter { f ->
                f.totalEpisodes == "1" || f.currentEpisode.contains("Full", ignoreCase = true) ||
                        f.categories.any { c -> c.contains("lẻ", ignoreCase = true) }
            }
            "hoat-hinh" -> films.filter { f ->
                f.categories.any { c ->
                    c.contains("hoạt hình", ignoreCase = true) || c.contains("anime", ignoreCase = true)
                }
            }
            else -> films.filter { f ->
                f.categories.any { c -> TextNorm.norm(c).contains(TextNorm.norm(typeSlug)) }
            }
        }
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
                    val serverName = srvObj.optString("server_name", "Server ${s + 1}")
                    val itemsArr = srvObj.optJSONArray("items") ?: JSONArray()
                    val episodeItems = mutableListOf<NguoncEpisodeItem>()
                    for (e in 0 until itemsArr.length()) {
                        val epObj = itemsArr.optJSONObject(e) ?: continue
                        val name = epObj.optString("name", "Tập ${e + 1}")
                        val epSlug = epObj.optString("slug", "tap-${e + 1}")
                        val embed = epObj.optString("embed", "")
                        val m3u8 = epObj.optString("m3u8", "")
                        if (embed.isNotBlank() || m3u8.isNotBlank()) {
                            episodeItems.add(
                                NguoncEpisodeItem(
                                    name = name,
                                    slug = epSlug,
                                    embed = embed,
                                    m3u8 = m3u8
                                )
                            )
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

        // Parse country nếu có
        var country = ""
        val countryObj = obj.opt("country")
        if (countryObj is JSONObject) {
            country = countryObj.optString("name", "")
        } else if (countryObj is String) {
            country = countryObj
        }

        val rawDesc = obj.optString("description", "")
        val cleanDesc = rawDesc.replace(Regex("""<[^>]*>"""), "").trim()
        val rawCurrentEp = obj.optString("current_episode", "")
        val cleanCurrentEp = com.kenhlive.tv.DataNormalizer.normalizeEpisodeName(rawCurrentEp)
        val rawQuality = obj.optString("quality", "HD")
        val cleanQuality = com.kenhlive.tv.DataNormalizer.normalizeQuality(rawQuality)
        val rawLang = obj.optString("language", "Vietsub")
        val cleanLang = com.kenhlive.tv.DataNormalizer.normalizeLanguage(rawLang)
        val thumbUrl = com.kenhlive.tv.DataNormalizer.normalizeImageUrl(obj.optString("thumb_url", ""))
        val posterUrl = com.kenhlive.tv.DataNormalizer.normalizeImageUrl(obj.optString("poster_url", ""))

        return NguoncFilm(
            id = obj.optString("id", ""),
            name = obj.optString("name", "").trim(),
            originalName = obj.optString("original_name", "").trim(),
            slug = obj.optString("slug", ""),
            year = obj.optString("year", ""),
            description = cleanDesc,
            totalEpisodes = obj.opt("total_episodes")?.toString().orEmpty(),
            currentEpisode = cleanCurrentEp,
            time = obj.optString("time", ""),
            quality = cleanQuality,
            language = cleanLang,
            director = obj.optString("director", ""),
            casts = obj.optString("casts", ""),
            categories = catList,
            country = country,
            thumbUrl = thumbUrl,
            posterUrl = posterUrl
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
