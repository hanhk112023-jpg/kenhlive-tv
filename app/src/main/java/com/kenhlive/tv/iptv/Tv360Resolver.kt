package com.kenhlive.tv.iptv

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Tv360Resolver: Giải mã trực tiếp luồng phát truyền hình TV360 trên thiết bị TV.
 *
 * Cơ chế hoạt động:
 * 1. Nhận URI kênh: "tv360://{id}" hoặc URL TV360.
 * 2. Gọi endpoint lấy stream: https://tv360.vn/public/v1/composite/get-link với headers chuẩn Web Client.
 * 3. Dữ liệu trả về được mã hóa AES-128-ECB với secret key từ frontend TV360.
 * 4. Giải mã payload JSON để lấy urlStreaming (HLS .m3u8 CDN Viettel).
 * 5. Bộ nhớ đệm RAM lưu token phát trong 45 phút để chuyển kênh tức thì (0ms latency).
 * 6. Có danh sách fallback HLS trực tiếp nếu kênh yêu cầu tài khoản VIP/đăng nhập.
 */
object Tv360Resolver {
    private const val TAG = "Tv360Resolver"
    private const val SECRET_KEY_SEED = "eNdtOeNDeNcRyPteDsCREt#2022"
    private const val CACHE_TTL_MS = 45 * 60 * 1000L // 45 phút

    // Bộ nhớ đệm: channelId -> Pair(playableUrl, expireTimestamp)
    private val streamCache = ConcurrentHashMap<String, Pair<String, Long>>()

    // Fallback stream URLs cho các kênh yêu cầu đăng nhập trên TV360
    private val FALLBACK_STREAMS = mapOf(
        "20" to "https://liveh12.vtvprime.vn/hls/ANNINHTV/index.m3u8",
        "19" to "https://liveh12.vtvprime.vn/hls/QPTV/index.m3u8",
        "10043" to "https://live.canthotv.vn/live/tv/chunklist.m3u8",
        "98" to "https://live.canthotv.vn/live/tv/chunklist.m3u8",
        "33" to "https://liveh34.vtvprime.vn/hls/HANOI1TV/index.m3u8",
        "9" to "https://freem3u.xyz/api/live/play.m3u8?vid=9",
        "26" to "https://1011154949.vnns.net/CDN-FPT02/THVL2-HD-1080p/playlist.m3u8"
    )

    /** Cung cấp luồng dự phòng nếu luồng hiện tại gặp sự cố (Playback error) */
    fun getBackupForChannel(url: String): String? {
        val chId = extractChannelId(url)
        FALLBACK_STREAMS[chId]?.let { if (it != url) return it }
        if (url.contains("cantho", ignoreCase = true) || url.contains("vtv10", ignoreCase = true) || chId == "98" || chId == "10043") {
            val target = "https://live.canthotv.vn/live/tv/chunklist.m3u8"
            if (target != url) return target
        }
        if (url.contains("anninh", ignoreCase = true) || chId == "20") {
            val target = "https://liveh12.vtvprime.vn/hls/ANNINHTV/index.m3u8"
            if (target != url) return target
        }
        return null
    }

    /** Kiểm tra xem URL có phải kênh TV360 cần resolve hay không */
    fun isTv360(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("tv360://") || (u.contains("tv360.vn") && u.contains("ch="))
    }

    /** Trích xuất channelId từ URL */
    fun extractChannelId(url: String): String {
        val trimmed = url.trim()
        if (trimmed.startsWith("tv360://", ignoreCase = true)) {
            val path = trimmed.substring(8)
            val qIdx = path.indexOf('?')
            return if (qIdx != -1) path.substring(0, qIdx) else path
        }
        val chIdx = trimmed.indexOf("ch=")
        if (chIdx != -1) {
            val sub = trimmed.substring(chIdx + 3)
            val ampIdx = sub.indexOf('&')
            return if (ampIdx != -1) sub.substring(0, ampIdx) else sub
        }
        return trimmed
    }

