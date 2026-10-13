package com.kenhlive.tv.phim

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

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
    /** Định dạng thời gian xem chi tiết: "15:20 / 45:00 (34%)" hoặc "01:24:30 / 02:05:00" */
    fun formattedProgress(): String {
        val posSec = (positionMs / 1000L).coerceAtLeast(0L)
        val durSec = (durationMs / 1000L).coerceAtLeast(0L)
        val posStr = formatTime(posSec)
        return if (durSec > 0L) {
            val durStr = formatTime(durSec)
            "$posStr / $durStr ($progressPct%)"
        } else if (posSec > 0L) {
            "Đã xem $posStr"
        } else {
            "Đã lưu tập"
        }
    }

    /** Chỉ lấy thời gian dạng "15:20 / 45:00" hoặc "15:20" */
    fun formattedTimeOnly(): String {
        val posSec = (positionMs / 1000L).coerceAtLeast(0L)
        val durSec = (durationMs / 1000L).coerceAtLeast(0L)
        return if (durSec > 0L) {
            "${formatTime(posSec)}/${formatTime(durSec)}"
        } else if (posSec > 0L) {
            formatTime(posSec)
        } else {
            ""
        }
    }

    private fun formatTime(totalSec: Long): String {
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }
}

/**
 * WatchHistoryManager Pro — Quản lý lịch sử, tiến độ và tủ phim đã lưu bền vững:
 * - Lưu trữ chính: SQLite Database (DB_VERSION 2) với đầy đủ bảng history, episodes, favorites.
 * - Lưu trữ dự phòng đa tầng (Dual-Layer Persistent Backup): SharedPreferences + persistent JSON vault
 *   (internal files + external app files dir).
 * - Tự động phục hồi 100% dữ liệu khi cập nhật phiên bản mới, cài đè APK hoặc khôi phục từ bộ nhớ thiết bị.
 * - Theo dõi chính xác từng tập phim, thời gian phát (mili-giây, mm:ss), tỷ lệ hoàn thành.
 * - Không bị hủy khi Activity đóng nhờ Scope chuyên dụng độc lập (SupervisorJob).
 */
object WatchHistoryManager {

    private const val TAG = "WatchHistoryManager"
    private const val DB_NAME = "kenhlive_cinema.db"
    private const val DB_VERSION = 2

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
    private const val COL_EP_NAME = "episode_name"
    private const val COL_EP_EMBED = "embed_url"
    private const val COL_EP_POSITION_MS = "position_ms"
    private const val COL_EP_DURATION_MS = "duration_ms"
    private const val COL_EP_WATCHED_AT = "watched_at"

    private const val COL_FAV_YEAR = "year"
    private const val COL_FAV_QUALITY = "quality"
    private const val COL_FAV_ADDED_AT = "added_at"

