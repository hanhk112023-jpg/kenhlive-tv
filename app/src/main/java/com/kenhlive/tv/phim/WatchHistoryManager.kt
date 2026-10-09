package com.kenhlive.tv.phim

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Model biểu diễn tiến độ xem phim cục bộ.
 */
data class WatchHistoryItem(
    val slug: String,
    val filmName: String,
    val posterUrl: String,
    val episodeName: String,
    val episodeSlug: String,
    val embedUrl: String,
    val positionMs: Long,
    val durationMs: Long,
    val progressPct: Int,
    val lastWatchedAt: Long,
    val isCompleted: Boolean
) {
    /** Định dạng thời gian xem: "01:24:30 / 02:05:00" hoặc "Phút 45" */
    fun formattedProgress(): String {
        val curMin = (positionMs / 60000).toInt()
        val totalMin = (durationMs / 60000).toInt()
        return if (totalMin > 0) {
            "${curMin}/${totalMin} phút ($progressPct%)"
        } else if (curMin > 0) {
            "${curMin} phút"
        } else {
            "$progressPct%"
        }
    }
}

/**
 * WatchHistoryManager — Quản lý lịch sử và tiến độ xem phim Nguồn C:
 * - Lưu trữ cục bộ bằng SQLite siêu nhẹ (zero-dependency, hiệu năng <1ms, an toàn trên TV box 1GB RAM).
 * - Theo dõi từng phim: tập đang xem, vị trí (mili-giây), thời điểm xem gần nhất.
 * - Danh sách "Tiếp tục xem" (Continue Watching) tự động cập nhật khi tạm dừng hoặc thoát.
 * - Đánh dấu các tập đã xem trong toàn bộ series.
 */
object WatchHistoryManager {

    private const val DB_NAME = "kenhlive_cinema.db"
    private const val DB_VERSION = 1

    private const val TABLE_HISTORY = "watch_history"
    private const val TABLE_EPISODES = "watched_episodes"
    private const val TABLE_FAVORITES = "favorite_films"

    private const val COL_SLUG = "slug"
    private const val COL_FILM_NAME = "film_name"
    private const val COL_POSTER_URL = "poster_url"
    private const val COL_EPISODE_NAME = "episode_name"
    private const val COL_EPISODE_SLUG = "episode_slug"
    private const val COL_EMBED_URL = "embed_url"
    private const val COL_POSITION_MS = "position_ms"
    private const val COL_DURATION_MS = "duration_ms"
    private const val COL_PROGRESS_PCT = "progress_pct"
    private const val COL_LAST_WATCHED_AT = "last_watched_at"
    private const val COL_IS_COMPLETED = "is_completed"

    private const val COL_EP_FILM_SLUG = "film_slug"
    private const val COL_EP_SLUG = "episode_slug"
    private const val COL_EP_WATCHED_AT = "watched_at"

    private const val COL_FAV_YEAR = "year"
    private const val COL_FAV_QUALITY = "quality"
    private const val COL_FAV_ADDED_AT = "added_at"

    @Volatile
    private var dbHelper: DbHelper? = null

    fun init(context: Context) {
        if (dbHelper == null) {
            synchronized(this) {
                if (dbHelper == null) {
                    dbHelper = DbHelper(context.applicationContext)
                }
            }
        }
    }

