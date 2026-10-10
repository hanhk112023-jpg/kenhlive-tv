package com.kenhlive.tv.iptv

import android.content.Context
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

data class EpgProgram(
    val title: String,
    val startTime: Long, // epoch ms
    val stopTime: Long,  // epoch ms
    val desc: String = ""
) {
    fun progressPercent(): Int {
        val now = System.currentTimeMillis()
        if (now <= startTime) return 0
        if (now >= stopTime) return 100
        val duration = stopTime - startTime
        return if (duration > 0) (((now - startTime) * 100) / duration).toInt() else 0
    }

    fun timeRange(): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = com.kenhlive.tv.DataNormalizer.TZ_VN }
        return "${sdf.format(Date(startTime))} - ${sdf.format(Date(stopTime))}"
    }

    fun startFormatted(): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = com.kenhlive.tv.DataNormalizer.TZ_VN }
        return sdf.format(Date(startTime))
    }
}

object EpgRepository {
    private const val TAG = "EpgRepository"
    private const val EPG_URL = "https://epgshare01.online/epgshare01/epg_ripper_VN1.xml.gz"
    private const val CACHE_FILE = "epg_vn.xml.gz"
    private const val EXPIRATION_MS = 6 * 3600_000L // 6 tiếng

    // Key đã chuẩn hóa -> Danh sách chương trình sắp xếp theo startTime
    private val channelPrograms = ConcurrentHashMap<String, List<EpgProgram>>()
    var isLoaded = false
        private set

    /**
     * Tải và phân tích EPG trong luồng IO (chỉ giữ các chương trình từ 3h trước đến 24h tới)
     */
    suspend fun initEpg(context: Context, forceRefresh: Boolean = false) = withContext(Dispatchers.IO) {
        val cacheFile = File(context.cacheDir, CACHE_FILE)
        val isExpired = !cacheFile.exists() || (System.currentTimeMillis() - cacheFile.lastModified() > EXPIRATION_MS)

        if (forceRefresh || isExpired) {
            val downloaded = downloadEpg(cacheFile)
            if (!downloaded && !cacheFile.exists()) {
                Log.w(TAG, "Không tải được EPG và không có cache.")
                return@withContext
            }
        }

        if (cacheFile.exists()) {
            parseEpgFile(cacheFile)
        }
    }

    private fun downloadEpg(targetFile: File): Boolean {
        return try {
            val url = URL(EPG_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 12000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV; KenhLive)")
            if (conn.responseCode in 200..299) {
                conn.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi tải EPG: ${e.message}")
            false
        }
    }

    private fun parseEpgFile(file: File) {
        try {
            val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
            val now = System.currentTimeMillis()
            val minTime = now - 3 * 3600_000L      // 3 tiếng trước
            val maxTime = now + 24 * 3600_000L     // 24 tiếng tới

            val tempMap = mutableMapOf<String, MutableList<EpgProgram>>()

            FileInputStream(file).use { fis ->
                GZIPInputStream(fis).use { gzis ->
                    val parser = Xml.newPullParser()
                    parser.setInput(gzis, "UTF-8")
                    var eventType = parser.eventType

                    var currentChannel = ""
                    var currentStart = 0L
                    var currentStop = 0L
                    var currentTitle = ""
                    var currentDesc = ""
                    var inProgramme = false

                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        when (eventType) {
                            XmlPullParser.START_TAG -> {
                                when (parser.name) {
                                    "programme" -> {
                                        inProgramme = true
                                        currentChannel = parser.getAttributeValue(null, "channel") ?: ""
                                        val startStr = parser.getAttributeValue(null, "start") ?: ""
                                        val stopStr = parser.getAttributeValue(null, "stop") ?: ""
                                        currentStart = try { dateFormat.parse(startStr)?.time ?: 0L } catch (_: Exception) { 0L }
                                        currentStop = try { dateFormat.parse(stopStr)?.time ?: 0L } catch (_: Exception) { 0L }
                                        currentTitle = ""
                                        currentDesc = ""
                                    }
                                    "title" -> {
                                        if (inProgramme) currentTitle = parser.nextText().trim()
                                    }
                                    "desc" -> {
                                        if (inProgramme) currentDesc = parser.nextText().trim()
                                    }
                                }
                            }
                            XmlPullParser.END_TAG -> {
                                if (parser.name == "programme") {
                                    inProgramme = false
                                    // Chỉ giữ các chương trình còn hiệu lực gần hiện tại
                                    if (currentChannel.isNotEmpty() && currentStop > minTime && currentStart < maxTime) {
                                        val normId = normalizeKey(currentChannel)
                                        val prog = EpgProgram(currentTitle, currentStart, currentStop, currentDesc)
                                        tempMap.getOrPut(normId) { mutableListOf() }.add(prog)
                                    }
                                }
                            }
                        }
                        eventType = parser.next()
                    }
                }
            }

            channelPrograms.clear()
            tempMap.forEach { (key, list) ->
                channelPrograms[key] = list.sortedBy { it.startTime }
            }
            isLoaded = true
            Log.i(TAG, "Đã nạp thành công EPG cho ${channelPrograms.size} kênh!")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi phân tích EPG: ${e.message}")
        }
    }

    /**
     * Tra cứu chương trình đang phát (Now) và kế tiếp (Next)
     */
    fun getCurrentAndNext(channelId: String, channelName: String): Pair<EpgProgram?, EpgProgram?>? {
        val list = findProgramsForChannel(channelId, channelName) ?: return null
        val now = System.currentTimeMillis()

        var current: EpgProgram? = null
        var next: EpgProgram? = null

        for (i in list.indices) {
            val p = list[i]
            if (now in p.startTime until p.stopTime) {
                current = p
                if (i + 1 < list.size) {
                    next = list[i + 1]
                }
                break
            } else if (p.startTime > now) {
                // Nếu không có chương trình đang phát đúng khung giờ, lấy chương trình kế tiếp gần nhất
                if (next == null) next = p
            }
        }

        return if (current != null || next != null) Pair(current, next) else null
    }

    /**
     * Tìm danh sách chương trình theo id hoặc tên kênh linh hoạt
     */
    fun findProgramsForChannel(channelId: String, channelName: String): List<EpgProgram>? {
        val key1 = normalizeKey(channelId)
        channelPrograms[key1]?.let { return it }

        val key2 = normalizeKey(channelName)
        channelPrograms[key2]?.let { return it }

        // Thử tìm theo từ khóa chính (vd: vtv1, htv7, thvl1)
        for ((k, list) in channelPrograms) {
            if (k.isNotEmpty() && (key2.contains(k) || k.contains(key2))) {
                return list
            }
        }
        return null
    }

    /**
     * Chuẩn hóa tên kênh để so khớp (bỏ dấu, bỏ khoảng trắng, bỏ hậu tố HD/SD/VN)
     */
    fun normalizeKey(raw: String): String = com.kenhlive.tv.DataNormalizer.normalizeChannelKey(raw)
}
