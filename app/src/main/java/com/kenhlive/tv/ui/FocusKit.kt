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
 * - D-pad trong grid/carousel: LEFT/RIGHT nuốt ở biên hoặc nhả ra để về menu, UP/DOWN sang hàng kề ĐÚNG CỘT
 * - Hàng chưa layout: cuộn outer RecyclerView rồi retry (tối đa ~1.4s)
 * Điện thoại: các view focusable=false nên toàn bộ logic này vô hại.
 */
object FocusKit {

    /** Vị trí ô focus gần nhất (rowPos, idx) — khôi phục khi quay lại màn hình. */
    var lastSlot: Pair<Int, Int>? = null

    /** Khoá nội dung của ô focus (vd "C|league|A vs B") — position drift sau auto-refresh. */
    var lastKey: String? = null

    interface RowHost {
        /** Định vị lại slot hiện tại theo khoá nội dung (null = không tìm thấy). */
        fun slotFor(key: String): Pair<Int, Int>? = null

        val outerRecyclerView: RecyclerView?
        /** Focus nút hero (UP từ hàng đầu). Trả về true nếu đã focus được. */
        fun focusHero(): Boolean
        /** Số hàng đứng trước các row card (0 = hero chiếm vị trí adapter 0). */
        val headerPositions: Int get() = 1
    }

