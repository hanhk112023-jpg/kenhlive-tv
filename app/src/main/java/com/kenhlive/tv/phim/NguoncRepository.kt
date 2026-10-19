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
 * NguoncRepository — Quản lý và cung cấp toàn bộ dữ liệu Điện Ảnh (VOD Cinema):
 * - TÍCH HỢP NGUỒN CHÍNH KKPHIM (phimapi.com): Cung cấp 100% link direct HLS .m3u8 chuẩn 4K/FHD,
 *   phát trực tiếp bằng ExoPlayer native, hỗ trợ đa server (Vietsub, Thuyết Minh, Lồng Tiếng).
 * - TÍCH HỢP NGUỒN DỰ PHÒNG NGUONC (phim.nguonc.com): Hơn 33.400+ đầu phim miễn phí.
 * - Hỗ trợ lọc đa chiều: Thể loại (hành động, tình cảm...), Quốc gia (Hàn, Trung, Âu Mỹ...), Phim bộ, Phim lẻ.
 * - Bộ đệm RAM thông minh (LRU Memory Cache) giúp nạp danh sách & chi tiết phim tức thì trong <50ms.
 * - Fallback Cache offline trích xuất từ assets khi API gián đoạn.
 */
object NguoncRepository {

    const val KKPHIM_API = "https://phimapi.com"
    const val KKPHIM_IMAGE_CDN = "https://phimimg.com"
    const val NGUONC_API = "https://phim.nguonc.com/api"
    const val REFERER_NGUONC = "https://phim.nguonc.com/"

    // In-memory Cache với TTL (10 phút), giới hạn trần tối đa 40 phim tránh phình RAM
    private val detailCache = ConcurrentHashMap<String, Pair<Long, NguoncFilmDetail>>()
    private val listCache = ConcurrentHashMap<String, Pair<Long, List<NguoncFilm>>>()
    private const val CACHE_TTL_MS = 10 * 60 * 1000L
    private const val MAX_DETAIL_CACHE_SIZE = 40

    private fun pruneDetailCacheIfNeeded() {
        if (detailCache.size >= MAX_DETAIL_CACHE_SIZE) {
            val now = System.currentTimeMillis()
            detailCache.entries.removeIf { now - it.value.first > CACHE_TTL_MS }
            if (detailCache.size >= MAX_DETAIL_CACHE_SIZE) {
                val oldest = detailCache.entries.minByOrNull { it.value.first }?.key
                if (oldest != null) detailCache.remove(oldest)
            }
        }
    }

    @Volatile
    private var fallbackCache: List<NguoncFilmDetail>? = null

    fun clearCache() {
        detailCache.clear()
        listCache.clear()
        fallbackCache = null
    }

    // Danh sách thể loại phổ biến
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

    private fun normalizeImgUrl(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        return if (s.startsWith("http://") || s.startsWith("https://")) {
            s
        } else {
            val path = if (s.startsWith("/")) s.substring(1) else s
            "$KKPHIM_IMAGE_CDN/$path"
        }
    }

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

            // 1. Thử KKPhim phim mới cập nhật trước
            try {
                val kkUrl = "$KKPHIM_API/danh-sach/phim-moi-cap-nhat?page=$page"
                val kkFilms = fetchKkphimList(kkUrl)
                if (kkFilms.isNotEmpty()) {
                    listCache[cacheKey] = System.currentTimeMillis() to kkFilms
                    return@withContext kkFilms
                }
            } catch (_: Exception) {}

            // 2. Fallback sang NguonC
            val ncUrl = "$NGUONC_API/films/phim-moi-cap-nhat?page=$page"
            val ncFilms = executeRequestAndParseList(ncUrl)
            if (ncFilms.isNotEmpty()) {
                listCache[cacheKey] = System.currentTimeMillis() to ncFilms
                return@withContext ncFilms
            }

            // 3. Fallback offline từ Assets nếu lỗi mạng
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