    // Backup Constants
    private const val PREFS_BACKUP = "kenhlive_cinema_backup_prefs"
    private const val KEY_VAULT_JSON = "vault_json"
    private const val BACKUP_FILE_NAME = "kenhlive_cinema_vault.json"

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var dbHelper: DbHelper? = null

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (dbHelper == null) {
            synchronized(this) {
                if (dbHelper == null) {
                    dbHelper = DbHelper(app)
                }
            }
        }
        // Tự động kiểm tra và phục hồi nếu database trống sau khi cài đặt/nâng cấp
        ioScope.launch {
            try {
                restoreFromBackupIfNeeded()
            } catch (t: Throwable) {
                Log.e(TAG, "Lỗi kiểm tra phục hồi backup: ${t.message}")
            }
        }
    }

    private class DbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            createAllTables(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            createAllTables(db)
            if (oldVersion < 2) {
                // Bổ sung các cột lưu tập và thời gian xem cho bảng favorites nếu migrate từ v1
                ensureColumn(db, TABLE_FAVORITES, COL_EPISODE_NAME, "TEXT")
                ensureColumn(db, TABLE_FAVORITES, COL_EPISODE_SLUG, "TEXT")
                ensureColumn(db, TABLE_FAVORITES, COL_EMBED_URL, "TEXT")
                ensureColumn(db, TABLE_FAVORITES, COL_POSITION_MS, "INTEGER NOT NULL DEFAULT 0")
                ensureColumn(db, TABLE_FAVORITES, COL_DURATION_MS, "INTEGER NOT NULL DEFAULT 0")
                ensureColumn(db, TABLE_FAVORITES, COL_PROGRESS_PCT, "INTEGER NOT NULL DEFAULT 0")

                // Bổ sung các cột cho bảng watched_episodes
                ensureColumn(db, TABLE_EPISODES, COL_EP_NAME, "TEXT")
                ensureColumn(db, TABLE_EPISODES, COL_EP_EMBED, "TEXT")
                ensureColumn(db, TABLE_EPISODES, COL_EP_POSITION_MS, "INTEGER NOT NULL DEFAULT 0")
                ensureColumn(db, TABLE_EPISODES, COL_EP_DURATION_MS, "INTEGER NOT NULL DEFAULT 0")
            }
        }

        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            // Đảm bảo các bảng và cột luôn tồn tại ngay cả khi mở lại DB
            createAllTables(db)
            ensureColumn(db, TABLE_FAVORITES, COL_EPISODE_NAME, "TEXT")
            ensureColumn(db, TABLE_FAVORITES, COL_EPISODE_SLUG, "TEXT")
            ensureColumn(db, TABLE_FAVORITES, COL_EMBED_URL, "TEXT")
            ensureColumn(db, TABLE_FAVORITES, COL_POSITION_MS, "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, TABLE_FAVORITES, COL_DURATION_MS, "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, TABLE_FAVORITES, COL_PROGRESS_PCT, "INTEGER NOT NULL DEFAULT 0")

            ensureColumn(db, TABLE_EPISODES, COL_EP_NAME, "TEXT")
            ensureColumn(db, TABLE_EPISODES, COL_EP_EMBED, "TEXT")
            ensureColumn(db, TABLE_EPISODES, COL_EP_POSITION_MS, "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, TABLE_EPISODES, COL_EP_DURATION_MS, "INTEGER NOT NULL DEFAULT 0")
        }

        private fun createAllTables(db: SQLiteDatabase) {
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
                    $COL_EP_NAME TEXT,
                    $COL_EP_EMBED TEXT,
                    $COL_EP_POSITION_MS INTEGER NOT NULL DEFAULT 0,
                    $COL_EP_DURATION_MS INTEGER NOT NULL DEFAULT 0,
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
                    $COL_EPISODE_NAME TEXT,
                    $COL_EPISODE_SLUG TEXT,
                    $COL_EMBED_URL TEXT,
                    $COL_POSITION_MS INTEGER NOT NULL DEFAULT 0,
                    $COL_DURATION_MS INTEGER NOT NULL DEFAULT 0,
                    $COL_PROGRESS_PCT INTEGER NOT NULL DEFAULT 0,
                    $COL_FAV_ADDED_AT INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_time ON $TABLE_HISTORY ($COL_LAST_WATCHED_AT DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_fav_time ON $TABLE_FAVORITES ($COL_FAV_ADDED_AT DESC)")
        }

        private fun ensureColumn(db: SQLiteDatabase, table: String, column: String, typeDef: String) {
            try {
                val cursor = db.rawQuery("PRAGMA table_info($table)", null)
                var exists = false
                cursor.use { c ->
                    val nameIdx = c.getColumnIndex("name")
                    while (c.moveToNext()) {
                        if (nameIdx >= 0 && c.getString(nameIdx).equals(column, ignoreCase = true)) {
                            exists = true
                            break
                        }
                    }
                }
                if (!exists) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN $column $typeDef")
                }
            } catch (e: Exception) {
                Log.w(TAG, "ensureColumn $table.$column error: ${e.message}")
            }
        }
    }

    /**
     * Tự động lưu tiến độ xem phim bất đồng bộ mà không bị hủy khi Activity thoát.
     */
    fun saveProgressAsync(
        slug: String,
        filmName: String,
        posterUrl: String,
        episodeName: String,
        episodeSlug: String,
        embedUrl: String,
        positionMs: Long,
        durationMs: Long
    ) {
        ioScope.launch {
            saveProgress(
                slug = slug,
                filmName = filmName,
                posterUrl = posterUrl,
                episodeName = episodeName,
                episodeSlug = episodeSlug,
                embedUrl = embedUrl,
                positionMs = positionMs,
                durationMs = durationMs
            )
        }
    }

    /**
     * Tự động lưu hoặc cập nhật tiến độ xem phim vào SQLite và sao lưu bền vững.
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

            // 1. Cập nhật bảng watch_history
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

            // 2. Cập nhật bảng watched_episodes (lưu tiến độ riêng cho từng tập)
            val cvEp = ContentValues().apply {
                put(COL_EP_FILM_SLUG, slug)
                put(COL_EP_SLUG, episodeSlug)
                put(COL_EP_NAME, episodeName)
                put(COL_EP_EMBED, embedUrl)
                put(COL_EP_POSITION_MS, positionMs)
                put(COL_EP_DURATION_MS, durationMs)
                put(COL_EP_WATCHED_AT, now)
            }
            db.insertWithOnConflict(TABLE_EPISODES, null, cvEp, SQLiteDatabase.CONFLICT_REPLACE)

            // 3. Nếu bộ phim này đã có trong danh sách Yêu Thích (Favorites), cập nhật ngay tập và thời gian xem mới nhất
            val curFav = db.query(TABLE_FAVORITES, arrayOf(COL_SLUG), "$COL_SLUG = ?", arrayOf(slug), null, null, null, "1")
            val isFav = curFav.use { it.count > 0 }
            if (isFav) {
                val cvFav = ContentValues().apply {
                    put(COL_EPISODE_NAME, episodeName)
                    put(COL_EPISODE_SLUG, episodeSlug)
                    put(COL_EMBED_URL, embedUrl)
                    put(COL_POSITION_MS, positionMs)
                    put(COL_DURATION_MS, durationMs)
                    put(COL_PROGRESS_PCT, progressPct)
                }
                db.update(TABLE_FAVORITES, cvFav, "$COL_SLUG = ?", arrayOf(slug))
            }

            // 4. Dọn dẹp an toàn: Giữ tối đa 200 phim gần nhất nhưng KHÔNG BAO GIỜ xóa phim nằm trong mục Đã Lưu
            db.execSQL(
                """
                DELETE FROM $TABLE_HISTORY WHERE $COL_SLUG NOT IN (
                    SELECT $COL_SLUG FROM $TABLE_HISTORY ORDER BY $COL_LAST_WATCHED_AT DESC LIMIT 200
                ) AND $COL_SLUG NOT IN (
                    SELECT $COL_SLUG FROM $TABLE_FAVORITES
                )
                """.trimIndent()
            )

            // 5. Sao lưu đồng bộ ra lưu trữ bền vững
            triggerBackupAsync()
        } catch (e: Exception) {
            Log.e(TAG, "saveProgress error: ${e.message}")
        }
    }

    /**
     * Lấy danh sách phim "Tiếp tục xem" (mới nhất lên đầu).
     */
    suspend fun getContinueWatchingList(limit: Int = 20): List<WatchHistoryItem> =
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
            } catch (e: Exception) {
                Log.w(TAG, "getContinueWatchingList error: ${e.message}")
            }
            list
        }

    /**
     * Lấy tiến độ đã xem của một phim cụ thể (kết hợp history & favorites).
     */
    suspend fun getProgressForFilm(slug: String): WatchHistoryItem? =
        withContext(Dispatchers.IO) {
            val helper = dbHelper ?: return@withContext null
            try {
                val db = helper.readableDatabase
                // 1. Kiểm tra trong TABLE_HISTORY trước
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

                // 2. Fallback sang TABLE_FAVORITES nếu history chưa có
                val favCursor = db.query(
                    TABLE_FAVORITES,
                    null,
                    "$COL_SLUG = ?",
                    arrayOf(slug),
                    null,
                    null,
                    null,
                    "1"
                )
                favCursor.use { c ->
                    if (c.moveToFirst()) {
                        val epName = c.getString(c.getColumnIndexOrThrow(COL_EPISODE_NAME)).orEmpty()
                        val epSlug = c.getString(c.getColumnIndexOrThrow(COL_EPISODE_SLUG)).orEmpty()
                        if (epName.isNotBlank() || epSlug.isNotBlank()) {
                            return@withContext WatchHistoryItem(
                                slug = c.getString(c.getColumnIndexOrThrow(COL_SLUG)),
                                filmName = c.getString(c.getColumnIndexOrThrow(COL_FILM_NAME)),
                                posterUrl = c.getString(c.getColumnIndexOrThrow(COL_POSTER_URL)).orEmpty(),
                                episodeName = epName.ifBlank { "Tập đã lưu" },
                                episodeSlug = epSlug,
                                embedUrl = c.getString(c.getColumnIndexOrThrow(COL_EMBED_URL)).orEmpty(),
                                positionMs = c.getLong(c.getColumnIndexOrThrow(COL_POSITION_MS)),
                                durationMs = c.getLong(c.getColumnIndexOrThrow(COL_DURATION_MS)),
                                progressPct = c.getInt(c.getColumnIndexOrThrow(COL_PROGRESS_PCT)),
                                lastWatchedAt = c.getLong(c.getColumnIndexOrThrow(COL_FAV_ADDED_AT)),
                                isCompleted = false
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "getProgressForFilm error: ${e.message}")
            }
            null
        }

    /**
     * Lấy tiến độ đã xem của một tập cụ thể trong một phim.
     */
    suspend fun getProgressForEpisode(filmSlug: String, episodeSlug: String): WatchHistoryItem? =
        withContext(Dispatchers.IO) {
            val helper = dbHelper ?: return@withContext null
            try {
                val db = helper.readableDatabase
                val cursor = db.query(
                    TABLE_EPISODES,
                    null,
                    "$COL_EP_FILM_SLUG = ? AND $COL_EP_SLUG = ?",
                    arrayOf(filmSlug, episodeSlug),
                    null,
                    null,
                    null,
                    "1"
                )
                cursor.use { c ->
                    if (c.moveToFirst()) {
                        val pos = c.getLong(c.getColumnIndexOrThrow(COL_EP_POSITION_MS))
                        val dur = c.getLong(c.getColumnIndexOrThrow(COL_EP_DURATION_MS))
                        val pct = if (dur > 0L) ((pos.toDouble() / dur) * 100).toInt().coerceIn(0, 100) else 0
                        return@withContext WatchHistoryItem(
                            slug = filmSlug,
                            filmName = "",
                            posterUrl = "",
                            episodeName = c.getString(c.getColumnIndexOrThrow(COL_EP_NAME)).orEmpty(),
                            episodeSlug = episodeSlug,
                            embedUrl = c.getString(c.getColumnIndexOrThrow(COL_EP_EMBED)).orEmpty(),
                            positionMs = pos,
                            durationMs = dur,
                            progressPct = pct,
                            lastWatchedAt = c.getLong(c.getColumnIndexOrThrow(COL_EP_WATCHED_AT)),
                            isCompleted = pct >= 90
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "getProgressForEpisode error: ${e.message}")
            }
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
                cursor.use { c -> c.count > 0 }
            } catch (e: Exception) {
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
            triggerBackupAsync()
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
    suspend fun getCompletedFilms(limit: Int = 20): List<WatchHistoryItem> =
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
            } catch (e: Exception) {
                Log.w(TAG, "getCompletedFilms error: ${e.message}")
            }
            list
        }

    /**
     * Lưu hoặc bỏ lưu phim yêu thích — BẢO TOÀN 100% TẬP PHIM VÀ THỜI GIAN XEM.
     * Nếu không truyền tập/thời gian, tự động kế thừa từ tiến độ xem gần nhất (nếu có).
     */
    suspend fun toggleFavorite(
        film: NguoncFilm,
        episodeName: String? = null,
        episodeSlug: String? = null,
        embedUrl: String? = null,
        positionMs: Long? = null,
        durationMs: Long? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val helper = dbHelper ?: return@withContext false
        try {
            val db = helper.writableDatabase
            if (isFavorite(film.slug)) {
                db.delete(TABLE_FAVORITES, "$COL_SLUG = ?", arrayOf(film.slug))
                triggerBackupAsync()
                false
            } else {
                // Tự động tìm kiếm tiến độ hiện tại nếu không truyền thông tin tập
                val existing = getProgressForFilm(film.slug)
                val finalEpName = episodeName ?: existing?.episodeName ?: film.currentEpisode
                val finalEpSlug = episodeSlug ?: existing?.episodeSlug ?: ""
                val finalEmbed = embedUrl ?: existing?.embedUrl ?: ""
                val finalPos = positionMs ?: existing?.positionMs ?: 0L
                val finalDur = durationMs ?: existing?.durationMs ?: 0L
                val finalPct = if (finalDur > 0L) {
                    ((finalPos.toDouble() / finalDur.toDouble()) * 100).toInt().coerceIn(0, 100)
                } else (existing?.progressPct ?: 0)

                val cv = ContentValues().apply {
                    put(COL_SLUG, film.slug)
                    put(COL_FILM_NAME, film.name)
                    put(COL_POSTER_URL, film.posterUrl.ifBlank { film.thumbUrl })
                    put(COL_FAV_YEAR, film.year)
                    put(COL_FAV_QUALITY, film.quality)
                    put(COL_EPISODE_NAME, finalEpName)
                    put(COL_EPISODE_SLUG, finalEpSlug)
                    put(COL_EMBED_URL, finalEmbed)
                    put(COL_POSITION_MS, finalPos)
                    put(COL_DURATION_MS, finalDur)
                    put(COL_PROGRESS_PCT, finalPct)
                    put(COL_FAV_ADDED_AT, System.currentTimeMillis())
                }
                db.insertWithOnConflict(TABLE_FAVORITES, null, cv, SQLiteDatabase.CONFLICT_REPLACE)

                // Đồng bộ vào watch_history nếu có tập xác định
                if (finalEpName.isNotBlank() && finalEpSlug.isNotBlank()) {
                    saveProgress(
                        slug = film.slug,
                        filmName = film.name,
                        posterUrl = film.posterUrl.ifBlank { film.thumbUrl },
                        episodeName = finalEpName,
                        episodeSlug = finalEpSlug,
                        embedUrl = finalEmbed,
                        positionMs = finalPos,
                        durationMs = finalDur
                    )
                } else {
                    triggerBackupAsync()
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "toggleFavorite error: ${e.message}")
            false
        }
    }

    /**
     * Lấy danh sách toàn bộ phim đã lưu (mới lưu nhất lên đầu), kèm tập và thời gian xem đầy đủ.
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
                val idxEpName = c.getColumnIndex(COL_EPISODE_NAME)
                val idxEpSlug = c.getColumnIndex(COL_EPISODE_SLUG)
                val idxEmbed = c.getColumnIndex(COL_EMBED_URL)
                val idxPos = c.getColumnIndex(COL_POSITION_MS)
                val idxDur = c.getColumnIndex(COL_DURATION_MS)
                val idxPct = c.getColumnIndex(COL_PROGRESS_PCT)
                val idxAdded = c.getColumnIndex(COL_FAV_ADDED_AT)

                while (c.moveToNext()) {
                    val slug = c.getString(idxSlug)
                    val poster = c.getString(idxPoster).orEmpty()
                    val favEpName = if (idxEpName >= 0) c.getString(idxEpName).orEmpty() else ""
                    val favEpSlug = if (idxEpSlug >= 0) c.getString(idxEpSlug).orEmpty() else ""
                    val favEmbed = if (idxEmbed >= 0) c.getString(idxEmbed).orEmpty() else ""
                    val favPos = if (idxPos >= 0) c.getLong(idxPos) else 0L
                    val favDur = if (idxDur >= 0) c.getLong(idxDur) else 0L
                    val favPct = if (idxPct >= 0) c.getInt(idxPct) else 0
                    val favAdded = if (idxAdded >= 0) c.getLong(idxAdded) else System.currentTimeMillis()

                    // Ưu tiên lấy progress mới nhất từ history, nếu không có thì dùng ngay data lưu trong favorites
                    val histProgress = getProgressForFilm(slug)
                    val finalProgress = histProgress ?: if (favEpName.isNotBlank() || favPos > 0L) {
                        WatchHistoryItem(
                            slug = slug,
                            filmName = c.getString(idxName),
                            posterUrl = poster,
                            episodeName = favEpName.ifBlank { "Tập đã lưu" },
                            episodeSlug = favEpSlug,
                            embedUrl = favEmbed,
                            positionMs = favPos,
                            durationMs = favDur,
                            progressPct = favPct,
                            lastWatchedAt = favAdded,
                            isCompleted = favPct >= 90
                        )
                    } else null

                    list.add(
                        NguoncFilm(
                            slug = slug,
                            name = c.getString(idxName),
                            posterUrl = poster,
                            thumbUrl = poster,
                            year = c.getString(idxYear).orEmpty(),
                            quality = c.getString(idxQuality).orEmpty(),
                            currentEpisode = finalProgress?.episodeName ?: favEpName,
                            watchProgress = finalProgress
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getFavoriteFilms error: ${e.message}")
        }
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
            db.delete(TABLE_FAVORITES, null, null)
            triggerBackupAsync()
        } catch (_: Exception) {}
    }

    // ================= DUAL-LAYER PERSISTENT BACKUP & AUTO RESTORE =================

    /**
     * Kích hoạt sao lưu toàn bộ dữ liệu ra SharedPreferences và file JSON bền vững.
     */
    private fun triggerBackupAsync() {
        ioScope.launch {
            try {
                performBackup()
            } catch (e: Exception) {
                Log.w(TAG, "triggerBackupAsync error: ${e.message}")
            }
        }
    }

    private fun performBackup() {
        val helper = dbHelper ?: return
        val app = appContext ?: return
        try {
            val db = helper.readableDatabase
            val root = JSONObject()
            root.put("version", 2)
            root.put("updated_at", System.currentTimeMillis())

            // 1. Sao lưu favorites
            val favArr = JSONArray()
            val curFav = db.query(TABLE_FAVORITES, null, null, null, null, null, null)
            curFav.use { c ->
                while (c.moveToNext()) {
                    val obj = JSONObject().apply {
                        put(COL_SLUG, c.getString(c.getColumnIndexOrThrow(COL_SLUG)))
                        put(COL_FILM_NAME, c.getString(c.getColumnIndexOrThrow(COL_FILM_NAME)))
                        put(COL_POSTER_URL, c.getString(c.getColumnIndexOrThrow(COL_POSTER_URL)).orEmpty())
                        put(COL_FAV_YEAR, c.getString(c.getColumnIndexOrThrow(COL_FAV_YEAR)).orEmpty())
                        put(COL_FAV_QUALITY, c.getString(c.getColumnIndexOrThrow(COL_FAV_QUALITY)).orEmpty())
                        put(COL_EPISODE_NAME, c.getString(c.getColumnIndexOrThrow(COL_EPISODE_NAME)).orEmpty())
                        put(COL_EPISODE_SLUG, c.getString(c.getColumnIndexOrThrow(COL_EPISODE_SLUG)).orEmpty())
                        put(COL_EMBED_URL, c.getString(c.getColumnIndexOrThrow(COL_EMBED_URL)).orEmpty())
                        put(COL_POSITION_MS, c.getLong(c.getColumnIndexOrThrow(COL_POSITION_MS)))
                        put(COL_DURATION_MS, c.getLong(c.getColumnIndexOrThrow(COL_DURATION_MS)))
                        put(COL_PROGRESS_PCT, c.getInt(c.getColumnIndexOrThrow(COL_PROGRESS_PCT)))
                        put(COL_FAV_ADDED_AT, c.getLong(c.getColumnIndexOrThrow(COL_FAV_ADDED_AT)))
                    }
                    favArr.put(obj)
                }
            }
            root.put("favorites", favArr)

            // 2. Sao lưu history
            val histArr = JSONArray()
            val curHist = db.query(TABLE_HISTORY, null, null, null, null, null, "$COL_LAST_WATCHED_AT DESC", "100")
            curHist.use { c ->
                while (c.moveToNext()) {
                    val obj = JSONObject().apply {
                        put(COL_SLUG, c.getString(c.getColumnIndexOrThrow(COL_SLUG)))
                        put(COL_FILM_NAME, c.getString(c.getColumnIndexOrThrow(COL_FILM_NAME)))
                        put(COL_POSTER_URL, c.getString(c.getColumnIndexOrThrow(COL_POSTER_URL)).orEmpty())
                        put(COL_EPISODE_NAME, c.getString(c.getColumnIndexOrThrow(COL_EPISODE_NAME)))
                        put(COL_EPISODE_SLUG, c.getString(c.getColumnIndexOrThrow(COL_EPISODE_SLUG)))
                        put(COL_EMBED_URL, c.getString(c.getColumnIndexOrThrow(COL_EMBED_URL)))
                        put(COL_POSITION_MS, c.getLong(c.getColumnIndexOrThrow(COL_POSITION_MS)))
                        put(COL_DURATION_MS, c.getLong(c.getColumnIndexOrThrow(COL_DURATION_MS)))
                        put(COL_PROGRESS_PCT, c.getInt(c.getColumnIndexOrThrow(COL_PROGRESS_PCT)))
                        put(COL_LAST_WATCHED_AT, c.getLong(c.getColumnIndexOrThrow(COL_LAST_WATCHED_AT)))
                        put(COL_IS_COMPLETED, c.getInt(c.getColumnIndexOrThrow(COL_IS_COMPLETED)))
                    }
                    histArr.put(obj)
                }
            }
            root.put("history", histArr)

            // 3. Sao lưu episodes
            val epArr = JSONArray()
            val curEp = db.query(TABLE_EPISODES, null, null, null, null, null, "$COL_EP_WATCHED_AT DESC", "200")
            curEp.use { c ->
                while (c.moveToNext()) {
                    val obj = JSONObject().apply {
                        put(COL_EP_FILM_SLUG, c.getString(c.getColumnIndexOrThrow(COL_EP_FILM_SLUG)))
                        put(COL_EP_SLUG, c.getString(c.getColumnIndexOrThrow(COL_EP_SLUG)))
                        put(COL_EP_NAME, c.getString(c.getColumnIndexOrThrow(COL_EP_NAME)).orEmpty())
                        put(COL_EP_EMBED, c.getString(c.getColumnIndexOrThrow(COL_EP_EMBED)).orEmpty())
                        put(COL_EP_POSITION_MS, c.getLong(c.getColumnIndexOrThrow(COL_EP_POSITION_MS)))
                        put(COL_EP_DURATION_MS, c.getLong(c.getColumnIndexOrThrow(COL_EP_DURATION_MS)))
                        put(COL_EP_WATCHED_AT, c.getLong(c.getColumnIndexOrThrow(COL_EP_WATCHED_AT)))
                    }
                    epArr.put(obj)
                }
            }
            root.put("episodes", epArr)

            val jsonString = root.toString()

            // Tầng 1: SharedPreferences
            val sp = app.getSharedPreferences(PREFS_BACKUP, Context.MODE_PRIVATE)
            sp.edit().putString(KEY_VAULT_JSON, jsonString).apply()

            // Tầng 2: Internal storage file
            val internalFile = File(app.filesDir, BACKUP_FILE_NAME)
            internalFile.writeText(jsonString)

            // Tầng 3: External app storage file (Bền vững qua cập nhật Android TV)
            try {
                val extDir = app.getExternalFilesDir(null)
                if (extDir != null) {
                    val extFile = File(extDir, BACKUP_FILE_NAME)
                    extFile.writeText(jsonString)
                }
            } catch (_: Throwable) {}

            // Tầng 4: Downloads Public Folder (Chống mất khi người dùng gỡ bản cũ cài bản mới)
            try {
                val pubDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (pubDir != null && pubDir.exists()) {
                    val pubFile = File(pubDir, BACKUP_FILE_NAME)
                    pubFile.writeText(jsonString)
                }
            } catch (_: Throwable) {}

        } catch (e: Exception) {
            Log.w(TAG, "performBackup failed: ${e.message}")
        }
    }

    /**
     * Tự động khôi phục toàn bộ phim đã lưu, các tập và tiến độ xem nếu SQLite đang trống.
     */
    private fun restoreFromBackupIfNeeded() {
        val helper = dbHelper ?: return
        val app = appContext ?: return
        try {
            val db = helper.readableDatabase
            var favCount = 0
            db.rawQuery("SELECT count(*) FROM $TABLE_FAVORITES", null).use { c ->
                if (c.moveToFirst()) favCount = c.getInt(0)
            }
            var histCount = 0
            db.rawQuery("SELECT count(*) FROM $TABLE_HISTORY", null).use { c ->
                if (c.moveToFirst()) histCount = c.getInt(0)
            }

            // Nếu cơ sở dữ liệu đã có dữ liệu thì không cần khôi phục
            if (favCount > 0 || histCount > 0) return

            // Tìm kiếm nguồn backup theo thứ tự ưu tiên
            var jsonString: String? = null

            // 1. Thử đọc từ Downloads Public (bền vững nhất sau khi gỡ cài lại)
            try {
                val pubDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val pubFile = File(pubDir, BACKUP_FILE_NAME)
                if (pubFile.exists() && pubFile.length() > 10) {
                    jsonString = pubFile.readText()
                }
            } catch (_: Throwable) {}

            // 2. Thử đọc từ External App Storage
            if (jsonString.isNullOrBlank()) {
                try {
                    val extDir = app.getExternalFilesDir(null)
                    val extFile = File(extDir, BACKUP_FILE_NAME)
                    if (extFile.exists() && extFile.length() > 10) {
                        jsonString = extFile.readText()
                    }
                } catch (_: Throwable) {}
            }

            // 3. Thử đọc từ Internal Storage File
            if (jsonString.isNullOrBlank()) {
                val internalFile = File(app.filesDir, BACKUP_FILE_NAME)
                if (internalFile.exists() && internalFile.length() > 10) {
                    jsonString = internalFile.readText()
                }
            }

            // 4. Thử đọc từ SharedPreferences
            if (jsonString.isNullOrBlank()) {
                val sp = app.getSharedPreferences(PREFS_BACKUP, Context.MODE_PRIVATE)
                jsonString = sp.getString(KEY_VAULT_JSON, null)
            }

            if (jsonString.isNullOrBlank()) return

            val root = JSONObject(jsonString)
            val writeDb = helper.writableDatabase

            writeDb.beginTransaction()
            try {
                // Khôi phục favorites
                val favArr = root.optJSONArray("favorites")
                if (favArr != null) {
                    for (i in 0 until favArr.length()) {
                        val o = favArr.getJSONObject(i)
                        val cv = ContentValues().apply {
                            put(COL_SLUG, o.optString(COL_SLUG))
                            put(COL_FILM_NAME, o.optString(COL_FILM_NAME))
                            put(COL_POSTER_URL, o.optString(COL_POSTER_URL))
                            put(COL_FAV_YEAR, o.optString(COL_FAV_YEAR))
                            put(COL_FAV_QUALITY, o.optString(COL_FAV_QUALITY))
                            put(COL_EPISODE_NAME, o.optString(COL_EPISODE_NAME))
                            put(COL_EPISODE_SLUG, o.optString(COL_EPISODE_SLUG))
                            put(COL_EMBED_URL, o.optString(COL_EMBED_URL))
                            put(COL_POSITION_MS, o.optLong(COL_POSITION_MS, 0L))
                            put(COL_DURATION_MS, o.optLong(COL_DURATION_MS, 0L))
                            put(COL_PROGRESS_PCT, o.optInt(COL_PROGRESS_PCT, 0))
                            put(COL_FAV_ADDED_AT, o.optLong(COL_FAV_ADDED_AT, System.currentTimeMillis()))
                        }
                        writeDb.insertWithOnConflict(TABLE_FAVORITES, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                }

                // Khôi phục history
                val histArr = root.optJSONArray("history")
                if (histArr != null) {
                    for (i in 0 until histArr.length()) {
                        val o = histArr.getJSONObject(i)
                        val cv = ContentValues().apply {
                            put(COL_SLUG, o.optString(COL_SLUG))
                            put(COL_FILM_NAME, o.optString(COL_FILM_NAME))
                            put(COL_POSTER_URL, o.optString(COL_POSTER_URL))
                            put(COL_EPISODE_NAME, o.optString(COL_EPISODE_NAME))
                            put(COL_EPISODE_SLUG, o.optString(COL_EPISODE_SLUG))
                            put(COL_EMBED_URL, o.optString(COL_EMBED_URL))
                            put(COL_POSITION_MS, o.optLong(COL_POSITION_MS, 0L))
                            put(COL_DURATION_MS, o.optLong(COL_DURATION_MS, 0L))
                            put(COL_PROGRESS_PCT, o.optInt(COL_PROGRESS_PCT, 0))
                            put(COL_LAST_WATCHED_AT, o.optLong(COL_LAST_WATCHED_AT, System.currentTimeMillis()))
                            put(COL_IS_COMPLETED, o.optInt(COL_IS_COMPLETED, 0))
                        }
                        writeDb.insertWithOnConflict(TABLE_HISTORY, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                }

                // Khôi phục episodes
                val epArr = root.optJSONArray("episodes")
                if (epArr != null) {
                    for (i in 0 until epArr.length()) {
                        val o = epArr.getJSONObject(i)
                        val cv = ContentValues().apply {
                            put(COL_EP_FILM_SLUG, o.optString(COL_EP_FILM_SLUG))
                            put(COL_EP_SLUG, o.optString(COL_EP_SLUG))
                            put(COL_EP_NAME, o.optString(COL_EP_NAME))
                            put(COL_EP_EMBED, o.optString(COL_EP_EMBED))
                            put(COL_EP_POSITION_MS, o.optLong(COL_EP_POSITION_MS, 0L))
                            put(COL_EP_DURATION_MS, o.optLong(COL_EP_DURATION_MS, 0L))
                            put(COL_EP_WATCHED_AT, o.optLong(COL_EP_WATCHED_AT, System.currentTimeMillis()))
                        }
                        writeDb.insertWithOnConflict(TABLE_EPISODES, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                }

                writeDb.setTransactionSuccessful()
                Log.i(TAG, "Phục hồi thành công kho phim và lịch sử xem từ bản sao lưu bền vững!")
            } finally {
                writeDb.endTransaction()
            }
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromBackupIfNeeded error: ${e.message}")
        }
    }
}
