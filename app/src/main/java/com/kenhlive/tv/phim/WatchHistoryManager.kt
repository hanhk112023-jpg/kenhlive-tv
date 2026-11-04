package com.kenhlive.tv.phim

import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private const val SNAPSHOT_FILE_NAME = "kenhlive_cinema_snapshot.db"

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var dbHelper: DbHelper? = null

    @Volatile
    private var appContext: Context? = null

    // ===== Chống "backup storm" (v7.13) =====
    // Lưu tiến độ chỉ ghi SQLite (nhẹ) + đánh dấu dirty. Sao lưu snapshot .db bị debounce:
    // tối đa 1 lần / 5 phút, và xả ngay (flush) khi toàn bộ Activity dừng (app xuống nền/đóng).
    // Mọi lần chạy backup đều đi qua backupLock để không có 2 snapshot ghi xen kẽ cùng file.
    private const val BACKUP_MIN_INTERVAL_MS = 5 * 60 * 1000L
    private const val MAX_EPISODES_PER_FILM = 500

    private val backupLock = Any()

    @Volatile
    private var backupDirty = false

    @Volatile
    private var lastBackupAtMs = 0L

    private var backupJob: Job? = null

    @Volatile
    private var lifecycleFlushRegistered = false

    private var startedActivities = 0

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
        registerBackgroundFlush(app)
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

            // 4. Lịch sử phim: KHÔNG cắt ngầm nữa (v7.13). Trước đây saveProgress âm thầm xóa
            //    history ngoài 200 phim gần nhất, mâu thuẫn với cam kết "sao lưu đầy đủ".
            //    Chỉ khống chế bảng episodes (bảng phình nhanh nhất): giữ tối đa
            //    MAX_EPISODES_PER_FILM tập gần nhất cho chính phim vừa xem.
            db.execSQL(
                """
                DELETE FROM $TABLE_EPISODES WHERE $COL_EP_FILM_SLUG = ? AND $COL_EP_SLUG NOT IN (
                    SELECT $COL_EP_SLUG FROM $TABLE_EPISODES
                    WHERE $COL_EP_FILM_SLUG = ?
                    ORDER BY $COL_EP_WATCHED_AT DESC LIMIT $MAX_EPISODES_PER_FILM
                )
                """.trimIndent(),
                arrayOf<Any>(slug, slug)
            )

            // 5. Chỉ đánh dấu cần sao lưu (debounce — xem triggerBackupAsync), KHÔNG xuất JSON tại đây
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
     * Lấy toàn bộ danh sách slug tập phim đã từng xem của một bộ phim (Single Query, siêu tốc).
     */
    suspend fun getWatchedEpisodeSlugs(filmSlug: String): Set<String> = withContext(Dispatchers.IO) {
        val helper = dbHelper ?: return@withContext emptySet()
        try {
            val db = helper.readableDatabase
            val cursor = db.query(
                TABLE_EPISODES,
                arrayOf(COL_EP_SLUG),
                "$COL_EP_FILM_SLUG = ?",
                arrayOf(filmSlug),
                null,
                null,
                null
            )
            val set = mutableSetOf<String>()
            cursor.use { c ->
                val slugIdx = c.getColumnIndex(COL_EP_SLUG)
                while (c.moveToNext()) {
                    if (slugIdx >= 0) {
                        set.add(c.getString(slugIdx))
                    }
                }
            }
            set
        } catch (e: Exception) {
            emptySet()
        }
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
     * Xóa hoàn toàn lịch sử và tiến độ xem của một phim (cả DB history, episodes, favorites và backup vault).
     */
    suspend fun deleteProgress(slug: String) = withContext(Dispatchers.IO) {
        val helper = dbHelper ?: return@withContext
        try {
            val db = helper.writableDatabase
            // 1. Xóa khỏi TABLE_HISTORY (tiến độ xem dở & hoàn thành)
            db.delete(TABLE_HISTORY, "$COL_SLUG = ?", arrayOf(slug))
            // 2. Xóa khỏi TABLE_EPISODES (danh sách các tập đã xem của phim)
            db.delete(TABLE_EPISODES, "$COL_EP_FILM_SLUG = ?", arrayOf(slug))
            // 3. Xóa sạch tiến độ tập xem trong TABLE_FAVORITES nếu phim đã lưu
            val cv = ContentValues().apply {
                putNull(COL_EPISODE_NAME)
                putNull(COL_EPISODE_SLUG)
                putNull(COL_EMBED_URL)
                put(COL_POSITION_MS, 0L)
                put(COL_DURATION_MS, 0L)
                put(COL_PROGRESS_PCT, 0)
            }
            db.update(TABLE_FAVORITES, cv, "$COL_SLUG = ?", arrayOf(slug))
            // 4. Xả vault NGAY (không chờ debounce 5 phút / xuống nền): nếu app bị
            // force-stop hoặc máy mất điện trước lần xả nền, vault cũ (kể cả bản ở
            // Downloads còn qua gỡ cài) sẽ khôi phục lại đúng phim vừa xoá ở lần mở
            // sau khi bảng đang trống — xoá xong phim "hồi sinh". (v0.1.4)
            flushBackupNow()
            Log.d(TAG, "Đã xóa hoàn tất lịch sử xem của phim: $slug")
        } catch (e: Exception) {
            Log.e(TAG, "deleteProgress error: ${e.message}")
        }
    }

    /**
     * Xóa bất đồng bộ tiến độ phim với callback tùy chọn.
     */
    fun deleteProgressAsync(slug: String, onDone: (() -> Unit)? = null) {
        ioScope.launch {
            deleteProgress(slug)
            withContext(Dispatchers.Main) {
                onDone?.invoke()
            }
        }
    }

    /**
     * Kiểm tra phim có tiến độ xem trong lịch sử không.
     */
    suspend fun hasProgress(slug: String): Boolean = withContext(Dispatchers.IO) {
        val helper = dbHelper ?: return@withContext false
        try {
            val db = helper.readableDatabase
            val cursor = db.query(TABLE_HISTORY, arrayOf(COL_SLUG), "$COL_SLUG = ?", arrayOf(slug), null, null, null, "1")
            cursor.use { it.count > 0 }
        } catch (_: Exception) {
            false
        }
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
            // Xả vault ngay như deleteProgress — tránh vault cũ khôi phục lại toàn bộ
            // lịch sử vừa xoá khi app bị tắt đột ngột trước lần xả nền. (v0.1.4)
            flushBackupNow()
        } catch (_: Exception) {}
    }

    // ================= SQLITE SNAPSHOT BACKUP & AUTO RESTORE (v0.1.5 — thay vault JSON) =================

    /**
     * Đánh dấu dữ liệu đã thay đổi và lên lịch sao lưu JSON đầy đủ (DEBOUNCE).
     *
     * Trước đây mỗi lần lưu tiến độ (3–5 giây/lần khi đang phát) đều xuất toàn bộ DB ra JSON
     * và ghi 4 nơi — "backup storm" mài mòn flash + rủi ro ghi xen kẽ làm rách vault.
     * Nay: chỉ đặt cờ dirty; backup thật chạy tối đa 1 lần / [BACKUP_MIN_INTERVAL_MS],
     * hoặc ngay khi app xuống nền/đóng hẳn (xem [registerBackgroundFlush] / [flushBackupAsync]).
     */
    private fun triggerBackupAsync() {
        backupDirty = true
        synchronized(backupLock) {
            if (backupJob?.isActive == true) return
            val waitMs = (BACKUP_MIN_INTERVAL_MS - (System.currentTimeMillis() - lastBackupAtMs))
                .coerceAtLeast(0L)
            backupJob = ioScope.launch {
                if (waitMs > 0L) delay(waitMs)
                runBackupIfDirty()
            }
        }
    }

    /** Xả sao lưu ngay bất kể debounce (dùng khi app xuống nền/đóng hoặc nơi gọi cần bền vững tức thì). */
    fun flushBackupAsync() {
        ioScope.launch { runBackupIfDirty() }
    }

    /**
     * Xả sao lưu ĐỒNG BỘ ngay tại thread đang gọi (phải đang ở IO): dùng cho các thao
     * tác xoá dữ liệu — vault phải phản ánh trạng thái sau xoá trước khi hàm xoá
     * trả về, nếu không một lần tắt app đột ngột sẽ để lại vault cũ và mục vừa xoá
     * bị khôi phục ở lần mở sau. (v0.1.4)
     */
    private fun flushBackupNow() {
        backupDirty = true
        runBackupIfDirty()
    }

    /**
     * Chạy backup nếu có thay đổi chưa sao lưu. Toàn bộ thân hàm nằm trong [backupLock]
     * nên không bao giờ có 2 snapshot chạy đồng thời/ghi xen kẽ cùng một file.
     */
    private fun runBackupIfDirty() {
        synchronized(backupLock) {
            if (!backupDirty) return
            backupDirty = false
            lastBackupAtMs = System.currentTimeMillis()
            try {
                snapshotDatabase()
            } catch (e: Exception) {
                // Backup lỗi thì đánh dấu lại dirty để lần kích hoạt sau thử lại
                backupDirty = true
                Log.w(TAG, "runBackupIfDirty error: ${e.message}")
            }
        }
    }

    /**
     * Đăng ký xả backup khi TOÀN BỘ Activity đã dừng (app xuống nền / bị đóng) —
     * thời điểm "dừng hẳn" chuẩn để chốt vault mà không cần sửa từng Activity.
     */
    private fun registerBackgroundFlush(app: Context) {
        if (lifecycleFlushRegistered) return
        val application = app as? Application ?: return
        lifecycleFlushRegistered = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                if (startedActivities <= 0) {
                    startedActivities = 0
                    flushBackupAsync()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Sao chép file nhị phân kiểu nguyên tử (tmp + rename) để snapshot không bao giờ bị rách giữa chừng khi mất điện/sập app. */
    private fun copyFileAtomic(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        src.inputStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
        if (!tmp.renameTo(dst)) {
            tmp.copyTo(dst, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * Sao lưu kiểu SNAPSHOT nhị phân (v0.1.5): sao chép thẳng file SQLite .db thay vì
     * xuất toàn bộ dữ liệu ra JSON như trước. JSON phình rất nhanh (mỗi dòng lặp lại
     * toàn bộ tên cột) và mỗi lần sao lưu phải serialize/parse toàn bảng; bản sao .db
     * nhỏ hơn nhiều, chép xong trong một lần đọc/ghi tuần tự, khôi phục là mở lại
     * đúng file đó. Hai đích: thư mục nội bộ (khôi phục nhanh) + Downloads (sống sót
     * qua gỡ cài). Bỏ hẳn bản sao SharedPreferences và external-app của vault JSON cũ.
     */
    private fun snapshotDatabase() {
        val helper = dbHelper ?: return
        val app = appContext ?: return
        try {
            val dbFile = app.getDatabasePath(DB_NAME)
            if (!dbFile.exists() || dbFile.length() == 0L) return
            // Chốt dữ liệu xuống file chính nếu đang dùng WAL + thu hồi bớt trang trống
            try {
                val db = helper.writableDatabase
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)?.use { it.moveToFirst() }
                db.execSQL("PRAGMA incremental_vacuum(128)")
            } catch (_: Throwable) {}
            copyFileAtomic(dbFile, File(app.filesDir, SNAPSHOT_FILE_NAME))
            try {
                val pubDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (pubDir != null && pubDir.exists()) {
                    copyFileAtomic(dbFile, File(pubDir, SNAPSHOT_FILE_NAME))
                }
            } catch (_: Throwable) {}
            cleanupLegacyJsonVault(app)
        } catch (e: Exception) {
            Log.w(TAG, "snapshotDatabase failed: ${e.message}")
        }
    }

    /** Đếm nhanh xem DB đã có dữ liệu chưa (điều kiện để bỏ qua khôi phục). */
    private fun databaseHasData(): Boolean {
        val helper = dbHelper ?: return false
        return try {
            val db = helper.readableDatabase
            var total = 0
            db.rawQuery("SELECT count(*) FROM $TABLE_FAVORITES", null).use { c -> if (c.moveToFirst()) total += c.getInt(0) }
            db.rawQuery("SELECT count(*) FROM $TABLE_HISTORY", null).use { c -> if (c.moveToFirst()) total += c.getInt(0) }
            total > 0
        } catch (_: Exception) { false }
    }

    /**
     * Khôi phục từ snapshot .db: đóng DB hiện tại (đang trống), chép file snapshot đè
     * lên vị trí DB rồi để SQLiteOpenHelper mở lại ở lần truy cập kế tiếp. Chỉ nhận
     * snapshot có user_version <= DB_VERSION hiện tại (tránh hạ cấp schema khi người
     * dùng cài bản cũ hơn bản đã tạo snapshot).
     */
    private fun restoreFromSnapshot(): Boolean {
        val helper = dbHelper ?: return false
        val app = appContext ?: return false
        val candidates = mutableListOf<File>()
        candidates += File(app.filesDir, SNAPSHOT_FILE_NAME)
        try {
            val pubDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (pubDir != null) candidates += File(pubDir, SNAPSHOT_FILE_NAME)
        } catch (_: Throwable) {}
        val srcFile = candidates.firstOrNull { it.exists() && it.length() > 0L } ?: return false
        try {
            val probe = SQLiteDatabase.openDatabase(srcFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val v = probe.version
            probe.close()
            if (v > DB_VERSION) {
                Log.w(TAG, "Snapshot version $v > DB_VERSION $DB_VERSION — bỏ qua khôi phục snapshot")
                return false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Snapshot không đọc được (${e.message}) — bỏ qua")
            return false
        }
        return try {
            val dbFile = app.getDatabasePath(DB_NAME)
            helper.close()
            for (suffix in listOf("-wal", "-shm", "-journal")) {
                try { File(dbFile.absolutePath + suffix).delete() } catch (_: Throwable) {}
            }
            dbFile.parentFile?.mkdirs()
            copyFileAtomic(srcFile, dbFile)
            Log.i(TAG, "Đã khôi phục dữ liệu từ snapshot SQLite (${srcFile.name}, ${srcFile.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromSnapshot error: ${e.message}")
            false
        }
    }

    /** Xoá tàn dư vault JSON cũ (3 file + SharedPreferences) sau khi đã có snapshot. */
    private fun cleanupLegacyJsonVault(app: Context) {
        try { File(app.filesDir, BACKUP_FILE_NAME).delete() } catch (_: Throwable) {}
        try {
            val extDir = app.getExternalFilesDir(null)
            if (extDir != null) File(extDir, BACKUP_FILE_NAME).delete()
        } catch (_: Throwable) {}
        try {
            val pubDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (pubDir != null) File(pubDir, BACKUP_FILE_NAME).delete()
        } catch (_: Throwable) {}
        try {
            app.getSharedPreferences(PREFS_BACKUP, Context.MODE_PRIVATE).edit().remove(KEY_VAULT_JSON).apply()
        } catch (_: Throwable) {}
    }

    /**
     * Điểm vào khôi phục khi mở app (chỉ chạy khi DB đang trống): ưu tiên snapshot .db;
     * nếu chưa có snapshot (người dùng nâng cấp từ bản vault JSON) thì nhập JSON cũ
     * MỘT LẦN rồi chụp snapshot mới và dọn vault JSON.
     */
    private fun restoreFromBackupIfNeeded() {
        if (databaseHasData()) return
        if (restoreFromSnapshot()) {
            appContext?.let { cleanupLegacyJsonVault(it) }
            return
        }
        importLegacyJsonVaultIfNeeded()
        if (databaseHasData()) {
            snapshotDatabase()
            // Sau migration, dọn vault JSON cũ ở mọi nơi, kể cả Downloads
            // công cộng (cleanupLegacyJsonVault xóa cả 3 file + SharedPreferences).
            appContext?.let { cleanupLegacyJsonVault(it) }
        }
    }

    /**
     * NHẬP MỘT LẦN từ vault JSON cũ (chỉ còn dùng khi nâng cấp từ bản trước v0.1.5 mà
     * chưa từng có snapshot .db): chỉ đọc từ thư mục app-private và
     * SharedPreferences, KHÔNG đọc từ Downloads công cộng (Tier 1: lịch sử xem
     * không được đọc từ thư mục công cộng mà app khác có thể đọc/ghi).
     * Xong việc, bản snapshot .db sẽ thay thế hẳn cơ chế này.
     */
    private fun importLegacyJsonVaultIfNeeded() {
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

            // Tìm kiếm nguồn backup theo thứ tự ưu tiên — chỉ app-private,
            // không còn bước đọc từ Downloads công cộng (Tier 1).
            var jsonString: String? = null

            // 1. Thử đọc từ External App Storage
            if (jsonString.isNullOrBlank()) {
                try {
                    val extDir = app.getExternalFilesDir(null)
                    val extFile = File(extDir, BACKUP_FILE_NAME)
                    if (extFile.exists() && extFile.length() > 10) {
                        jsonString = extFile.readText()
                    }
                } catch (_: Throwable) {}
            }

            // 2. Thử đọc từ Internal Storage File
            if (jsonString.isNullOrBlank()) {
                val internalFile = File(app.filesDir, BACKUP_FILE_NAME)
                if (internalFile.exists() && internalFile.length() > 10) {
                    jsonString = internalFile.readText()
                }
            }

            // 3. Thử đọc từ SharedPreferences
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