    /** Resolve URL kênh TV360 thành link HLS m3u8 có thể phát trên ExoPlayer */
    suspend fun resolve(rawUrl: String): String? = withContext(Dispatchers.IO) {
        val channelId = extractChannelId(rawUrl)
        if (channelId.isBlank()) return@withContext null

        val now = System.currentTimeMillis()
        val cached = streamCache[channelId]
        if (cached != null && cached.second > now) {
            Log.d(TAG, "Cache hit for channel $channelId: ${cached.first}")
            return@withContext cached.first
        }

        try {
            val resolvedUrl = fetchStreamFromApi(channelId)
            if (!resolvedUrl.isNullOrBlank()) {
                streamCache[channelId] = Pair(resolvedUrl, now + CACHE_TTL_MS)
                Log.d(TAG, "Successfully resolved TV360 channel $channelId -> $resolvedUrl")
                return@withContext resolvedUrl
            }
        } catch (e: Exception) {
            Log.w(TAG, "TV360 API resolution failed for channel $channelId: ${e.message}")
        }

        // Nếu TV360 trả về lỗi hoặc cần đăng nhập, dùng link fallback chất lượng cao
        val fallback = FALLBACK_STREAMS[channelId]
        if (!fallback.isNullOrBlank()) {
            Log.i(TAG, "Using verified fallback stream for channel $channelId: $fallback")
            return@withContext fallback
        }

        null
    }

    /** Gọi API get-link của TV360 và giải mã AES */
    private fun fetchStreamFromApi(channelId: String): String? {
        val timestamp = System.currentTimeMillis() / 1000
        val deviceId = UUID.randomUUID().toString().replace("-", "").take(32)
        val apiUrl = "https://tv360.vn/public/v1/composite/get-link?id=$channelId&type=LIVE&mod=LIVE&subInfo=3&t=$timestamp&secured=true"

        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 6000
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Origin", "https://tv360.vn")
            setRequestProperty("Referer", "https://tv360.vn/")
            setRequestProperty("osapptype", "WEB")
            setRequestProperty("osappversion", "2.0.0-6fec7c6eb")
            setRequestProperty("devicetype", "WEB")
            setRequestProperty("devicename", "Chrome")
            setRequestProperty("deviceid", deviceId)
            setRequestProperty("lang", "vi")
            setRequestProperty("requestid", "WEB_$timestamp")
            setRequestProperty("tv360transid", timestamp.toString())
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            Log.w(TAG, "TV360 get-link returned HTTP $code")
            return null
        }

        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(responseText)
        if (json.optInt("errorCode") != 200) {
            Log.w(TAG, "TV360 get-link errorCode: ${json.optInt("errorCode")}, msg: ${json.optString("message")}")
            return null
        }

        val encData = json.optString("data", "")
        if (encData.isEmpty()) return null

        val decryptedPayload = decryptAes(encData) ?: return null
        val streamJson = JSONObject(decryptedPayload)
        val urlStreaming = streamJson.optString("urlStreaming", "")
        return if (urlStreaming.isNotEmpty()) urlStreaming else null
    }

    /** Giải mã AES-128-ECB với secret key seed */
    private fun decryptAes(encryptedBase64: String): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val hash = md.digest(SECRET_KEY_SEED.toByteArray(StandardCharsets.UTF_8))
            val hexString = StringBuilder()
            for (b in hash) {
                val hex = Integer.toHexString(0xff and b.toInt())
                if (hex.length == 1) hexString.append('0')
                hexString.append(hex)
            }
            val keyHex32 = hexString.substring(0, 32)
            val keyBytes = ByteArray(16)
            for (i in 0 until 16) {
                keyBytes[i] = keyHex32.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }

            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)

            val decodedBytes = Base64.decode(encryptedBase64, Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            String(decryptedBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "AES decryption failed: ${e.message}")
            null
        }
    }
}
