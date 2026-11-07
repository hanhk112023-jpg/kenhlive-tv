package com.kenhlive.tv

import java.io.File

/**
 * Logic thuần, không phụ thuộc Android runtime, của chuỗi kiểm chứng cập nhật.
 * Tách riêng để unit test JVM có thể kiểm chứng mà không cần PackageManager hay
 * Context: mọi quyết định từ chối/chấp nhận đều đi qua validateCandidate().
 * Mọi hàm ở đây đều fail-closed: thiếu dữ liệu kiểm chứng thì trả về lỗi,
 * không bao giờ cho qua.
 *
 * Lưu ý (KL4): UpdateManager (kiểm tra/tải/cài APK qua workers.dev) đã bị gỡ bỏ;
 * object này được giữ lại vì là logic kiểm chứng thuần được UpdateVerificationTest
 * kiểm thử trực tiếp, và có thể tái sử dụng nếu sau này có kênh phát hành mới.
 */
internal object UpdateVerification {
    fun isNewerVersion(latest: String, cur: String): Boolean {
        val l = latest.split('.').map { it.toIntOrNull() ?: 0 }
        val c = cur.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }; val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    fun sha256Hex(file: File): String? = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var r: Int
            while (input.read(buf).also { r = it } != -1) md.update(buf, 0, r)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }

    fun sha256HexBytes(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** Hai tập chữ ký khớp nhau khi có ít nhất một digest chứng chỉ chung. */
    fun signaturesMatch(installed: List<ByteArray>, archive: List<ByteArray>): Boolean {
        if (archive.isEmpty() || installed.isEmpty()) return false
        val installedDigests = installed.map { sha256HexBytes(it) }.toSet()
        return archive.any { sha256HexBytes(it) in installedDigests }
    }

    /**
     * Trả về chuỗi lỗi nếu ứng viên cập nhật KHÔNG đạt, null nếu đạt.
     * Thứ tự kiểm tra: SHA bắt buộc, package, signer, rồi version mới hơn.
     */
    fun validateCandidate(
        packageName: String,
        archivePackage: String,
        archiveVersionCode: Long,
        installedVersionCode: Long,
        archiveVersionName: String,
        installedVersionName: String,
        expectedSha256: String?,
        actualSha256: String?,
        signerMatches: Boolean
    ): String? {
        if (expectedSha256.isNullOrBlank()) {
            return "thiếu checksum SHA-256 từ nguồn phát hành — từ chối cập nhật"
        }
        if (actualSha256.isNullOrBlank()) {
            return "không tính được SHA-256 của file tải về"
        }
        if (!actualSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
            return "checksum SHA-256 không khớp với nguồn phát hành"
        }
        if (archivePackage != packageName) {
            return "APK không đúng gói ứng dụng ($archivePackage)"
        }
        if (!signerMatches) {
            return "chữ ký APK không khớp với chữ ký app đang cài"
        }
        val nameNewer = isNewerVersion(archiveVersionName, installedVersionName)
        if (archiveVersionCode <= installedVersionCode && !nameNewer) {
            return "phiên bản APK ($archiveVersionName) không mới hơn bản đang cài ($installedVersionName)"
        }
        return null
    }
}
