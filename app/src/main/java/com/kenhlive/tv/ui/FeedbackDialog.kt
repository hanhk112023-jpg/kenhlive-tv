package com.kenhlive.tv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.kenhlive.tv.R

/**
 * Bảng Thông Báo Mới & Nhóm Thảo Luận Telegram (@kenhlivetv):
 * - Giới thiệu các tính năng mới và thông báo cập nhật hệ thống.
 * - Mã QR Telegram rõ nét để người dùng quét điện thoại tham gia nhóm thảo luận, báo lỗi và yêu cầu kênh.
 */
object FeedbackDialog {

    const val TELEGRAM_URL = "https://t.me/kenhlivetv"
    const val TELEGRAM_HANDLE = "@kenhlivetv"
    private const val PREF_KEY_SEEN = "seen_notice_v740"

    fun show(context: Context): AlertDialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_feedback, null)
        val dlg = AlertDialog.Builder(context, R.style.Theme_KenhLive_Dialog)
            .setView(view)
            .create()

        val btnOpen = view.findViewById<Button>(R.id.btnOpenTelegram)
        val btnClose = view.findViewById<Button>(R.id.btnCloseFeedback)

        btnOpen?.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_URL)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Nhóm Telegram: $TELEGRAM_HANDLE ($TELEGRAM_URL)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        btnClose?.setOnClickListener {
            dlg.dismiss()
        }

        dlg.show()

        // Focus mặc định vào nút ĐÃ HIỂU / ĐÓNG để người dùng TV thao tác nhanh
        btnClose?.post {
            btnClose.requestFocus()
        }

        return dlg
    }

    /**
     * Tự động hiển thị bảng thông báo cho phiên bản mới v7.4.0 (chỉ hiện 1 lần đầu).
     */
    fun showIfNeeded(context: Context) {
        val sp = context.getSharedPreferences("kenhlive_prefs", Context.MODE_PRIVATE)
        if (!sp.getBoolean(PREF_KEY_SEEN, false)) {
            sp.edit().putBoolean(PREF_KEY_SEEN, true).apply()
            show(context)
        }
    }
}
