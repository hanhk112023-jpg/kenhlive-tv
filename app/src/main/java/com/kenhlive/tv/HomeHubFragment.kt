package com.kenhlive.tv

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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

        // Setup focus animations & click listeners for 3 grand portal cards: THỂ THAO, TRUYỀN HÌNH & PHIM TRUYỆN
        val mainCards = listOf(
            cardSports to 0, // Thể thao
            cardIptv to 2,   // Truyền hình
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

        // Điện thoại: chỉnh lại bố cục Hub (vốn thiết kế cho TV) theo chiều màn hình
        applyHubLayout(view)

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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Activity xử lý configChanges nên không recreate: tự xếp lại Hub khi xoay máy (phone)
        view?.let { applyHubLayout(it) }
    }

    /**
     * Hub vốn là màn hình TV (2 thẻ cổng nằm ngang + hàng tiện ích ngang).
     * Trên điện thoại dọc, bố cục ngang khiến mỗi thẻ bị bóp còn ~124dp và hàng nút tiện ích
     * tràn khỏi màn hình -> xếp dọc cả hai hàng, ẩn spacer và dòng chú thích phím D-pad.
     * TV và điện thoại ngang giữ nguyên bố cục gốc.
     */
    private fun applyHubLayout(root: View) {
        if (!DeviceMode.isPhone) return
        val portal = root.findViewById<LinearLayout>(R.id.portalContainer) ?: return
        val cardSports = root.findViewById<View>(R.id.cardSports) ?: return
        val cardIptv = root.findViewById<View>(R.id.cardIptv) ?: return
        val cardPhim = root.findViewById<View>(R.id.cardPhim) ?: return
        val btnSettings = root.findViewById<View>(R.id.btnHubSettings) ?: return
        val bottomRow = btnSettings.parent as? LinearLayout ?: return
        val d = root.resources.displayMetrics.density
        val gap = (10 * d).toInt()
        val portrait = DeviceMode.isPortrait(root.context)

        fun cardLp(v: View) = v.layoutParams as? LinearLayout.LayoutParams

        if (portrait) {
            portal.orientation = LinearLayout.VERTICAL
            cardLp(cardSports)?.let { lp ->
                lp.width = LinearLayout.LayoutParams.MATCH_PARENT
                lp.height = 0
                lp.weight = 1f
                lp.rightMargin = 0
                lp.bottomMargin = gap
                cardSports.layoutParams = lp
            }
            cardLp(cardIptv)?.let { lp ->
                lp.width = LinearLayout.LayoutParams.MATCH_PARENT
                lp.height = 0
                lp.weight = 1f
                lp.leftMargin = 0
                lp.rightMargin = 0
                lp.bottomMargin = gap
                cardIptv.layoutParams = lp
            }
            cardLp(cardPhim)?.let { lp ->
                lp.width = LinearLayout.LayoutParams.MATCH_PARENT
                lp.height = 0
                lp.weight = 1f
                lp.leftMargin = 0
                lp.topMargin = 0
                cardPhim.layoutParams = lp
            }
            bottomRow.orientation = LinearLayout.VERTICAL
            bottomRow.gravity = Gravity.START
            for (i in 0 until bottomRow.childCount) {
                val child = bottomRow.getChildAt(i)
                val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
                when {
                    child.id == View.NO_ID -> child.visibility = View.GONE // spacer co giãn
                    child is TextView -> child.visibility = View.GONE // chú thích "D-pad · OK..."
                    else -> {
                        lp.leftMargin = 0
                        lp.topMargin = if (child === btnSettings) 0 else (10 * d).toInt()
                        child.layoutParams = lp
                    }
                }
            }
        } else {
            portal.orientation = LinearLayout.HORIZONTAL
            cardLp(cardSports)?.let { lp ->
                lp.width = 0
                lp.height = LinearLayout.LayoutParams.MATCH_PARENT
                lp.weight = 1f
                lp.rightMargin = gap / 2
                lp.bottomMargin = 0
                cardSports.layoutParams = lp
            }
            cardLp(cardIptv)?.let { lp ->
                lp.width = 0
                lp.height = LinearLayout.LayoutParams.MATCH_PARENT
                lp.weight = 1f
                lp.leftMargin = gap / 2
                lp.rightMargin = gap / 2
                lp.bottomMargin = 0
                cardIptv.layoutParams = lp
            }
            cardLp(cardPhim)?.let { lp ->
                lp.width = 0
                lp.height = LinearLayout.LayoutParams.MATCH_PARENT
                lp.weight = 1f
                lp.leftMargin = gap / 2
                cardPhim.layoutParams = lp
            }
            bottomRow.orientation = LinearLayout.HORIZONTAL
            bottomRow.gravity = Gravity.CENTER_VERTICAL
            for (i in 0 until bottomRow.childCount) {
                val child = bottomRow.getChildAt(i)
                val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
                when {
                    child.id == View.NO_ID -> child.visibility = View.VISIBLE
                    child is TextView -> child.visibility = View.VISIBLE
                    else -> {
                        lp.leftMargin = if (child === btnSettings) 0 else gap
                        lp.topMargin = 0
                        child.layoutParams = lp
                    }
                }
            }
        }
    }

    fun ensureHubFocus(): Boolean {
        // Điện thoại dùng cảm ứng: không ép focus lên thẻ (tránh thẻ bị phóng to / sáng focus sẵn)
        if (DeviceMode.isPhone) return false
        val root = view ?: return false
        val target = root.findViewById<View>(lastFocusedViewId)
            ?: root.findViewById<View>(R.id.cardSports)
        return target?.requestFocus() == true
    }
}
