package com.kenhlive.tv

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
 * Update in-app: tải APK GitHub Release bằng DownloadManager (không nhảy browser),
 * xong tự mở màn hình cài đặt. Chữ ký keystore cố định → cài đè được bản cũ.
 */
object UpdateManager {

    private var dm: DownloadManager? = null
    private var apkFile: File? = null
    private var receiverRegistered = false

    fun checkAndUpdate(ctx: Context) {
        val appCtx = ctx.applicationContext
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

                val cur = appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName ?: "0"
                if (!isNewer(latest, cur)) return@launch

                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(ctx)
                        .setTitle("Có bản mới v$latest")
                        .setMessage("Tải và cập nhật ngay trong app?")
                        .setPositiveButton("TẢI NGAY") { _, _ -> startDownload(appCtx, apkUrl, latest) }
                        .setNegativeButton("Để sau", null).show()
                }
            } catch (e: Exception) { /* im lặng */ }
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

    private fun startDownload(ctx: Context, url: String, version: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !ctx.packageManager.canRequestPackageInstalls()
        ) {
            AlertDialog.Builder(ctx)
                .setTitle("Cần quyền cài app")
                .setMessage("Cho phép KênhLive cài ứng dụng không rõ nguồn gốc để tự cập nhật.")
                .setPositiveButton("Mở cài đặt") { _, _ ->
                    try {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Không mở được cài đặt", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Hủy", null).show()
            return
        }

        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "KenhLive-v$version.apk")

        dm = (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).also { d ->
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle("KênhLive v$version")
                .setDescription("Đang tải bản cập nhật…")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(file))
                .setAllowedOverRoaming(true)
            val id = d.enqueue(req)
            apkFile = file

            if (!receiverRegistered) {
                receiverRegistered = true
                ctx.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == id) {
                            openInstaller(ctx)
                        }
                    }
                }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }
            Toast.makeText(ctx, "Đang tải v$version…", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openInstaller(ctx: Context) {
        val f = apkFile ?: return
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Tải xong: ${f.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }
}
