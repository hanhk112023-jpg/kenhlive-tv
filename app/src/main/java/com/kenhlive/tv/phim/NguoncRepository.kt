package com.kenhlive.tv.phim

import android.content.Context
import com.kenhlive.tv.Http
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
 * - NGUỒN DUY NHẤT KKPHIM (phimapi.com): Cung cấp 100% link direct HLS .m3u8 chuẩn 4K/FHD,
 *   phát trực tiếp bằng ExoPlayer native, hỗ trợ đa server (Vietsub, Thuyết Minh, Lồng Tiếng).
 *   (Nguồn dự phòng NguonC đã bị gỡ bỏ vì kém ổn định — khi KKPhim lỗi, các hàm trả
 *   rỗng/null trung thực để UI hiển thị trạng thái lỗi thay vì dữ liệu của nguồn khác.)
 * - Hỗ trợ lọc đa chiều: Thể loại (hành động, tình cảm...), Quốc gia (Hàn, Trung, Âu Mỹ...), Phim bộ, Phim lẻ.
 * - Bộ đệm RAM thông minh (LRU Memory Cache) giúp nạp danh sách & chi tiết phim tức thì trong <50ms.
 */
object NguoncRepository {

    const val KKPHIM_API = "https://phimapi.com"
    const val KKPHIM_IMAGE_CDN = "https://phimimg.com"

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

