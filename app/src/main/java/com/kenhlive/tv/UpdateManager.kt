package com.kenhlive.tv

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * Update in-app: kiểm tra GitHub Release → tải APK bằng DownloadManager → tự mở màn cài đặt.
 *
 * FIX crash v4.7.2:
 *  - Dialog PHẢI dùng Activity context (bản cũ truyền applicationContext → BadTokenException
 *    "văng app khi ấn TẢI NGAY"). Mọi UI path giờ nhận Activity + check isFinishing.
 *  - registerReceiver khai báo RECEIVER_NOT_EXPORTED trên API 33+ (bản cũ crash SecurityException
 *    trên Android 13/14 TV).
 *  - Receiver lưu id vào SharedPreferences → vẫn mở installer nếu app bị kill giữa lúc tải.
 *  - Mở installer thử ACTION_VIEW rồi ACTION_INSTALL_PACKAGE, fail thì hướng dẫn thủ công.
 */
object UpdateManager {

    private const val PREFS = "update_state"
    private const val KEY_DL_ID = "dl_id"
    private const val KEY_DL_PATH = "dl_path"
    private var receiverRegistered = false

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

    private fun downloadNow(appCtx: Context, activity: Activity, url: String, version: String) {
        try {
            val dir = File(appCtx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "KenhLive-v$version.apk")

            val d = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle("KênhLive v$version")
                .setDescription("Đang tải bản cập nhật…")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(file))
                .setAllowedOverRoaming(true)
            val id = d.enqueue(req)

            // lưu trạng thái để nếu app chết giữa lúc tải, lần mở sau vẫn mở được installer
            appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_DL_ID, id).putString(KEY_DL_PATH, file.absolutePath).apply()

            registerCompleteReceiver(appCtx)
            Toast.makeText(appCtx, "Đang tải v$version…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(appCtx, "Không tải được: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun registerCompleteReceiver(ctx: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val id = prefs.getLong(KEY_DL_ID, -1)
                if (id != -1L && i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == id) {
                    val path = prefs.getString(KEY_DL_PATH, null)
                    if (path != null) {
                        prefs.edit().remove(KEY_DL_ID).remove(KEY_DL_PATH).apply()
                        openInstaller(c, File(path))
                    }
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) { receiverRegistered = false }
    }

    /** Gọi khi app khởi động: nếu có bản tải xong mà chưa cài (app bị kill giữa chừng) → mở installer. */
    fun resumePendingInstall(activity: Activity) {
        val appCtx = activity.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_DL_PATH, null) ?: return
        val f = File(path)
        if (!f.exists() || f.length() < 1_000_000) return
        val d = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        try {
            val cur = d.query(DownloadManager.Query().setIds(prefs.getLong(KEY_DL_ID, -1)))
            var done = false
            if (cur != null && cur.moveToFirst()) {
                val status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                done = status == DownloadManager.STATUS_SUCCESSFUL
                cur.close()
            }
            if (done) {
                // mở 1 lần rồi xóa trạng thái chờ — tránh hỏi lặp lại mỗi lần bật app
                prefs.edit().remove(KEY_DL_ID).remove(KEY_DL_PATH).apply()
                openInstaller(appCtx, f)
            }
        } catch (e: Exception) { }
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
