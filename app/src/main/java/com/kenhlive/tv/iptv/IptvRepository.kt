package com.kenhlive.tv.iptv

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import com.kenhlive.tv.Http
import java.util.Locale

data class IptvChannel(
    val id: String,
    val name: String,
    val group: String,
    val logo: String,
    val url: String,
    val isVn: Boolean = false,
    val country: String = "",
    val isCustom: Boolean = false
)

object IptvRepository {
    private const val PREFS = "iptv_prefs"
    private const val KEY_CACHE_VN = "m3u_cache_vn"
    private const val KEY_CACHE_SPORTS = "m3u_cache_sports"
    private const val KEY_CACHE_CUSTOM = "m3u_cache_custom"
    private const val KEY_CUSTOM_M3U_URL = "custom_m3u_url"
    private const val KEY_LAST_UPDATE = "m3u_last_update"
    private const val FILE_CACHE_PARSED = "cache_iptv_parsed.json"
    private const val ASSET_FALLBACK_VN = "fallback_vn.m3u"

    // Không còn playlist M3U mặc định: người dùng toàn quyền tự thêm link
    // của riêng họ. Trước đây app tự gán sẵn link mẫu tinyurl.com/vmt47 cho
    // mọi người chưa từng cài đặt — giờ mặc định là KHÔNG có playlist custom.

    // Bộ nhớ RAM tĩnh để PlayerActivity mở sidebar chuyển kênh tức thì
    var currentChannels: List<IptvChannel> = emptyList()

    /**
     * Kết quả nạp playlist M3U tùy chỉnh ở lần loadChannels gần nhất —
     * null = không có link custom / OK; chuỗi = lý do thất bại để UI báo user
     * thay vì im lặng chỉ hiện kênh mặc định.
     */
    var lastCustomLoadError: String? = null
        private set

    /** Lấy URL danh sách M3U tùy chỉnh của người dùng — rỗng = chưa cài đặt (không có mặc định). */
    fun getCustomM3uUrl(context: Context): String {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getString(KEY_CUSTOM_M3U_URL, "") ?: ""
    }

