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
 * Dialog hiển thị thông tin Liên hệ & Báo lỗi qua Telegram (https://t.me/T990512S).
 * Thiết kế chuẩn Android TV (D-pad focus) và Mobile.
 * Kèm mã QR trực quan quét nhanh trên màn hình TV.
 */
object FeedbackDialog {

    const val TELEGRAM_URL = "https://t.me/T990512S"
    const val TELEGRAM_HANDLE = "@T990512S"

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
                    "Telegram: $TELEGRAM_HANDLE ($TELEGRAM_URL)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        btnClose?.setOnClickListener {
            dlg.dismiss()
        }

        dlg.show()

        // Focus mặc định cho remote TV
        btnOpen?.post {
            btnOpen.requestFocus()
        }

        return dlg
    }
}
