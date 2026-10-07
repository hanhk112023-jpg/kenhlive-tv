package com.kenhlive.tv.phim

/**
 * Data models cho hệ thống Phim Nguồn C (phim.nguonc.com):
 * - Hỗ trợ kho phim hơn 33.400+ đầu phim miễn phí
 * - Tương thích chuẩn phim lẻ, phim bộ, anime, TV shows
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
    val thumbUrl: String = "",
    val posterUrl: String = ""
)

data class NguoncEpisodeItem(
    val name: String,     // e.g. "1", "2", "Tập 1", "Full"
    val slug: String,     // e.g. "tap-1"
    val embed: String     // e.g. "https://embed12.streamc.xyz/embed.php?hash=..."
)

data class NguoncServer(
    val serverName: String, // e.g. "Vietsub #1"
    val items: List<NguoncEpisodeItem>
)

data class NguoncFilmDetail(
    val film: NguoncFilm,
    val episodes: List<NguoncServer>
)