    /** Lưu URL danh sách M3U tùy chỉnh và xóa cache để nạp mới */
    fun setCustomM3uUrl(context: Context, url: String) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit()
            .putString(KEY_CUSTOM_M3U_URL, url.trim())
            .remove(KEY_CACHE_CUSTOM)
            .remove(KEY_LAST_UPDATE)
            .apply()
        currentChannels = emptyList()
        try {
            java.io.File(context.cacheDir, FILE_CACHE_PARSED).delete()
        } catch (_: Exception) {}
    }

    /** Đọc nhanh danh sách kênh đã parse từ ổ đĩa (0s parsing, hiển thị tức thì) */
    fun getCachedChannelsDisk(context: Context): List<IptvChannel>? {
        if (currentChannels.isNotEmpty()) return currentChannels
        return try {
            val file = java.io.File(context.cacheDir, FILE_CACHE_PARSED)
            if (file.exists() && file.length() > 0) {
                val jsonArr = org.json.JSONArray(file.readText())
                val list = mutableListOf<IptvChannel>()
                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.optJSONObject(i) ?: continue
                    list.add(
                        IptvChannel(
                            id = obj.optString("id", ""),
                            name = obj.optString("name", ""),
                            group = obj.optString("group", ""),
                            logo = obj.optString("logo", ""),
                            url = obj.optString("url", ""),
                            isVn = obj.optBoolean("isVn", false),
                            country = obj.optString("country", ""),
                            isCustom = obj.optBoolean("isCustom", false)
                        )
                    )
                }
                if (list.isNotEmpty()) {
                    currentChannels = list
                    list
                } else null
            } else null
        } catch (_: Exception) { null }
    }

    private fun saveParsedChannelsDisk(context: Context, list: List<IptvChannel>) {
        try {
            val jsonArr = org.json.JSONArray()
            for (ch in list) {
                val obj = org.json.JSONObject().apply {
                    put("id", ch.id)
                    put("name", ch.name)
                    put("group", ch.group)
                    put("logo", ch.logo)
                    put("url", ch.url)
                    put("isVn", ch.isVn)
                    put("country", ch.country)
                    put("isCustom", ch.isCustom)
                }
                jsonArr.put(obj)
            }
            val file = java.io.File(context.cacheDir, FILE_CACHE_PARSED)
            file.writeText(jsonArr.toString())
        } catch (_: Exception) { }
    }

    // Nguồn cố định: Việt Nam & Thể thao từ iptv-org
    const val URL_VN = "https://iptv-org.github.io/iptv/countries/vn.m3u"
    const val URL_SPORTS = "https://iptv-org.github.io/iptv/categories/sports.m3u"

    private val KNOWN_HTTPS_HOSTS = setOf(
        "tv360.vn", "live.canthotv.vn", "liveh12.vtvprime.vn", "liveh34.vtvprime.vn",
        "1011154949.vnns.net", "freem3u.xyz", "iptv-org.github.io", "epgshare01.online",
        "phimapi.com", "phimimg.com", "vnres.co", "json.vnres.co"
    )

    private val warnedUnknownStreamHosts = mutableSetOf<String>()

    /** Nâng http:// lên https:// chỉ cho host trong KNOWN_HTTPS_HOSTS, còn lại giữ nguyên. */
    internal fun upgradeToHttps(url: String): String {
        if (!url.startsWith("http://", ignoreCase = true)) return url
        val host = try {
            java.net.URI(url).host?.lowercase(Locale.ROOT).orEmpty()
        } catch (_: Exception) { "" }
        if (host.isEmpty()) return url
        val known = KNOWN_HTTPS_HOSTS.any { host == it || host.endsWith(".$it") }
        return if (known) "https://" + url.substringAfter("://") else url
    }

    // Danh sách mã quốc gia Châu Âu & thương hiệu thể thao lớn
    private val EU_COUNTRIES = setOf(
        "UK", "GB", "ES", "FR", "DE", "IT", "PT", "NL", "BE", "CH", "AT", "SE", "NO", "DK", "FI", "PL", "RO", "CZ"
    )

    suspend fun loadChannels(context: Context, forceRefresh: Boolean = false): List<IptvChannel> = withContext(Dispatchers.IO) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastUpdate = sp.getLong(KEY_LAST_UPDATE, 0L)
        val isExpired = System.currentTimeMillis() - lastUpdate > 3600_000L * 3 // Tự động làm mới mỗi 3 tiếng

        var cachedVn = sp.getString(KEY_CACHE_VN, null)
        var cachedSports = sp.getString(KEY_CACHE_SPORTS, null)
        var cachedCustom = sp.getString(KEY_CACHE_CUSTOM, null)
        val customUrl = getCustomM3uUrl(context)

        if (forceRefresh || isExpired || cachedVn == null || cachedSports == null || (customUrl.isNotBlank() && cachedCustom == null)) {
            coroutineScope {
                val jobVn = async { fetchUrl(URL_VN) }
                val jobSports = async { fetchUrl(URL_SPORTS) }
                val jobCustom = if (customUrl.isNotBlank()) async { fetchUrl(customUrl) } else null

                val resVn = jobVn.await()
                val resSports = jobSports.await()
                val resCustom = jobCustom?.await()

                val editor = sp.edit()
                var anyFetchOk = false
                if (!resVn.isNullOrBlank()) {
                    cachedVn = resVn
                    editor.putString(KEY_CACHE_VN, resVn)
                    anyFetchOk = true
                }
                if (!resSports.isNullOrBlank()) {
                    cachedSports = resSports
                    editor.putString(KEY_CACHE_SPORTS, resSports)
                    anyFetchOk = true
                }
                if (!resCustom.isNullOrBlank()) {
                    cachedCustom = resCustom
                    editor.putString(KEY_CACHE_CUSTOM, resCustom)
                    anyFetchOk = true
                }
                if (anyFetchOk) {
                    editor.putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                }
                editor.apply()
            }
        }

        if (cachedVn.isNullOrBlank()) {
            cachedVn = try {
                context.assets.open(ASSET_FALLBACK_VN).bufferedReader().use { it.readText() }
            } catch (_: Exception) { null }
        }

        val result = mutableListOf<IptvChannel>()

        // 1. Nạp danh sách kênh Việt Nam & TV360 được tuyển chọn kỹ càng từ assets
        val localVnText = try {
            context.assets.open(ASSET_FALLBACK_VN).bufferedReader().use { it.readText() }
        } catch (_: Exception) { null }

        if (!localVnText.isNullOrBlank()) {
            val curatedVn = parseM3u(localVnText, defaultGroup = "Việt Nam", isVn = true, filterSports = false)
            result.addAll(curatedVn)
        }

        // 2. Bổ sung các kênh mở rộng từ nguồn online (nếu có và không trùng).
        if (!cachedVn.isNullOrBlank() && cachedVn != localVnText) {
            val onlineVn = parseM3u(cachedVn!!, defaultGroup = "Việt Nam", isVn = true, filterSports = false)
            val seenNames = result.map { it.name.lowercase(Locale.ROOT) }.toHashSet()
            val seenUrls = result.map { it.url }.toHashSet()
            for (ch in onlineVn) {
                val nameKey = ch.name.lowercase(Locale.ROOT)
                if (!seenNames.contains(nameKey) && !seenUrls.contains(ch.url)) {
                    result.add(ch)
                    seenNames.add(nameKey)
                    seenUrls.add(ch.url)
                }
            }
        }

        val sortedVn = result.sortedWith(
            compareBy(
                { ch ->
                    val g = ch.group.lowercase(Locale.ROOT)
                    val n = ch.name.lowercase(Locale.ROOT)
                    when {
                        g.contains("vtv") || n.contains("vtv") -> 0
                        g.contains("tv360") || n.contains("360") -> 1
                        g.contains("htv") || g.contains("vĩnh long") || n.contains("htv") || n.contains("thvl") -> 2
                        g.contains("vtvcab") || g.contains("sctv") -> 3
                        g.contains("quốc gia") || n.contains("antv") || n.contains("qpv") -> 4
                        else -> 5
                    }
                },
                { it.name }
            )
        )

        val finalChannels = mutableListOf<IptvChannel>()
        finalChannels.addAll(sortedVn)

        // 3. Kênh M3U Tùy Chọn của người dùng (chỉ khi chính họ đã nhập link)
        lastCustomLoadError = null
        if (customUrl.isNotBlank()) {
            if (cachedCustom.isNullOrBlank()) {
                // Link đã lưu nhưng không tải được nội dung (mạng lỗi / link chết /
                // server trả non-200) — trước đây bị bỏ qua im lặng khiến user tưởng
                // app bỏ qua link mình nhập.
                lastCustomLoadError = "Không tải được danh sách kênh từ link M3U (kiểm tra lại đường dẫn hoặc mạng)"
            } else {
                val customChannels = parseM3u(
                    cachedCustom!!,
                    defaultGroup = "M3U Tùy Chọn",
                    isVn = false,
                    filterSports = false,
                    isCustom = true
                )
                if (customChannels.isNotEmpty()) {
                    finalChannels.addAll(customChannels)
                } else if (isSingleStreamPlaylist(cachedCustom!!)) {
                    // Link là MỘT luồng .m3u8 đơn (media playlist, không có #EXTINF)
                    // chứ không phải danh sách kênh — vẫn cho xem như 1 kênh duy nhất
                    // thay vì báo "không có kênh" khó hiểu.
                    finalChannels.add(
                        IptvChannel(
                            id = "custom_stream_${customUrl.hashCode()}",
                            name = "Kênh M3U",
                            group = "M3U Của Tôi",
                            logo = "",
                            url = customUrl,
                            isVn = false,
                            isCustom = true
                        )
                    )
                } else {
                    lastCustomLoadError = "Link tải về không phải danh sách kênh M3U hợp lệ"
                }
            }
        }

        // 4. Kênh Thể thao Quốc tế (DAZN, Sky Sports, beIN, ESPN, FIFA+, Fight, Tennis, Golf, F1...)
        if (!cachedSports.isNullOrBlank()) {
            finalChannels.addAll(parseM3u(cachedSports!!, defaultGroup = "Thể Thao", isVn = false, filterSports = true))
        }

        val distinctList = finalChannels.distinctBy { it.url }
        currentChannels = distinctList
        saveParsedChannelsDisk(context, distinctList)
        distinctList
    }

    private fun fetchUrl(urlStr: String): String? {
        return try {
            Http.getLongWithRetry(urlStr, attempts = 2)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Nhận diện media playlist (.m3u8 đơn lẻ): có header #EXTM3U + tag #EXT-X-
     * nhưng KHÔNG có #EXTINF (dấu hiệu danh sách kênh). Trường hợp này người
     * dùng thường dán nhầm link một kênh thay vì link playlist.
     */
    internal fun isSingleStreamPlaylist(content: String): Boolean =
        content.contains("#EXTM3U", ignoreCase = true) &&
            content.contains("#EXT-X-", ignoreCase = true) &&
            !content.contains("#EXTINF", ignoreCase = true)

    fun parseM3u(
        content: String,
        defaultGroup: String,
        isVn: Boolean,
        filterSports: Boolean,
        isCustom: Boolean = false
    ): List<IptvChannel> {
        val list = mutableListOf<IptvChannel>()
        val lines = content.lines()
        var curName = ""
        var curGroup = defaultGroup
        var curLogo = ""
        var curId = ""
        var curCountry = ""

        val tvgNameRegex = Regex("""tvg-name="([^"]*)"""", RegexOption.IGNORE_CASE)
        val tvgLogoRegex = Regex("""tvg-logo="([^"]*)"""", RegexOption.IGNORE_CASE)
        val groupRegex = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE)
        val tvgIdRegex = Regex("""tvg-id="([^"]*)"""", RegexOption.IGNORE_CASE)
        val countryRegex = Regex("""tvg-country="([^"]*)"""", RegexOption.IGNORE_CASE)
        val idCountryRegex = Regex("""tvg-id="[^"]*\.([a-zA-Z]{2})@""", RegexOption.IGNORE_CASE)

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                curLogo = tvgLogoRegex.find(trimmed)?.groupValues?.get(1) ?: ""
                val foundGroup = groupRegex.find(trimmed)?.groupValues?.get(1) ?: ""
                curId = tvgIdRegex.find(trimmed)?.groupValues?.get(1) ?: ""
                val tvgName = tvgNameRegex.find(trimmed)?.groupValues?.get(1) ?: ""

                // Trích xuất quốc gia
                var country = countryRegex.find(trimmed)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: ""
                if (country.isEmpty()) {
                    country = idCountryRegex.find(trimmed)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: ""
                }
                curCountry = country

                val commaIdx = trimmed.lastIndexOf(',')
                val dispName = if (commaIdx != -1 && commaIdx < trimmed.length - 1) {
                    trimmed.substring(commaIdx + 1).trim()
                } else tvgName

                var cleanName = (if (dispName.isNotEmpty()) dispName else tvgName).ifEmpty { "Kênh TV" }
                val isGeoBlocked = cleanName.contains("[Geo-blocked]", ignoreCase = true)
                if (isGeoBlocked) {
                    curName = ""
                    continue
                }
                cleanName = cleanName.replace(Regex("""\[Geo-blocked\]""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""\[Not 24/7\]""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""\(\d+p\)""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""\s+HD$""", RegexOption.IGNORE_CASE), " HD")
                    .trim()

                curName = cleanName

                curGroup = when {
                    isCustom && foundGroup.isNotBlank() && foundGroup != "Undefined" && foundGroup != "General" -> foundGroup
                    isCustom -> defaultGroup
                    isVn -> if (foundGroup.isNotBlank() && foundGroup != "Undefined" && foundGroup != "General") foundGroup else "Việt Nam"
                    curCountry in EU_COUNTRIES -> "Châu Âu (${curCountry})"
                    else -> defaultGroup
                }

                // Nếu lọc thể thao: Lấy DAZN, Sky Sports, beIN, ESPN, FIFA+, Fight, Tennis, Golf, F1...
                if (filterSports) {
                    val nameLower = cleanName.lowercase(Locale.ROOT)
                    val isPopularSports = nameLower.contains("dazn") ||
                        nameLower.contains("sky sport") ||
                        nameLower.contains("skysport") ||
                        nameLower.contains("bein") ||
                        nameLower.contains("espn") ||
                        nameLower.contains("fifa") ||
                        nameLower.contains("fight") ||
                        nameLower.contains("combat") ||
                        nameLower.contains("tennis") ||
                        nameLower.contains("golf") ||
                        nameLower.contains("f1") ||
                        nameLower.contains("racing")

                    if (isGeoBlocked || !isPopularSports) {
                        curName = ""
                    } else {
                        curGroup = when {
                            nameLower.contains("dazn") -> "DAZN"
                            nameLower.contains("sky") -> "Sky Sports"
                            nameLower.contains("bein") -> "beIN Sports"
                            nameLower.contains("espn") -> "ESPN"
                            nameLower.contains("fifa") -> "FIFA+"
                            else -> "Thể Thao"
                        }
                    }
                }
            } else if (!trimmed.startsWith("#")) {
                if (curName.isNotEmpty() && !trimmed.contains("dethich.pw", ignoreCase = true) &&
                    (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) || trimmed.startsWith("tv360://", true))) {
                    try {
                        val streamHost = java.net.URI(upgradeToHttps(trimmed)).host?.lowercase(Locale.ROOT).orEmpty()
                        val knownStream = KNOWN_HTTPS_HOSTS.any { streamHost == it || streamHost.endsWith(".$it") }
                        if (streamHost.isNotEmpty() && !knownStream && warnedUnknownStreamHosts.add(streamHost)) {
                            Log.w("IptvRepository", "Unknown stream host for channel $curName: $streamHost")
                        }
                    } catch (_: Exception) { }
                    val chId = if (curId.isNotBlank()) curId else "ch_${list.size}_${curName.hashCode()}"
                    list.add(
                        IptvChannel(
                            id = chId,
                            name = curName,
                            group = curGroup,
                            logo = upgradeToHttps(curLogo),
                            url = upgradeToHttps(trimmed),
                            isVn = isVn,
                            country = curCountry,
                            isCustom = isCustom
                        )
                    )
                }
                curName = ""
                curLogo = ""
                curCountry = ""
                curGroup = defaultGroup
                curId = ""
            }
        }
        return list
    }
}