    private class DbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_HISTORY (
                    $COL_SLUG TEXT PRIMARY KEY,
                    $COL_FILM_NAME TEXT NOT NULL,
                    $COL_POSTER_URL TEXT,
                    $COL_EPISODE_NAME TEXT NOT NULL,
                    $COL_EPISODE_SLUG TEXT NOT NULL,
                    $COL_EMBED_URL TEXT NOT NULL,
                    $COL_POSITION_MS INTEGER NOT NULL DEFAULT 0,
                    $COL_DURATION_MS INTEGER NOT NULL DEFAULT 0,
                    $COL_PROGRESS_PCT INTEGER NOT NULL DEFAULT 0,
                    $COL_LAST_WATCHED_AT INTEGER NOT NULL,
                    $COL_IS_COMPLETED INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_EPISODES (
                    $COL_EP_FILM_SLUG TEXT NOT NULL,
                    $COL_EP_SLUG TEXT NOT NULL,
                    $COL_EP_WATCHED_AT INTEGER NOT NULL,
                    PRIMARY KEY ($COL_EP_FILM_SLUG, $COL_EP_SLUG)
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_FAVORITES (
                    $COL_SLUG TEXT PRIMARY KEY,
                    $COL_FILM_NAME TEXT NOT NULL,
                    $COL_POSTER_URL TEXT,
                    $COL_FAV_YEAR TEXT,
                    $COL_FAV_QUALITY TEXT,
                    $COL_FAV_ADDED_AT INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_time ON $TABLE_HISTORY ($COL_LAST_WATCHED_AT DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_fav_time ON $TABLE_FAVORITES ($COL_FAV_ADDED_AT DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Future schema migrations
        }
    }

    /**
     * Tự động lưu hoặc cập nhật tiến độ xem phim.
     */
    suspend fun saveProgress(
        slug: String,
        filmName: String,
        posterUrl: String,
        episodeName: String,
        episodeSlug: String,
        embedUrl: String,
        positionMs: Long,
        durationMs: Long
    ) = withContext(Dispatchers.IO) {
        val helper = dbHelper ?: return@withContext
        try {
            val db = helper.writableDatabase
            val now = System.currentTimeMillis()
            val progressPct = if (durationMs > 0) {
                ((positionMs.toDouble() / durationMs.toDouble()) * 100).toInt().coerceIn(0, 100)
            } else 0
            val isCompleted = progressPct >= 90

            val cv = ContentValues().apply {
                put(COL_SLUG, slug)
                put(COL_FILM_NAME, filmName)
                put(COL_POSTER_URL, posterUrl)
                put(COL_EPISODE_NAME, episodeName)
                put(COL_EPISODE_SLUG, episodeSlug)
                put(COL_EMBED_URL, embedUrl)
                put(COL_POSITION_MS, positionMs)
                put(COL_DURATION_MS, durationMs)
                put(COL_PROGRESS_PCT, progressPct)
                put(COL_LAST_WATCHED_AT, now)
                put(COL_IS_COMPLETED, if (isCompleted) 1 else 0)
            }

            db.insertWithOnConflict(TABLE_HISTORY, null, cv, SQLiteDatabase.CONFLICT_REPLACE)

            // Đánh dấu tập đã xem
            val cvEp = ContentValues().apply {
                put(COL_EP_FILM_SLUG, slug)
                put(COL_EP_SLUG, episodeSlug)
                put(COL_EP_WATCHED_AT, now)
            }
            db.insertWithOnConflict(TABLE_EPISODES, null, cvEp, SQLiteDatabase.CONFLICT_REPLACE)

            // Dọn dẹp: giữ tối đa 50 phim gần nhất để tránh phình dung lượng bộ nhớ TV
            db.execSQL(
                """
                DELETE FROM $TABLE_HISTORY WHERE $COL_SLUG NOT IN (
                    SELECT $COL_SLUG FROM $TABLE_HISTORY ORDER BY $COL_LAST_WATCHED_AT DESC LIMIT 50
                )
                """.trimIndent()
            )
        } catch (_: Exception) {}
    }

    /**
     * Lấy danh sách phim "Tiếp tục xem" (mới nhất lên đầu).
     */
    suspend fun getContinueWatchingList(limit: Int = 15): List<WatchHistoryItem> =
        withContext(Dispatchers.IO) {
            val helper = dbHelper ?: return@withContext emptyList()
            val list = mutableListOf<WatchHistoryItem>()
            try {
                val db = helper.readableDatabase
                val cursor = db.query(
                    TABLE_HISTORY,
                    null,
                    "$COL_POSITION_MS > 0 AND $COL_IS_COMPLETED = 0",
                    null,
                    null,
                    null,
                    "$COL_LAST_WATCHED_AT DESC",
                    limit.toString()
                )
                cursor.use { c ->
                    val idxSlug = c.getColumnIndex(COL_SLUG)
                    val idxName = c.getColumnIndex(COL_FILM_NAME)
                    val idxPoster = c.getColumnIndex(COL_POSTER_URL)
                    val idxEpName = c.getColumnIndex(COL_EPISODE_NAME)
                    val idxEpSlug = c.getColumnIndex(COL_EPISODE_SLUG)
                    val idxEmbed = c.getColumnIndex(COL_EMBED_URL)
                    val idxPos = c.getColumnIndex(COL_POSITION_MS)
                    val idxDur = c.getColumnIndex(COL_DURATION_MS)
                    val idxPct = c.getColumnIndex(COL_PROGRESS_PCT)
                    val idxTime = c.getColumnIndex(COL_LAST_WATCHED_AT)
                    val idxComp = c.getColumnIndex(COL_IS_COMPLETED)

                    while (c.moveToNext()) {
                        list.add(
                            WatchHistoryItem(
                                slug = c.getString(idxSlug),
                                filmName = c.getString(idxName),
                                posterUrl = c.getString(idxPoster).orEmpty(),
                                episodeName = c.getString(idxEpName),
                                episodeSlug = c.getString(idxEpSlug),
                                embedUrl = c.getString(idxEmbed),
                                positionMs = c.getLong(idxPos),
                                durationMs = c.getLong(idxDur),
                                progressPct = c.getInt(idxPct),
                                lastWatchedAt = c.getLong(idxTime),
                                isCompleted = c.getInt(idxComp) == 1
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
            list
        }

    /**
     * Lấy tiến độ đã xem của một phim cụ thể.
     */
    suspend fun getProgressForFilm(slug: String): WatchHistoryItem? =
        withContext(Dispatchers.IO) {
            val helper = dbHelper ?: return@withContext null
            try {
                val db = helper.readableDatabase
                val cursor = db.query(
                    TABLE_HISTORY,
                    null,
                    "$COL_SLUG = ?",
                    arrayOf(slug),
                    null,
                    null,
                    null,
                    "1"
                )
                cursor.use { c ->
                    if (c.moveToFirst()) {
                        return@withContext WatchHistoryItem(
                            slug = c.getString(c.getColumnIndexOrThrow(COL_SLUG)),
                            filmName = c.getString(c.getColumnIndexOrThrow(COL_FILM_NAME)),
                            posterUrl = c.getString(c.getColumnIndexOrThrow(COL_POSTER_URL)).orEmpty(),
                            episodeName = c.getString(c.getColumnIndexOrThrow(COL_EPISODE_NAME)),
                            episodeSlug = c.getString(c.getColumnIndexOrThrow(COL_EPISODE_SLUG)),
                            embedUrl = c.getString(c.getColumnIndexOrThrow(COL_EMBED_URL)),
                            positionMs = c.getLong(c.getColumnIndexOrThrow(COL_POSITION_MS)),
                            durationMs = c.getLong(c.getColumnIndexOrThrow(COL_DURATION_MS)),
                            progressPct = c.getInt(c.getColumnIndexOrThrow(COL_PROGRESS_PCT)),
                            lastWatchedAt = c.getLong(c.getColumnIndexOrThrow(COL_LAST_WATCHED_AT)),
                            isCompleted = c.getInt(c.getColumnIndexOrThrow(COL_IS_COMPLETED)) == 1
                        )
                    }
                }
            } catch (_: Exception) {}
            null
        }

    /**
     * Kiểm tra một tập phim đã từng được xem chưa.
     */
    suspend fun isEpisodeWatched(filmSlug: String, episodeSlug: String): Boolean =
        withContext(Dispatchers.IO) {
            val helper = dbHelper ?: return@withContext false
            try {
                val db = helper.readableDatabase
                val cursor = db.query(
                    TABLE_EPISODES,
                    arrayOf(COL_EP_SLUG),
                    "$COL_EP_FILM_SLUG = ? AND $COL_EP_SLUG = ?",
                    arrayOf(filmSlug, episodeSlug),
                    null,
                    null,
                    null,
                    "1"
                )
                cursor.use { c ->
                    return@withContext c.count > 0
                }
            } catch (_: Exception) {
                false
            }
        }

    /**
     * Xóa tiến độ của một phim.
     */
    suspend fun deleteProgress(slug: String) = withContext(Dispatchers.IO) {
        val helper = dbHelper ?: return@withContext
        try {
            val db = helper.writableDatabase
            db.delete(TABLE_HISTORY, "$COL_SLUG = ?", arrayOf(slug))
        } catch (_: Exception) {}
    }

    // ================= PHIM YÊU THÍCH / ĐÃ LƯU (FAVORITES) =================

    /**
     * Kiểm tra phim đã được lưu vào danh sách yêu thích chưa.
     */
    suspend fun isFavorite(slug: String): Boolean = withContext(Dispatchers.IO) {
        val helper = dbHelper ?: return@withContext false
        try {
            val db = helper.readableDatabase
            val cursor = db.query(TABLE_FAVORITES, arrayOf(COL_SLUG), "$COL_SLUG = ?", arrayOf(slug), null, null, null, "1")
            cursor.use { it.count > 0 }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Lấy danh sách các phim/tập đã xem xong (tiến độ >= 90%).
     */
    suspend fun getCompletedFilms(limit: Int = 15): List<WatchHistoryItem> =
        withContext(Dispatchers.IO) {
            val helper = dbHelper ?: return@withContext emptyList()
            val list = mutableListOf<WatchHistoryItem>()
            try {
                val db = helper.readableDatabase
                val cursor = db.query(
                    TABLE_HISTORY,
                    null,
                    "$COL_IS_COMPLETED = 1",
                    null,
                    null,
                    null,
                    "$COL_LAST_WATCHED_AT DESC",
                    limit.toString()
                )
                cursor.use { c ->
                    val idxSlug = c.getColumnIndex(COL_SLUG)
                    val idxName = c.getColumnIndex(COL_FILM_NAME)
                    val idxPoster = c.getColumnIndex(COL_POSTER_URL)
                    val idxEpName = c.getColumnIndex(COL_EPISODE_NAME)
                    val idxEpSlug = c.getColumnIndex(COL_EPISODE_SLUG)
                    val idxEmbed = c.getColumnIndex(COL_EMBED_URL)
                    val idxPos = c.getColumnIndex(COL_POSITION_MS)
                    val idxDur = c.getColumnIndex(COL_DURATION_MS)
                    val idxPct = c.getColumnIndex(COL_PROGRESS_PCT)
                    val idxTime = c.getColumnIndex(COL_LAST_WATCHED_AT)
                    val idxComp = c.getColumnIndex(COL_IS_COMPLETED)

                    while (c.moveToNext()) {
                        list.add(
                            WatchHistoryItem(
                                slug = c.getString(idxSlug),
                                filmName = c.getString(idxName),
                                posterUrl = c.getString(idxPoster).orEmpty(),
                                episodeName = c.getString(idxEpName),
                                episodeSlug = c.getString(idxEpSlug),
                                embedUrl = c.getString(idxEmbed),
                                positionMs = c.getLong(idxPos),
                                durationMs = c.getLong(idxDur),
                                progressPct = c.getInt(idxPct),
                                lastWatchedAt = c.getLong(idxTime),
                                isCompleted = c.getInt(idxComp) == 1
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
            list
        }

    /**
     * Lưu hoặc bỏ lưu phim yêu thích. Trả về true nếu vừa thêm vào yêu thích, false nếu vừa xóa bỏ.
     */
    suspend fun toggleFavorite(
        film: NguoncFilm,
        episodeName: String? = null,
        episodeSlug: String? = null,
        embedUrl: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val helper = dbHelper ?: return@withContext false
        try {
            val db = helper.writableDatabase
            if (isFavorite(film.slug)) {
                db.delete(TABLE_FAVORITES, "$COL_SLUG = ?", arrayOf(film.slug))
                false
            } else {
                val cv = ContentValues().apply {
                    put(COL_SLUG, film.slug)
                    put(COL_FILM_NAME, film.name)
                    put(COL_POSTER_URL, film.posterUrl.ifBlank { film.thumbUrl })
                    put(COL_FAV_YEAR, film.year)
                    put(COL_FAV_QUALITY, film.quality)
                    put(COL_FAV_ADDED_AT, System.currentTimeMillis())
                }
                db.insertWithOnConflict(TABLE_FAVORITES, null, cv, SQLiteDatabase.CONFLICT_REPLACE)

                // Nếu có tập được chỉ định thì lưu luôn tập đó vào điểm nhớ
                if (!episodeName.isNullOrBlank() && !episodeSlug.isNullOrBlank()) {
                    saveProgress(
                        slug = film.slug,
                        filmName = film.name,
                        posterUrl = film.posterUrl.ifBlank { film.thumbUrl },
                        episodeName = episodeName,
                        episodeSlug = episodeSlug,
                        embedUrl = embedUrl.orEmpty(),
                        positionMs = 1000L,
                        durationMs = 0L
                    )
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Lấy danh sách toàn bộ phim đã lưu (mới lưu nhất lên đầu), kèm tiến độ tập xem hiện tại.
     */
    suspend fun getFavoriteFilms(): List<NguoncFilm> = withContext(Dispatchers.IO) {
        val helper = dbHelper ?: return@withContext emptyList()
        val list = mutableListOf<NguoncFilm>()
        try {
            val db = helper.readableDatabase
            val cursor = db.query(TABLE_FAVORITES, null, null, null, null, null, "$COL_FAV_ADDED_AT DESC")
            cursor.use { c ->
                val idxSlug = c.getColumnIndex(COL_SLUG)
                val idxName = c.getColumnIndex(COL_FILM_NAME)
                val idxPoster = c.getColumnIndex(COL_POSTER_URL)
                val idxYear = c.getColumnIndex(COL_FAV_YEAR)
                val idxQuality = c.getColumnIndex(COL_FAV_QUALITY)
                while (c.moveToNext()) {
                    val slug = c.getString(idxSlug)
                    val poster = c.getString(idxPoster).orEmpty()
                    val progress = getProgressForFilm(slug)
                    list.add(
                        NguoncFilm(
                            slug = slug,
                            name = c.getString(idxName),
                            posterUrl = poster,
                            thumbUrl = poster,
                            year = c.getString(idxYear).orEmpty(),
                            quality = c.getString(idxQuality).orEmpty(),
                            currentEpisode = progress?.episodeName ?: "",
                            watchProgress = progress
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        list
    }

    /**
     * Xóa toàn bộ lịch sử.
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        val helper = dbHelper ?: return@withContext
        try {
            val db = helper.writableDatabase
            db.delete(TABLE_HISTORY, null, null)
            db.delete(TABLE_EPISODES, null, null)
        } catch (_: Exception) {}
    }
}
