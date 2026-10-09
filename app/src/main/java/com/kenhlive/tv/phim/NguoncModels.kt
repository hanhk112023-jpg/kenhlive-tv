package com.kenhlive.tv.phim

/**
 * Data models cho hệ thống Phim Nguồn C (phim.nguonc.com):
 * - Hỗ trợ kho phim hơn 33.400+ đầu phim miễn phí
 * - Tương thích chuẩn phim lẻ, phim bộ, anime, TV shows
 * - Tích hợp theo dõi tiến độ xem (Watch Progress)
 */

data class NguoncFilm(
    val id: String = "",
    val name: String,
    val originalName: String = "",
    val slug: String,
    val year: String = "",
    val description: String = "",
    val totalEpisodes: String = "",
    val currentEpisode: String = "",
    val time: String = "",
    val quality: String = "HD",
    val language: String = "Vietsub",
    val director: String = "",
    val casts: String = "",
    val categories: List<String> = emptyList(),
    val country: String = "",
    val thumbUrl: String = "",
    val posterUrl: String = "",
    val watchProgress: WatchHistoryItem? = null
)

data class NguoncEpisodeItem(
    val name: String,     // e.g. "1", "2", "Tập 1", "Full"
    val slug: String,     // e.g. "tap-1"
    val embed: String,    // e.g. "https://embed12.streamc.xyz/embed.php?hash=..."
    val m3u8: String = "" // link direct m3u8 nếu có
)

data class NguoncServer(
    val serverName: String, // e.g. "Vietsub #1"
    val items: List<NguoncEpisodeItem>
)

data class NguoncFilmDetail(
    val film: NguoncFilm,
    val episodes: List<NguoncServer>
)

data class NguoncPaginatedResult(
    val films: List<NguoncFilm>,
    val currentPage: Int,
    val totalPages: Int,
    val totalItems: Int
)

data class NguoncCategoryItem(
    val id: String,
    val name: String,
    val slug: String
)

data class NguoncFilter(
    val type: String = "phim-moi",     // "phim-moi", "phim-bo", "phim-le", "hoat-hinh", "tv-shows"
    val categorySlug: String = "",      // hành-động, tình-cảm, v.v.
    val countrySlug: String = "",       // trung-quoc, han-quoc, au-my, viet-nam
    val year: String = ""               // 2026, 2025, 2024
)