    /** Gắn hiệu ứng focus chuẩn cho card. */
    fun decorateCard(card: View, scale: Float = 1.06f, elevation: Float = 16f) {
        card.setOnFocusChangeListener { v, has ->
            v.animate().cancel()
            v.animate()
                .scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f)
                .setDuration(150).setInterpolator(DecelerateInterpolator()).start()
            v.elevation = if (has) elevation else 0f
        }
    }

    /** Áp dụng tương tác chuẩn Apple (TV: Focus scale 1.06x + elevation; Mobile: Touch spring 0.96x). */
    fun applyAppleInteraction(view: View, tvScale: Float = 1.06f, phoneScale: Float = 0.96f) {
        if (com.kenhlive.tv.DeviceMode.isTv) {
            view.isFocusable = true
            view.isClickable = true
            view.setOnFocusChangeListener { v, hasFocus ->
                v.animate().cancel()
                v.animate()
                    .scaleX(if (hasFocus) tvScale else 1f)
                    .scaleY(if (hasFocus) tvScale else 1f)
                    .setDuration(140)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                v.elevation = if (hasFocus) 12f else 0f
            }
        } else {
            view.isFocusable = false
            view.setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        v.animate().cancel()
                        v.animate().scaleX(phoneScale).scaleY(phoneScale).setDuration(80).start()
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        v.animate().cancel()
                        v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    }
                }
                false
            }
        }
    }

    /** OnKeyListener cho card trong row ngang.
     * Khi idx == 0 và bấm LEFT: nuốt để giữ focus luôn ở mép trái của hàng, tránh văng focus.
     */
    fun rowCardKey(
        host: RowHost, rowPos: Int, idx: Int, size: Int
    ): View.OnKeyListener = View.OnKeyListener { _, keyCode, ev ->
        if (ev.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (idx == 0) true // chạm mép trái: nuốt, giữ nguyên trên card đầu tiên
                else false // để RecyclerView tự cuộn sang trái bình thường
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (idx >= size - 1) true // chạm mép phải: nuốt, giữ nguyên trên card cuối cùng
                else false // để RecyclerView tự cuộn sang phải bình thường
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(host, rowPos, rowPos + 1, idx)
            KeyEvent.KEYCODE_DPAD_UP ->
                if (rowPos < host.headerPositions) host.focusHero()
                else moveRow(host, rowPos, rowPos - 1, idx)
            else -> false
        }
    }

    /** Nhớ ô đang focus để restore. */
    fun remember(rowPos: Int, idx: Int, key: String? = null) {
        lastSlot = rowPos to idx
        if (key != null) lastKey = key
    }

    private fun moveRow(host: RowHost, fromPos: Int, targetPos: Int, idx: Int): Boolean {
        val rv = host.outerRecyclerView ?: return false
        val total = rv.adapter?.itemCount ?: 0
        if (targetPos < 0 || targetPos >= total) return true // biên dọc: nuốt
        val nextItem = (rv.adapter as? androidx.recyclerview.widget.ListAdapter<*, *>)?.currentList?.getOrNull(targetPos)
        if (nextItem is String) {
            // Nếu hàng kế tiếp là Header ngày (String), nhảy tiếp xuống hàng trận đấu (targetPos + 1)
            val skipPos = if (targetPos > fromPos) targetPos + 1 else targetPos - 1
            if (skipPos in 0 until total) {
                return moveRow(host, fromPos, skipPos, idx)
            }
        }
        if (focusNow(rv, targetPos, idx)) return true
        rv.scrollToPosition(targetPos)
        retry(host, targetPos, idx, 6)
        return true
    }

    private fun retry(host: RowHost, pos: Int, idx: Int, left: Int) {
        val rv = host.outerRecyclerView ?: return
        if (left <= 0) {
            // Khi hết retry mà vẫn chưa bắt được focus: phục hồi vào view đầu tiên có thể focus
            if (rv.findFocus() == null) {
                firstFocusableIn(rv)?.requestFocus()
            }
            return
        }
        rv.postDelayed({
            if (!focusNow(rv, pos, idx)) retry(host, pos, idx, left - 1)
        }, 60)
    }

    /** Focus ô idx của hàng adapter `pos` (hàng = RecyclerView ngang bên trong RowVH hoặc hàng dọc độc lập). */
    fun focusNow(rv: RecyclerView, pos: Int, idx: Int): Boolean {
        val vh = rv.findViewHolderForAdapterPosition(pos) ?: return false
        val inner = vh.itemView.findViewById<RecyclerView>(R.id.rowList)
        if (inner != null) {
            val lm = inner.layoutManager as? LinearLayoutManager
            val target = inner.findViewHolderForAdapterPosition(idx)?.itemView
                ?: lm?.findViewByPosition(idx)
                ?: firstFocusableIn(inner)
            if (target?.requestFocus() == true) return true
            inner.post {
                val t2 = inner.findViewHolderForAdapterPosition(idx)?.itemView
                    ?: inner.layoutManager?.findViewByPosition(idx)
                    ?: firstFocusableIn(inner)
                t2?.requestFocus()
            }
            return false
        }
        // Hàng đơn lẻ (ScheduleMatch, v.v.): focus trực tiếp vào itemView hoặc con đầu tiên
        if (vh.itemView.isFocusable && vh.itemView.requestFocus()) return true
        val target = vh.itemView.findFocus() ?: firstFocusableIn(vh.itemView)
        return target?.requestFocus() == true
    }

    fun firstFocusableIn(root: View): View? {
        if (root.visibility != View.VISIBLE) return null
        if (root.isFocusable) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val f = firstFocusableIn(root.getChildAt(i))
                if (f != null) return f
            }
        }
        return null
    }

    /** Khôi phục focus về ô đã nhớ (gọi từ onResume) — ưu tiên KHOÁ nội dung vì position drift sau refresh. */
    fun restore(host: RowHost) {
        val rv = host.outerRecyclerView ?: return
        if (lastSlot == null && lastKey == null) {
            if (!host.focusHero()) {
                focusNow(rv, 1, 0)
            }
            return
        }
        var left = 8
        val tryOnce = object : Runnable {
            override fun run() {
                // re-resolve moi lan: vi tri drift theo DiffUtil pending updates
                val slot = lastKey?.let { host.slotFor(it) } ?: lastSlot
                val done = slot?.let { (rowPos, idx) ->
                    if (focusNow(rv, rowPos, idx)) true
                    else { rv.scrollToPosition(rowPos); false }
                } ?: false
                if (done || left <= 0) return
                left--
                rv.postDelayed(this, 160)
            }
        }
        rv.post(tryOnce)
    }
}
