package com.kenhlive.tv.ui

import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kenhlive.tv.R

/**
 * Bộ công cụ focus cho Android TV (10-foot):
 * - Hiệu ứng focus thống nhất: phóng nhẹ + nâng bóng + viền trắng (fg_focus trong XML)
 * - D-pad trong grid/carousel: LEFT/RIGHT nuốt ở biên, UP/DOWN sang hàng kề ĐÚNG CỘT
 *   (không để focus-search mặc định của Android đoán → bug "nhảy lung tung")
 * - Hàng chưa layout: cuộn outer RecyclerView rồi retry (tối đa ~1.4s)
 * Điện thoại: các view focusable=false nên toàn bộ logic này vô hại.
 */
object FocusKit {

    /** Vị trí ô focus gần nhất (rowPos, idx) — khôi phục khi quay lại màn hình. */
    var lastSlot: Pair<Int, Int>? = null

    interface RowHost {
        val outerRecyclerView: RecyclerView?
        /** Focus nút hero (UP từ hàng đầu). Trả về true nếu đã focus được. */
        fun focusHero(): Boolean
        /** Số hàng đứng trước các row card (0 = hero chiếm vị trí adapter 0). */
        val headerPositions: Int get() = 1
    }

    /** Gắn hiệu ứng focus chuẩn cho card. */
    fun decorateCard(card: View, scale: Float = 1.06f, elevation: Float = 16f) {
        card.setOnFocusChangeListener { v, has ->
            v.animate()
                .scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f)
                .setDuration(150).setInterpolator(DecelerateInterpolator()).start()
            v.elevation = if (has) elevation else 0f
        }
    }

    /** OnKeyListener cho card trong row ngang. */
    fun rowCardKey(
        host: RowHost, rowPos: Int, idx: Int, size: Int
    ): View.OnKeyListener = View.OnKeyListener { _, keyCode, ev ->
        if (ev.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> idx == 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> idx == size - 1
            KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(host, rowPos, rowPos + 1, idx)
            KeyEvent.KEYCODE_DPAD_UP ->
                if (rowPos < host.headerPositions) host.focusHero()
                else moveRow(host, rowPos, rowPos - 1, idx)
            else -> false
        }
    }

    /** Nhớ ô đang focus để restore. */
    fun remember(rowPos: Int, idx: Int) { lastSlot = rowPos to idx }

    private fun moveRow(host: RowHost, fromPos: Int, targetPos: Int, idx: Int): Boolean {
        val rv = host.outerRecyclerView ?: return false
        if (targetPos < 0 || targetPos >= (rv.adapter?.itemCount ?: 0)) return true // biên dọc: nuốt
        val step = if (targetPos > fromPos) 1 else -1
        if (focusRow(rv, targetPos, idx, step)) return true
        rv.smoothScrollBy(0, step * 420)
        retry(host, targetPos, idx, 7, step)
        return true
    }

    private fun retry(host: RowHost, pos: Int, idx: Int, left: Int, step: Int) {
        if (left <= 0) return
        val rv = host.outerRecyclerView ?: return
        rv.postDelayed({
            if (!focusRow(rv, pos, idx, step)) retry(host, pos, idx, left - 1, step)
        }, 180)
    }

    /**
     * Focus hàng `pos`; nếu hàng đó không có gì focus được (header ngày) thì bước tiếp theo
     * hướng `step` sang hàng kề. Thiếu bước này thì D-pad DOWN/UP từ rail "Trực tiếp & Sắp
     * diễn ra" chỉ cuộn nội dung còn focus mắc lại ở card rail (card bị recycle → mất focus).
     */
    private fun focusRow(rv: RecyclerView, pos: Int, idx: Int, step: Int): Boolean {
        val count = rv.adapter?.itemCount ?: 0
        var p = pos
        while (p in 0 until count) {
            val vh = rv.findViewHolderForAdapterPosition(p) ?: return false // chưa layout → chờ/cuộn
            if (focusNow(rv, p, idx)) return true
            val isRail = vh.itemView.findViewById<RecyclerView>(R.id.rowList) != null
            if (isRail || vh.itemView.isFocusable) return false // có ô thật, chỉ là chưa sẵn sàng
            p += step // header ngày: bỏ qua, đi tiếp cùng hướng bấm phím
        }
        return false
    }

    /** Focus ô idx của hàng adapter `pos` (hàng = RecyclerView ngang bên trong RowVH). */
    fun focusNow(rv: RecyclerView, pos: Int, idx: Int): Boolean {
        val vh = rv.findViewHolderForAdapterPosition(pos) ?: return false
        val inner = vh.itemView.findViewById<RecyclerView>(R.id.rowList)
        if (inner != null) {
            val lm = inner.layoutManager as? LinearLayoutManager ?: return false
            val target = lm.findViewByPosition(idx)
                ?: lm.findViewByPosition(idx.coerceAtMost((inner.adapter?.itemCount ?: 1) - 1))
                ?: return false
            return target.requestFocus()
        }
        // Hàng không phải rail (card trận trong danh sách theo ngày): focus chính card.
        val item = vh.itemView
        return item.isFocusable && item.requestFocus()
    }

    /** Khôi phục focus về ô đã nhớ (gọi từ onResume). */
    fun restore(host: RowHost) {
        val (rowPos, idx) = lastSlot ?: return
        val rv = host.outerRecyclerView ?: return
        rv.post {
            if (!focusRow(rv, rowPos, idx, 1) && !focusRow(rv, rowPos, idx, -1)) {
                // hàng chưa nằm trong màn → cuộn tới rồi thử lại
                rv.scrollToPosition(rowPos)
                rv.postDelayed({ focusRow(rv, rowPos, idx, 1) }, 220)
            }
        }
    }
}