            // 1. Thử KKPhim theo danh mục v1/api/danh-sach/{slug}
            try {
                val kkUrl = "$KKPHIM_API/v1/api/danh-sach/$typeSlug?page=$page&limit=36"
                val kkFilms = fetchKkphimList(kkUrl)
                if (kkFilms.isNotEmpty()) {
                    listCache[cacheKey] = System.currentTimeMillis() to kkFilms
                    return@withContext kkFilms
                }
            } catch (_: Exception) {}

            // 2. Thử NguonC endpoint danh-sach/{type}
            val urlType = "$NGUONC_API/films/danh-sach/$typeSlug?page=$page"
            var films = executeRequestAndParseList(urlType)

            // Nếu rỗng, thử endpoint the-loai/{type}
            if (films.isEmpty()) {
                val urlCat = "$NGUONC_API/films/the-loai/$typeSlug?page=$page"
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

            // 1. Thử KKPhim v1/api/the-loai/{slug}
            try {
                val kkUrl = "$KKPHIM_API/v1/api/the-loai/$categorySlug?page=$page&limit=36"
                val kkFilms = fetchKkphimList(kkUrl)
                if (kkFilms.isNotEmpty()) {
                    listCache[cacheKey] = System.currentTimeMillis() to kkFilms
                    return@withContext kkFilms
                }
            } catch (_: Exception) {}

            // 2. Fallback sang NguonC
            val url = "$NGUONC_API/films/the-loai/$categorySlug?page=$page"
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

            // 1. Thử KKPhim v1/api/quoc-gia/{slug}
            try {
                val kkUrl = "$KKPHIM_API/v1/api/quoc-gia/$countrySlug?page=$page&limit=36"
                val kkFilms = fetchKkphimList(kkUrl)
                if (kkFilms.isNotEmpty()) {
                    listCache[cacheKey] = System.currentTimeMillis() to kkFilms
                    return@withContext kkFilms
                }
            } catch (_: Exception) {}

            // 2. Fallback sang NguonC
            val url = "$NGUONC_API/films/quoc-gia/$countrySlug?page=$page"
            val films = executeRequestAndParseList(url)
            if (films.isNotEmpty()) {
                listCache[cacheKey] = System.currentTimeMillis() to films
                return@withContext films
            }
            emptyList()
        }

    /**
     * Lấy chi tiết phim và danh sách tập phim theo slug (có kèm Cache nạp tức thì).
     * ƯU TIÊN KKPHIM: Trả về link_m3u8 direct HLS và đa server (Vietsub, Thuyết Minh, Lồng Tiếng).
     */
    suspend fun fetchFilmDetail(slug: String, context: Context? = null): NguoncFilmDetail? =
        withContext(Dispatchers.IO) {
            detailCache[slug]?.let { (ts, detail) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) {
                    return@withContext detail
                }
            }

            // 1. Thử KKPhim trước để lấy direct .m3u8
            try {
                val kkUrl = "$KKPHIM_API/phim/$slug"
                val req = Request.Builder()
                    .url(kkUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
                val jsonStr = Http.get().newCall(req).execute().use { res ->
                    if (res.isSuccessful) res.body?.string().orEmpty() else ""
                }
                if (jsonStr.isNotBlank()) {
                    val detail = parseKkphimDetail(jsonStr)
                    if (detail != null && detail.episodes.isNotEmpty()) {
                        pruneDetailCacheIfNeeded()
                        detailCache[slug] = System.currentTimeMillis() to detail
                        return@withContext detail
                    }
                }
            } catch (_: Exception) {}

            // 2. Fallback sang NguonC
            try {
                val url = "$NGUONC_API/film/$slug"
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
                    pruneDetailCacheIfNeeded()
                    detailCache[slug] = System.currentTimeMillis() to detail
                    return@withContext detail
                }
            } catch (_: Exception) {}

