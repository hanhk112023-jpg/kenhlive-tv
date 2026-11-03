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
                // Repo co the private → qua proxy worker (kenhlive-dl), no tu tai APK bang token.
                val json = client.newCall(
                    Request.Builder().url("https://kenhlive-dl.htuananh153.workers.dev/version").build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) return@launch
                    JSONObject(resp.body!!.string())
                }
                val latest = json.optString("tag").removePrefix("v")
                val apkUrl = "https://kenhlive-dl.htuananh153.workers.dev/apk"
                // H2: checksum SHA-256 do worker /version cung cấp (nếu có) để verify
                // file tải về trước khi cài. Không có thì vẫn verify gói + phiên bản
                // bằng PackageManager trong verifyDownloadedApk().
                val expectedSha256 = listOf("sha256", "apk_sha256", "checksum_sha256")
                    .firstNotNullOfOrNull { k -> json.optString(k).takeIf { it.isNotBlank() } }

                val cur = appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName ?: "0"
                if (!isNewer(latest, cur)) return@launch

                withContext(Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed) return@withContext
                    try {
                        AlertDialog.Builder(activity)
                            .setTitle("Có bản mới v$latest")
                            .setMessage("Tải và cập nhật ngay trong app?")
                            .setPositiveButton("TẢI NGAY") { _, _ -> startDownload(activity, apkUrl, latest, expectedSha256) }
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
                    Request.Builder().url("https://kenhlive-dl.htuananh153.workers.dev/version").build()
                ).execute().use { resp -> JSONObject(resp.body!!.string()) }
                val apkUrl = "https://kenhlive-dl.htuananh153.workers.dev/apk"
                if (apkUrl.isBlank() || json.optString("tag").isBlank()) return@launch
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

    private fun startDownload(activity: Activity, url: String, version: String, expectedSha256: String? = null) {
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
                            downloadNow(appCtx, activity, url, version, expectedSha256) // vẫn cho tải, bước cài sẽ fallback
                        }
                    }
                    .setNegativeButton("Hủy", null).show()
            } catch (e: Exception) { }
            return
        }
        downloadNow(appCtx, activity, url, version, expectedSha256)
    }

    /** Tải APK bằng OkHttp streaming (đi qua cùng proxy với API) — KHÔNG dùng
     *  DownloadManager (system process bỏ qua proxy emulator/TV → treo vĩnh viễn).
     *  Tiến độ hiển thị Toast định kỳ. Xong → mở installer ngay trên main thread. */
    private fun downloadNow(appCtx: Context, activity: Activity, url: String, version: String, expectedSha256: String? = null) {
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
                // H2: VERIFY trước khi cài — checksum SHA-256 (nếu worker cung cấp) +
                // gói ứng dụng + versionCode phải đúng bản KenhLive mới hơn bản đang cài.
                // Không khớp -> xoá file, huỷ cài, báo lỗi (tránh cài nhầm APK lạ/hỏng).
                val verifyError = verifyDownloadedApk(appCtx, file, expectedSha256)
                if (verifyError != null) {
                    file.delete()
                    prefs.edit().remove(KEY_DL_PATH).remove(KEY_DL_VER).apply()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "Huỷ cập nhật: $verifyError", Toast.LENGTH_LONG).show()
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

    private fun sha256Hex(f: File): String? = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var r: Int
            while (input.read(buf).also { r = it } != -1) md.update(buf, 0, r)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }

    /**
     * H2 — Kiểm chứng APK vừa tải trước khi mở installer. Trả về chuỗi lỗi nếu
     * KHÔNG đạt, null nếu đạt:
     * 1. Nếu có checksum từ nguồn phát hành: SHA-256 của file phải khớp.
     * 2. File phải là APK đọc được bằng PackageManager.
     * 3. packageName phải đúng gói của app (chống cài nhầm APK ứng dụng khác).
     * 4. versionCode/versionName phải MỚI HƠN bản đang cài (đúng bản cập nhật kỳ vọng).
     */
    private fun verifyDownloadedApk(appCtx: Context, file: File, expectedSha256: String?): String? {
        if (!expectedSha256.isNullOrBlank()) {
            val actual = sha256Hex(file) ?: return "không tính được SHA-256 của file tải về"
            if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                return "checksum SHA-256 không khớp với nguồn phát hành"
            }
        }
        val pm = appCtx.packageManager
        val archive = try { pm.getPackageArchiveInfo(file.absolutePath, 0) } catch (_: Exception) { null }
            ?: return "file tải về không phải APK hợp lệ"
        if (archive.packageName != appCtx.packageName) {
            return "APK không đúng gói ứng dụng (${archive.packageName})"
        }
        val installed = try { pm.getPackageInfo(appCtx.packageName, 0) } catch (_: Exception) { null }
            ?: return null // không đọc được bản đang cài -> đã qua các vòng trên, cho qua
        @Suppress("DEPRECATION")
        val archiveCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode
        else archive.versionCode.toLong()
        @Suppress("DEPRECATION")
        val installedCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installed.longVersionCode
        else installed.versionCode.toLong()
        val nameNewer = isNewer(archive.versionName ?: "", installed.versionName ?: "")
        if (archiveCode <= installedCode && !nameNewer) {
            return "phiên bản APK (${archive.versionName ?: "?"}) không mới hơn bản đang cài (${installed.versionName ?: "?"})"
        }
        return null
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
        // H2: file chờ cài cũng phải qua kiểm chứng gói + đúng phiên bản đã tải dở,
        // tránh trường hợp file bị thay thế trong lúc app bị kill.
        val storedVer = prefs.getString(KEY_DL_VER, null)
        val archive = try { appCtx.packageManager.getPackageArchiveInfo(f.absolutePath, 0) } catch (_: Exception) { null }
        val archiveOk = archive != null && archive.packageName == appCtx.packageName &&
            (storedVer == null || archive.versionName == null || archive.versionName == storedVer)
        prefs.edit().remove(KEY_DL_PATH).remove(KEY_DL_VER).apply()
        if (!archiveOk) {
            f.delete()
            return
        }
        openInstaller(appCtx, f)
    }

    /** Mở PackageInstaller TRỰC TIẾP, không đi qua ACTION_VIEW chung (ROM hay đẩy
     *  'Ứng dụng tệp'/file manager khi resolver chọn nhầm app). Ưu tiên component cài đặt
     *  hệ thống, cùng lắm mới fallback ACTION_VIEW. */
    private fun openInstaller(ctx: Context, f: File) {
        val uri = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        } catch (e: Exception) {
            Uri.fromFile(f) // ROM lạ không map FileProvider → file:// (cũ nhưng TV box hay chấp nhận)
        }
        val pm = ctx.packageManager
        // 1) component PackageInstaller tường minh (không phải app 'Tệp')
        val candidates = listOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller"
        )
        for (pkg in candidates) {
            try {
                val intent = Intent(Intent.ACTION_VIEW)
                    .setClassName(pkg, "$pkg.PackageInstallerActivity")
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (intent.resolveActivity(pm) != null) { ctx.startActivity(intent); return }
            } catch (e: Exception) { }
        }
        // 2) resolve ACTION_VIEW nhưng LOẠI app quản lý tệp
        try {
            val probe = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
            val resolvers = pm.queryIntentActivities(probe, 0)
            val installer = resolvers.firstOrNull { ri ->
                val n = (ri.activityInfo.packageName + ri.activityInfo.name).lowercase()
                // BUG-20: denylist cu, bo `contains("file")` qua rong (loai ca installer that co "file" trong ten)
                !n.contains("documentsui") && !n.contains("filemanager") && !n.contains("filebrowser") &&
                    !n.contains("explorer") && !n.contains("archive")
            } ?: resolvers.firstOrNull()
            if (installer != null) {
                val intent = Intent(Intent.ACTION_VIEW)
                    .setClassName(installer.activityInfo.packageName, installer.activityInfo.name)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(intent)
                return
            }
        } catch (e: Exception) { }
        // 3) ACTION_VIEW thường + INSTALL_PACKAGE fallback cuối
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            return
        } catch (e: Exception) { }
        try {
            ctx.startActivity(Intent("android.intent.action.INSTALL_PACKAGE")
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e2: Exception) {
            Toast.makeText(ctx, "Tải xong! Mở trình quản lý tệp để cài: ${f.name}", Toast.LENGTH_LONG).show()
        }
    }
}