    fun clearCache() {
        detailCache.clear()
        listCache.clear()
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

    /** Khoá khử trùng slug: bỏ khoảng trắng thừa, chữ thường, bỏ '/' cuối — chỉ dùng để so trùng, không đổi slug gốc khi gọi API. */
    private fun slugKey(raw: String): String = raw.trim().lowercase().trimEnd('/')

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
     * Nạp 1 trang danh sách KKPhim (có cache RAM). KHÁC các hàm fetch* công khai:
     * lỗi mạng / cầu dao mở từ Http.executeRequest được NÉM ra ngoài thay vì nuốt
     * thành list rỗng, để nơi gọi phân biệt được "lỗi tải" với "trang rỗng thật"
     * (trang cuối / danh mục không có phim). Trang rỗng thật vẫn trả emptyList().
     */
    private fun fetchListPage(cacheKey: String, url: String): List<NguoncFilm> {
        listCache[cacheKey]?.let { (ts, list) ->
            if (System.currentTimeMillis() - ts < CACHE_TTL_MS && list.isNotEmpty()) {
                return list
            }
        }
        val films = fetchKkphimList(url)
        if (films.isNotEmpty()) {
            listCache[cacheKey] = System.currentTimeMillis() to films
        }
        return films
    }

    /**
     * Lấy danh sách phim mới cập nhật (có hỗ trợ phân trang).
     */
    suspend fun fetchNewFilms(context: Context? = null, page: Int = 1): List<NguoncFilm> =
        withContext(Dispatchers.IO) {
            // KKPhim phim mới cập nhật; lỗi -> rỗng như hành vi cũ
            try {
                fetchListPage("new_films_p$page", "$KKPHIM_API/danh-sach/phim-moi-cap-nhat?page=$page")
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * Lấy danh sách phim theo loại danh mục (phim-bo, phim-le, hoat-hinh, tv-shows).
     */
    suspend fun fetchFilmsByType(typeSlug: String, page: Int = 1, context: Context? = null): List<NguoncFilm> =
        withContext(Dispatchers.IO) {
            if (typeSlug == "phim-moi" || typeSlug == "tat-ca" || typeSlug.isBlank()) {
                return@withContext fetchNewFilms(context, page)
            }

            // KKPhim theo danh mục v1/api/danh-sach/{slug}; lỗi -> rỗng như hành vi cũ
            try {
                fetchListPage("type_${typeSlug}_p$page", "$KKPHIM_API/v1/api/danh-sach/$typeSlug?page=$page&limit=36")
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * Biến thể nghiêm của [fetchFilmsByType] cho PHÂN TRANG LƯỚI: lỗi tải được ném ra
     * ngoài (không nuốt thành rỗng) để UI không nhầm lỗi tạm thời với trang cuối thật.
     */
    suspend fun fetchFilmsByTypeStrict(typeSlug: String, page: Int = 1): List<NguoncFilm> =
        withContext(Dispatchers.IO) {
            if (typeSlug == "phim-moi" || typeSlug == "tat-ca" || typeSlug.isBlank()) {
                return@withContext fetchListPage("new_films_p$page", "$KKPHIM_API/danh-sach/phim-moi-cap-nhat?page=$page")
            }
            fetchListPage("type_${typeSlug}_p$page", "$KKPHIM_API/v1/api/danh-sach/$typeSlug?page=$page&limit=36")
        }

    /**
     * Lấy danh sách phim theo thể loại (the-loai/{slug}).
     */
    suspend fun fetchFilmsByCategory(categorySlug: String, page: Int = 1, context: Context? = null): List<NguoncFilm> =
        withContext(Dispatchers.IO) {
            // KKPhim v1/api/the-loai/{slug}; lỗi -> rỗng như hành vi cũ
            try {
                fetchListPage("cat_${categorySlug}_p$page", "$KKPHIM_API/v1/api/the-loai/$categorySlug?page=$page&limit=36")
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * Biến thể nghiêm của [fetchFilmsByCategory] cho PHÂN TRANG LƯỚI: lỗi tải được ném
     * ra ngoài (không nuốt thành rỗng) để UI phân biệt lỗi với trang cuối thật.
     */
    suspend fun fetchFilmsByCategoryStrict(categorySlug: String, page: Int = 1): List<NguoncFilm> =
        withContext(Dispatchers.IO) {
            fetchListPage("cat_${categorySlug}_p$page", "$KKPHIM_API/v1/api/the-loai/$categorySlug?page=$page&limit=36")
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
            deferreds.awaitAll().flatten().distinctBy { slugKey(it.slug) }
        }

        if (allFilms.isNotEmpty()) {
            listCache[cacheKey] = System.currentTimeMillis() to allFilms
            return@withContext allFilms
        }

        emptyList()
    }

    /**
     * Lấy danh sách phim theo quốc gia (quoc-gia/{slug}).
     */
    suspend fun fetchFilmsByCountry(countrySlug: String, page: Int = 1, context: Context? = null): List<NguoncFilm> =
        withContext(Dispatchers.IO) {
            // KKPhim v1/api/quoc-gia/{slug}; lỗi -> rỗng như hành vi cũ
            try {
                fetchListPage("country_${countrySlug}_p$page", "$KKPHIM_API/v1/api/quoc-gia/$countrySlug?page=$page&limit=36")
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * Biến thể nghiêm của [fetchFilmsByCountry] cho PHÂN TRANG LƯỚI: lỗi tải được ném
     * ra ngoài (không nuốt thành rỗng) để UI phân biệt lỗi với trang cuối thật.
     */
    suspend fun fetchFilmsByCountryStrict(countrySlug: String, page: Int = 1): List<NguoncFilm> =
        withContext(Dispatchers.IO) {
            fetchListPage("country_${countrySlug}_p$page", "$KKPHIM_API/v1/api/quoc-gia/$countrySlug?page=$page&limit=36")
        }

    /**
     * Lấy chi tiết phim và danh sách tập phim theo slug (có kèm Cache nạp tức thì).
     * Nguồn KKPHIM: Trả về link_m3u8 direct HLS và đa server (Vietsub, Thuyết Minh, Lồng Tiếng).
     */
    suspend fun fetchFilmDetail(slug: String, context: Context? = null): NguoncFilmDetail? =
        withContext(Dispatchers.IO) {
            detailCache[slug]?.let { (ts, detail) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) {
                    return@withContext detail
                }
            }

            // KKPhim để lấy direct .m3u8
            try {
                val kkUrl = "$KKPHIM_API/phim/$slug"
                val req = Request.Builder()
                    .url(kkUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
                // Qua Http.executeRequest: có ghi ApiHealth + tôn trọng cầu dao (ném lỗi -> trả null)
                val jsonStr = Http.executeRequest(req)
                if (jsonStr.isNotBlank()) {
                    val detail = parseKkphimDetail(jsonStr)
                    if (detail != null && detail.episodes.isNotEmpty()) {
                        pruneDetailCacheIfNeeded()
                        detailCache[slug] = System.currentTimeMillis() to detail
                        return@withContext detail
                    }
                }
            } catch (_: Exception) {}

            null
        }

    /**
     * Tìm kiếm phim theo từ khóa (KKPhim v1/api/tim-kiem).
     */
    suspend fun searchFilms(query: String, context: Context? = null, page: Int = 1): List<NguoncFilm> =
        withContext(Dispatchers.IO) {
            val qTrim = query.trim()
            if (qTrim.isEmpty()) return@withContext emptyList()

            val encoded = try { URLEncoder.encode(qTrim, "UTF-8") } catch (_: Exception) { qTrim }

            // KKPhim tìm kiếm v1/api/tim-kiem
            try {
                val kkUrl = "$KKPHIM_API/v1/api/tim-kiem?keyword=$encoded&limit=24"
                val kkFilms = fetchKkphimList(kkUrl)
                if (kkFilms.isNotEmpty()) return@withContext kkFilms
            } catch (_: Exception) {}

            emptyList()
        }

    private fun fetchKkphimList(url: String): List<NguoncFilm> {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()
        // Qua Http.executeRequest (ghi ApiHealth + cầu dao); lỗi ném ra cho nơi gọi xử lý.
        // Mọi điểm gọi fetchKkphimList đều đã nằm trong try/catch nên hành vi tổng thể không đổi.
        val jsonStr = Http.executeRequest(req)
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
                        // Không bịa mặc định: API thiếu trường nào thì để trống, UI sẽ ẩn
                        quality = obj.optString("quality", ""),
                        language = obj.optString("lang", ""),
                        categories = cats,
                        country = country,
                        thumbUrl = thumbUrl,
                        posterUrl = posterUrl
                    )
                )
            }
        } catch (_: Exception) {}
        return list.distinctBy { slugKey(it.slug) }
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
                // Không bịa mặc định: API thiếu trường nào thì để trống, UI sẽ ẩn
                quality = movieObj.optString("quality", ""),
                language = movieObj.optString("lang", ""),
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
}
