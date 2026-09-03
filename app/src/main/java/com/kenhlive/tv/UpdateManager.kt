package com.kenhlive.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Update in-app: kiểm tra GitHub Release → tải APK bằng OkHttp streaming → tự mở màn cài đặt.
 * v4.9: bỏ DownloadManager (system process không đi qua proxy → treo vĩnh viễn trên TV/emulator).
 *
 * FIX crash v4.7.2:
 *  - Dialog PHẢI dùng Activity context (bản cũ truyền applicationContext → BadTokenException
 *    "văng app khi ấn TẢI NGAY"). Mọi UI path giờ nhận Activity + check isFinishing.
 *  - Persist dl_path vào SharedPreferences → vẫn mở installer nếu app bị kill giữa lúc tải.
 *  - Mở installer thử ACTION_VIEW rồi ACTION_INSTALL_PACKAGE, fail thì hướng dẫn thủ công.
 */
object UpdateManager {

    private const val PREFS = "update_state"
    private const val KEY_DL_PATH = "dl_path"
    private const val KEY_DL_VER = "dl_ver"
    @Volatile private var openedFor = -1L
    private val openLock = Any()

    /** Mở installer ĐÚNG 1 lần cho 1 lần tải (tránh double-tap / resume trùng). */
    private fun openInstallerOnce(ctx: Context, id: Long, f: File) {
        synchronized(openLock) {
            if (openedFor == id) return
            openedFor = id
        }
        // xóa trạng thái chờ — tránh resumePendingInstall mở lại installer mỗi lần bật app
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_DL_PATH).remove(KEY_DL_VER).apply()
        openInstaller(ctx, f)
    }

    fun checkAndUpdate(activity: Activity) {
        val appCtx = activity.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val json = client.newCall(
                    Request.Builder().url("https://api.github.com/repos/hanhk112023-jpg/kenhlive-tv/releases/latest").build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) return@launch
                    JSONObject(resp.body!!.string())
                }
                val latest = json.optString("tag_name").removePrefix("v")
                val apkUrl = json.optJSONArray("assets")?.let { arr ->
                    (0 until arr.length())
                        .map { arr.optJSONObject(it) }
                        .firstOrNull { it?.optString("name")?.endsWith(".apk") == true }
                        ?.optString("browser_download_url")
                } ?: return@launch
                if (apkUrl.isBlank()) return@launch

                val cur = appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName ?: "0"
                if (!isNewer(latest, cur)) return@launch

                withContext(Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed) return@withContext
                    try {
                        AlertDialog.Builder(activity)
                            .setTitle("Có bản mới v$latest")
                            .setMessage("Tải và cập nhật ngay trong app?")
                            .setPositiveButton("TẢI NGAY") { _, _ -> startDownload(activity, apkUrl, latest) }
                            .setNegativeButton("Để sau", null)
                            .show()
                    } catch (e: Exception) { /* activity đã mất — bỏ qua, không crash */ }
                }
            } catch (e: Exception) { /* im lặng */ }
        }
    }

    /** Debug hook cho QA: ép hiện dialog update với version giả cao hơn → test luồng bấm TẢI NGAY không crash. */
    fun debugForceDialog(activity: Activity) {
        val appCtx = activity.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val json = client.newCall(
                    Request.Builder().url("https://api.github.com/repos/hanhk112023-jpg/kenhlive-tv/releases/latest").build()
                ).execute().use { resp -> JSONObject(resp.body!!.string()) }
                val apkUrl = json.optJSONArray("assets")?.let { arr ->
                    (0 until arr.length()).map { arr.optJSONObject(it) }
                        .firstOrNull { it?.optString("name")?.endsWith(".apk") == true }
                        ?.optString("browser_download_url")
                } ?: return@launch
                withContext(Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed) return@withContext
                    try {
                        AlertDialog.Builder(activity)
                            .setTitle("Có bản mới v99.0 (QA)")
                            .setMessage("Tải và cập nhật ngay trong app?")
                            .setPositiveButton("TẢI NGAY") { _, _ -> startDownload(activity, apkUrl, "99.0") }
                            .setNegativeButton("Để sau", null)
                            .show()
                    } catch (e: Exception) { }
                }
            } catch (e: Exception) { }
        }
    }

    private fun isNewer(latest: String, cur: String): Boolean {
        val l = latest.split('.').map { it.toIntOrNull() ?: 0 }
        val c = cur.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }; val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun startDownload(activity: Activity, url: String, version: String) {
        val appCtx = activity.applicationContext
        // quyền cài app: dialog phải chạy trên Activity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appCtx.packageManager.canRequestPackageInstalls()
        ) {
            if (activity.isFinishing || activity.isDestroyed) return
            try {
                AlertDialog.Builder(activity)
                    .setTitle("Cần quyền cài app")
                    .setMessage("Cho phép KênhLive cài ứng dụng không rõ nguồn gốc để tự cập nhật.")
                    .setPositiveButton("Mở cài đặt") { _, _ ->
                        try {
                            activity.startActivity(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${appCtx.packageName}"))
                            )
                        } catch (e: Exception) {
                            Toast.makeText(appCtx, "Máy không có màn hình cấp quyền — bản tải về sẽ mở trình cài đặt thủ công", Toast.LENGTH_LONG).show()
                            downloadNow(appCtx, activity, url, version) // vẫn cho tải, bước cài sẽ fallback
                        }
                    }
                    .setNegativeButton("Hủy", null).show()
            } catch (e: Exception) { }
            return
        }
        downloadNow(appCtx, activity, url, version)
    }

    /** Tải APK bằng OkHttp streaming (đi qua cùng proxy với API) — KHÔNG dùng
     *  DownloadManager (system process bỏ qua proxy emulator/TV → treo vĩnh viễn).
     *  Tiến độ hiển thị Toast định kỳ. Xong → mở installer ngay trên main thread. */
    private fun downloadNow(appCtx: Context, activity: Activity, url: String, version: String) {
        val dir = File(appCtx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").apply { mkdirs() }
        // dọn file cũ nhưng GIỮ file đang chờ cài của version mới nhất (resume)
        dir.listFiles()?.filter { !it.name.contains(version) }?.forEach { it.delete() }
        val tmp = File(dir, "KenhLive-v$version.apk.part")
        val file = File(dir, "KenhLive-v$version.apk")
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // persist đường dẫn NGAY — app bị kill giữa lúc tải thì lần sau mở vẫn biết
                prefs.edit().putString(KEY_DL_PATH, file.absolutePath).putString(KEY_DL_VER, version).apply()
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, "Bắt đầu tải v$version…", Toast.LENGTH_SHORT).show()
                }

                val client = OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val resp = client.newCall(Request.Builder().url(url).build()).execute()
                if (!resp.isSuccessful) {
                    resp.close()
                    prefs.edit().remove(KEY_DL_PATH).remove(KEY_DL_VER).apply()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "Tải thất bại: HTTP ${resp.code}", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                val total = resp.body?.contentLength() ?: -1L
                resp.body?.byteStream()?.use { input ->
                    java.io.FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int; var done = 0L; var lastToast = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read); done += read
                            val now = System.currentTimeMillis()
                            if (now - lastToast > 3000) {   // tiến độ mỗi 3s, không spam
                                lastToast = now
                                withContext(Dispatchers.Main) {
                                    if (total > 0) Toast.makeText(appCtx,
                                        "Đang tải v$version: ${done * 100 / total}% (${done / 1048576}/${total / 1048576}MB)", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        out.fd.sync()
                    }
                }
                resp.close()
                if (!tmp.renameTo(file) || file.length() < 1_000_000) {
                    tmp.delete(); file.delete()
                    prefs.edit().remove(KEY_DL_PATH).remove(KEY_DL_VER).apply()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "Tải xong nhưng file lỗi — thử lại", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                // tải xong → mở installer NGAY (main thread)
                withContext(Dispatchers.Main) {
                    openInstallerOnce(appCtx, System.currentTimeMillis(), file)
                    Toast.makeText(appCtx, "Tải xong! Mở trình cài đặt…", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                tmp.delete()
                prefs.edit().remove(KEY_DL_PATH).remove(KEY_DL_VER).apply()
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, "Lỗi tải: ${e.message?.take(60)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Gọi khi app khởi động: nếu có APK đã tải xong mà chưa cài (app bị kill giữa chừng) → mở installer. */
    fun resumePendingInstall(activity: Activity) {
        val appCtx = activity.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_DL_PATH, null) ?: return
        val f = File(path)
        if (!f.exists() || f.length() < 1_000_000) {
            prefs.edit().remove(KEY_DL_PATH).remove(KEY_DL_VER).apply(); return
        }
        prefs.edit().remove(KEY_DL_PATH).remove(KEY_DL_VER).apply()
        openInstaller(appCtx, f)
    }

    private fun openInstaller(ctx: Context, f: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            try {
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                val alt = Intent("android.intent.action.INSTALL_PACKAGE")
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(alt)
            } catch (e2: Exception) {
                Toast.makeText(ctx, "Tải xong! Mở trình quản lý tệp để cài: ${f.name}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
