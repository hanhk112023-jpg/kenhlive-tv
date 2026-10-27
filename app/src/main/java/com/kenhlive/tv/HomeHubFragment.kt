package com.kenhlive.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * HomeHubFragment — Cổng chọn chính khi mở ứng dụng KenhLive TV:
 * - 3 mục lớn: THỂ THAO, TRUYỀN HÌNH, PHIM TRUYỆN
 * - Mục thứ 4: CÀI ĐẶT & Tiện ích (Đa màn hình, Lịch thi đấu, Tìm kiếm)
 * - Khi ấn vào bất kỳ mục nào sẽ mở giao diện riêng của mục đó; bấm BACK quay lại Cổng chọn.
 */
class HomeHubFragment : Fragment() {

    private var lastFocusedViewId: Int = R.id.cardSports

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_home_hub, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val cardSports = view.findViewById<View>(R.id.cardSports)
        val cardIptv = view.findViewById<View>(R.id.cardIptv)
        val cardPhim = view.findViewById<View>(R.id.cardPhim)

        val btnSettings = view.findViewById<View>(R.id.btnHubSettings)
        val btnFeedback = view.findViewById<View>(R.id.btnHubFeedback)
        val btnMultiview = view.findViewById<View>(R.id.btnHubMultiview)
        val btnSchedule = view.findViewById<View>(R.id.btnHubSchedule)
        val btnSearch = view.findViewById<View>(R.id.btnHubSearch)
        val tvClock = view.findViewById<TextView>(R.id.tvHubClock)

        // Setup focus animations & click listeners for 2 main grand portal cards: THỂ THAO & PHIM TRUYỆN
        val mainCards = listOf(
            cardSports to 0, // Thể thao
            cardPhim to 5    // Phim truyện
        )

        mainCards.forEach { (card, targetTab) ->
            card?.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    lastFocusedViewId = v.id
                }
                v.animate().cancel()
                val scale = if (hasFocus) 1.045f else 1.0f
                val elev = if (hasFocus) 18f else 2f
                v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .translationZ(elev)
                    .setDuration(150)
                    .start()
            }

            card?.setOnClickListener {
                lastFocusedViewId = it.id
                (activity as? MainActivity)?.openSection(targetTab)
            }

            card?.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    when (card.id) {
                        R.id.cardPhim -> btnSearch?.requestFocus() ?: btnSettings?.requestFocus()
                        R.id.cardIptv -> btnSchedule?.requestFocus() ?: btnSettings?.requestFocus()
                        else -> btnSettings?.requestFocus()
                    }
                    true
                } else {
                    false
                }
            }
        }

        // Setup utility row buttons (Tab 4 Settings, Feedback & Quick Tools)
        btnMultiview?.visibility = View.GONE
        val bottomButtons = listOf(
            btnSettings to { (activity as? MainActivity)?.openSection(4) },
            btnFeedback to { com.kenhlive.tv.ui.FeedbackDialog.show(requireContext()) },
            btnSchedule to { (activity as? MainActivity)?.openSection(1) },
            btnSearch to { (activity as? MainActivity)?.openSection(3) }
        )

        bottomButtons.forEach { (btn, action) ->
            btn?.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    lastFocusedViewId = v.id
                }
                v.animate().cancel()
                val scale = if (hasFocus) 1.06f else 1.0f
                v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(130)
                    .start()
            }

            btn?.setOnClickListener {
                lastFocusedViewId = it.id
                action.invoke()
            }

            btn?.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    when (btn.id) {
                        R.id.btnHubSettings, R.id.btnHubMultiview -> cardSports?.requestFocus()
                        R.id.btnHubSchedule -> (if (cardIptv?.visibility == View.VISIBLE) cardIptv else cardSports)?.requestFocus()
                        R.id.btnHubSearch -> cardPhim?.requestFocus()
                        else -> cardSports?.requestFocus()
                    }
                    true
                } else {
                    false
                }
            }
        }

        // Live Clock Updater (GMT+7)
        viewLifecycleOwner.lifecycleScope.launch {
            val fmt = SimpleDateFormat("HH:mm · dd/MM/yyyy", Locale("vi", "VN")).apply {
                timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
            }
            while (isActive) {
                tvClock?.text = fmt.format(Date())
                delay(15_000L)
            }
        }

        // Initial focus on Sports Card
        view.post {
            ensureHubFocus()
        }

        // Tự động hiện bảng thông báo v7.4.0 (1 lần đầu) nếu mở app bình thường
        val act = activity
        val hasIntentExtra = (act?.intent?.getIntExtra("tab", -1) ?: -1) != -1 || act?.intent?.getStringExtra("open") != null
        if (!hasIntentExtra) {
            view.postDelayed({
                if (isAdded && !isDetached) {
                    com.kenhlive.tv.ui.FeedbackDialog.showIfNeeded(requireContext())
                }
            }, 1000L)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            view?.post {
                ensureHubFocus()
            }
        }
    }

    fun ensureHubFocus(): Boolean {
        val root = view ?: return false
        val target = root.findViewById<View>(lastFocusedViewId)
            ?: root.findViewById<View>(R.id.cardSports)
        return target?.requestFocus() == true
    }
}