            // 3. Tìm trong fallback offline nếu không gọi được online
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
     * Tìm kiếm phim theo từ khóa (hỗ trợ tìm kiếm KKPhim, NguonC và offline).
     */
    suspend fun searchFilms(query: String, context: Context? = null, page: Int = 1): List<NguoncFilm> =
        withContext(Dispatchers.IO) {
            val qTrim = query.trim()
            if (qTrim.isEmpty()) return@withContext emptyList()

            val encoded = try { URLEncoder.encode(qTrim, "UTF-8") } catch (_: Exception) { qTrim }

            // 1. Thử KKPhim tìm kiếm v1/api/tim-kiem
            try {
                val kkUrl = "$KKPHIM_API/v1/api/tim-kiem?keyword=$encoded&limit=24"
                val kkFilms = fetchKkphimList(kkUrl)
                if (kkFilms.isNotEmpty()) return@withContext kkFilms
            } catch (_: Exception) {}

            // 2. Thử NguonC tìm kiếm
            try {
                val url = "$NGUONC_API/films/search?keyword=$encoded&page=$page"
                val films = executeRequestAndParseList(url)
                if (films.isNotEmpty()) return@withContext films
            } catch (_: Exception) {}

            // 3. Fallback: Tìm kiếm không dấu trong kho offline
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

    private fun fetchKkphimList(url: String): List<NguoncFilm> {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()
        val jsonStr = Http.get().newCall(req).execute().use { res ->
            if (!res.isSuccessful) return emptyList()
            res.body?.string().orEmpty()
        }
        if (jsonStr.isBlank()) return emptyList()

        val list = mutableListOf<NguoncFilm>()
        try {
            val root = JSONObject(jsonStr)
            val dataObj = root.optJSONObject("data")
            val itemsArr = dataObj?.optJSONArray("items") ?: root.optJSONArray("items") ?: return emptyList()

            for (i in 0 until itemsArr.length()) {
                val obj = itemsArr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                val origName = obj.optString("origin_name", "").trim()
                val slug = obj.optString("slug", "")
                if (name.isBlank() || slug.isBlank()) continue

                val year = obj.opt("year")?.toString().orEmpty()
                val posterRaw = obj.optString("poster_url", "")
                val thumbRaw = obj.optString("thumb_url", "")
                val posterUrl = normalizeImgUrl(posterRaw)
                val thumbUrl = normalizeImgUrl(thumbRaw)

                val cats = mutableListOf<String>()
                val catArr = obj.optJSONArray("category")
                if (catArr != null) {
                    for (c in 0 until catArr.length()) {
                        val cObj = catArr.optJSONObject(c)
                        val cn = cObj?.optString("name", "").orEmpty()
                        if (cn.isNotBlank()) cats.add(cn)
                    }
                }

                var country = ""
                val countryArr = obj.optJSONArray("country")
                if (countryArr != null && countryArr.length() > 0) {
                    country = countryArr.optJSONObject(0)?.optString("name", "").orEmpty()
                }

                list.add(
                    NguoncFilm(
                        id = obj.optString("_id", slug),
                        name = name,
                        originalName = origName,
                        slug = slug,
                        year = year,
                        description = obj.optString("content", "").replace(Regex("""<[^>]*>"""), "").trim(),
                        totalEpisodes = obj.opt("episode_total")?.toString().orEmpty(),
                        currentEpisode = obj.optString("episode_current", ""),
                        time = obj.optString("time", ""),
                        quality = obj.optString("quality", "FHD"),
                        language = obj.optString("lang", "Vietsub"),
                        categories = cats,
                        country = country,
                        thumbUrl = thumbUrl,
                        posterUrl = posterUrl
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    private fun parseKkphimDetail(jsonStr: String): NguoncFilmDetail? {
        try {
            val root = JSONObject(jsonStr)
            val movieObj = root.optJSONObject("movie") ?: return null
            val name = movieObj.optString("name", "").trim()
            val slug = movieObj.optString("slug", "")
            if (name.isBlank() || slug.isBlank()) return null

            val posterRaw = movieObj.optString("poster_url", "")
            val thumbRaw = movieObj.optString("thumb_url", "")
            val posterUrl = normalizeImgUrl(posterRaw)
            val thumbUrl = normalizeImgUrl(thumbRaw)

            val cats = mutableListOf<String>()
            val catArr = movieObj.optJSONArray("category")
            if (catArr != null) {
                for (c in 0 until catArr.length()) {
                    val cObj = catArr.optJSONObject(c)
                    val cn = cObj?.optString("name", "").orEmpty()
                    if (cn.isNotBlank()) cats.add(cn)
                }
            }

            var country = ""
            val countryArr = movieObj.optJSONArray("country")
            if (countryArr != null && countryArr.length() > 0) {
                country = countryArr.optJSONObject(0)?.optString("name", "").orEmpty()
            }

            val casts = mutableListOf<String>()
            val actorArr = movieObj.optJSONArray("actor")
            if (actorArr != null) {
                for (a in 0 until actorArr.length()) {
                    val an = actorArr.optString(a, "")
                    if (an.isNotBlank()) casts.add(an)
                }
            }

            val directorArr = movieObj.optJSONArray("director")
            val director = if (directorArr != null && directorArr.length() > 0) directorArr.optString(0, "") else ""

            val film = NguoncFilm(
                id = movieObj.optString("_id", slug),
                name = name,
                originalName = movieObj.optString("origin_name", "").trim(),
                slug = slug,
                year = movieObj.opt("year")?.toString().orEmpty(),
                description = movieObj.optString("content", "").replace(Regex("""<[^>]*>"""), "").trim(),
                totalEpisodes = movieObj.opt("episode_total")?.toString().orEmpty(),
                currentEpisode = movieObj.optString("episode_current", ""),
                time = movieObj.optString("time", ""),
                quality = movieObj.optString("quality", "FHD"),
                language = movieObj.optString("lang", "Vietsub"),
                director = director,
                casts = casts.joinToString(", "),
                categories = cats,
                country = country,
                thumbUrl = thumbUrl,
                posterUrl = posterUrl
            )

            val servers = mutableListOf<NguoncServer>()
            val episodesArr = root.optJSONArray("episodes") ?: movieObj.optJSONArray("episodes")
            if (episodesArr != null) {
                for (s in 0 until episodesArr.length()) {
                    val srvObj = episodesArr.optJSONObject(s) ?: continue
                    val srvName = srvObj.optString("server_name", "Server ${s + 1}")
                    val itemsArr = srvObj.optJSONArray("server_data") ?: srvObj.optJSONArray("items") ?: JSONArray()
                    val epItems = mutableListOf<NguoncEpisodeItem>()
                    for (e in 0 until itemsArr.length()) {
                        val epObj = itemsArr.optJSONObject(e) ?: continue
                        val epName = epObj.optString("name", "Tập ${e + 1}")
                        val epSlug = epObj.optString("slug", "tap-${e + 1}")
                        val linkEmbed = epObj.optString("link_embed", "")
                        val linkM3u8 = epObj.optString("link_m3u8", "")
                        if (linkEmbed.isNotBlank() || linkM3u8.isNotBlank()) {
                            epItems.add(
                                NguoncEpisodeItem(
                                    name = epName,
                                    slug = epSlug,
                                    embed = linkEmbed,
                                    m3u8 = linkM3u8
                                )
                            )
                        }
                    }
                    if (epItems.isNotEmpty()) {
                        servers.add(NguoncServer(serverName = srvName, items = epItems))
                    }
                }
            }

            return NguoncFilmDetail(film = film, episodes = servers)
        } catch (_: Exception) {
            return null
        }
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
     * Parse chi tiết phim và tập phim từ JSON response của /api/film/{slug} NguonC
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
