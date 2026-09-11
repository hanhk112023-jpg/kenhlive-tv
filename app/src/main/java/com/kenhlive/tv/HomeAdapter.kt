package com.kenhlive.tv

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.children
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import coil.transform.CircleCropTransformation
import coil.transform.RoundedCornersTransformation

/**
 * Trang chủ chuẩn TV 10-foot:
 *  Row 0: HERO full-bleed top 5 trận
 *  Row 1+: theo giải — card thumbnail 16:9, mỗi card = 1 TRẬN (gộp N phòng).
 */
class HomeAdapter(
    private var groups: List<LiveMatchGroup>,
    private val lifecycleOwner: LifecycleOwner,
    private val onGroupClick: (LiveMatchGroup) -> Unit,
    private val onLongClickGroup: (LiveMatchGroup) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HERO = 0
        private const val TYPE_ROW = 1
        private fun buildRows(groups: List<LiveMatchGroup>) =
            groups.groupBy { it.league }
                .entries.sortedByDescending { it.value.sumOf { g -> g.totalViewers } }
                .map { it.key to it.value }
    }

    private var rows: List<Pair<String, List<LiveMatchGroup>>> = buildRows(groups)
    private var rv: RecyclerView? = null
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) { rv = recyclerView }
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) { rv = null }

    /** (rowPos, idx) cua card dang giu focus — DOC THOI DIEM submit() (truoc khi dispatch
     *  destroy VH), mot lan dung. Truoc day auto-refresh rebind full hang khi VH dang focus
     *  bi detach -> mat focus (khong co gi de phuc hoi vi VH moi khong con thuoc VH cu)
     *  -> phim D-pad tiep theo roi vong focus-search mac dinh = "nhay lung tung" con sot. */
    private var pendingRestore: Pair<Int, Int>? = null

    private fun currentFocusSlot(): Pair<Int, Int>? {
        val rvw = rv ?: return null
        for (pos in 0 until itemCount) {
            val vh = rvw.findViewHolderForAdapterPosition(pos) as? RowVH ?: continue
            val i = focusedChildIndex(vh.container)
            if (i >= 0) return pos to i
        }
        return null
    }

    /** Cập nhật dữ liệu mới (auto-refresh 3 phút).
     *  Dùng DiffUtil THEO ROW: chỉ rebind hàng thực sự thay đổi → RecyclerView giữ nguyên
     *  ViewHolder + focus đang có. notifyDataSetChanged() cũ destroy mọi view giữa lúc
     *  user đang bấm D-pad = nguồn lỗi "đi 1 hướng nhảy lung tung". */
    fun submit(newGroups: List<LiveMatchGroup>) {
        pendingRestore = currentFocusSlot()
        val oldRows = rows
        groups = newGroups
        val newRows = buildRows(groups)
        val sameStructure = oldRows.map { it.first } == newRows.map { it.first }
        if (sameStructure) {
            // cùng số hàng + cùng giải: chỉ rebind hàng nào đổi nội dung
            newRows.forEachIndexed { i, (_, gs) ->
                val oldTitles = oldRows[i].second.map { it.matchTitle }
                if (oldTitles != gs.map { it.matchTitle }) notifyItemChanged(i + 1)
                else if (itemCount > i + 1) notifyItemChanged(i + 1, "data")
            }
            rows = newRows
            return
        }
        rows = newRows
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldRows.size + 1
            override fun getNewListSize() = newRows.size + 1
            // hero luôn ở vị trí 0
            override fun areItemsTheSame(o: Int, n: Int) =
                if (o == 0 || n == 0) o == n else oldRows[o - 1].first == newRows[n - 1].first
            override fun areContentsTheSame(o: Int, n: Int) =
                if (o == 0 || n == 0) true
                else oldRows[o - 1].second.map { it.matchTitle } == newRows[n - 1].second.map { it.matchTitle }
        }, false).dispatchUpdatesTo(this)
    }

    inner class HeroVH(v: View) : RecyclerView.ViewHolder(v) {
        val pager: ViewPager2 = v.findViewById(R.id.heroPager)
    }

    inner class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val container: LinearLayout = v.findViewById(R.id.rowContainer)
    }

    override fun getItemViewType(pos: Int) = if (pos == 0) TYPE_HERO else TYPE_ROW
    override fun getItemCount() = 1 + rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HERO)
            HeroVH(inf.inflate(R.layout.item_hero_pager, parent, false))
        else
            RowVH(inf.inflate(R.layout.item_row, parent, false))
    }

    /** Tự điều hướng D-pad trên card — KHÔNG để focus-search mặc định của Android quyết:
     *  mỗi hàng là HorizontalScrollView với offset cuộn RIÊNG, tìm 'gần nhất hình học'
     *  sẽ rơi vào ô khác cột / hàng khác = bug "nhảy sai vị trí".
     *  - LEFT/RIGHT ở mép: nuốt (đứng yên tại biên).
     *  - DOWN/UP: sang hàng kề đúng CÙNG CỘT idx (clamp), tự cuộn hàng đó vào tầm nhìn.
     *  - UP từ hàng đầu: về nút hero. */
    private fun applyEdgeFocusGuard(card: View, rowPos: Int, idx: Int, size: Int) {
        // Chuyen dong focus: phong to nhe + bong den (style Netflix TV) thay vi vien do tinh
        card.setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.07f else 1.0f).scaleY(if (has) 1.07f else 1.0f)
                .setDuration(140).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            v.elevation = if (has) 18f else 0f
        }
        card.setOnKeyListener { _, keyCode, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT  -> idx == 0
                KeyEvent.KEYCODE_DPAD_RIGHT -> idx == size - 1
                KeyEvent.KEYCODE_DPAD_DOWN  -> focusRowCard(rowPos, rowPos + 1, idx)
                KeyEvent.KEYCODE_DPAD_UP    ->
                    if (rowPos <= 1) focusHeroPlay()
                    else focusRowCard(rowPos, rowPos - 1, idx)
                else -> false
            }
        }
    }

    private var pendingFocus: Pair<Int, Int>? = null

    /** Focus ô cùng cột `idx` của hàng `targetPos`; hàng chưa layout thì cuộn RecyclerView
     *  tới đó rồi retry (tối đa ~1.5s) — không để lọt về focus-search mặc định (= nhảy sai). */
    private fun focusRowCard(fromPos: Int, targetPos: Int, idx: Int): Boolean {
        val rvw = rv ?: return false
        if (targetPos < 0 || targetPos >= itemCount) return true // nuốt phím tại biên dọc
        focusRowNow(rvw, targetPos, idx)?.let { if (it) return true }
        pendingFocus = targetPos to idx
        rvw.smoothScrollBy(0, if (targetPos > fromPos) 480 else -480)
        rvw.postDelayed({ retryPending(8) }, 220)
        return true
    }

    private fun focusRowNow(rvw: RecyclerView, pos: Int, idx: Int): Boolean? {
        val vh = rvw.findViewHolderForAdapterPosition(pos) as? RowVH ?: return null
        if (vh.container.childCount == 0) return null
        val target = vh.container.getChildAt(idx.coerceAtMost(vh.container.childCount - 1))
        (vh.container.parent as? android.widget.HorizontalScrollView)
            ?.smoothScrollTo(target.left - 40, 0)
        return target.requestFocus()
    }

    private fun retryPending(n: Int) {
        val (pos, idx) = pendingFocus ?: return
        val rvw = rv ?: run { pendingFocus = null; return }
        if (focusRowNow(rvw, pos, idx) == true) { pendingFocus = null; return }
        if (n > 0) rvw.postDelayed({ retryPending(n - 1) }, 180) else pendingFocus = null
    }

    /** UP từ hàng đầu tiên → nút ▶ XEM NGAY của hero trang hiện tại.
     *  Hero chua duoc layout (user dang cuon xuong xa) -> CUON LEN + retry thay vi
     *  tra false (false = de Android focus-search mac dinh nat di tab bar/noi khac). */
    private var pendingHeroFocus = false
    private fun focusHeroPlay(): Boolean {
        val rvw = rv ?: return false
        if (tryHeroPlay()) return true
        pendingHeroFocus = true
        rvw.smoothScrollToPosition(0)
        rvw.postDelayed({ retryHeroFocus(8) }, 240)
        return true
    }
    private fun retryHeroFocus(n: Int) {
        if (!pendingHeroFocus) return
        if (tryHeroPlay()) { pendingHeroFocus = false; return }
        if (n > 0) rv?.postDelayed({ retryHeroFocus(n - 1) }, 180) else pendingHeroFocus = false
    }
    private fun tryHeroPlay(): Boolean {
        val vh = rv?.findViewHolderForAdapterPosition(0) as? HeroVH ?: return false
        val inner = vh.pager.getChildAt(0) as? ViewGroup ?: return false
        val page: View = inner.children.toList()
            .getOrNull(vh.pager.currentItem.coerceAtMost(inner.childCount - 1)) ?: return false
        return page.findViewById<View>(R.id.heroPlay)?.requestFocus() ?: false
    }

    /** Payload "data": cập nhật text viewers tại chỗ, KHÔNG rebuild view → giữ nguyên focus D-pad. */
    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int, payloads: List<Any>) {
        if (payloads.isNotEmpty() && payloads[0] == "data") {
            if (h is RowVH && pos - 1 < rows.size) {
                val list = rows[pos - 1].second
                val cont = h.container
                for (i in 0 until minOf(cont.childCount, list.size)) {
                    val card = cont.getChildAt(i)
                    val g = list[i]
                    card.findViewById<TextView>(R.id.cardViewers)?.text =
                        "👁 ${SocoliveRepository.fmtViewers(g.totalViewers)}"
                    card.findViewById<TextView>(R.id.cardBlv)?.text =
                        g.top.blvName + if (g.count > 1) " +${g.count - 1} BLV" else ""
                    card.findViewById<TextView>(R.id.roomBadge)?.let { b ->
                        if (g.count > 1) { b.text = "${g.count} phòng"; b.visibility = View.VISIBLE }
                        else b.visibility = View.GONE
                    }
                    card.setOnClickListener { onGroupClick(g) }
                    card.setOnLongClickListener { onLongClickGroup(g); true }
                }
            }
            return // hero: giữ nguyên trang đang xem, không rebind
        }
        onBindViewHolder(h, pos)
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        if (h is HeroVH) {
            // BUG-07: adapter cu phai duoc detach — neu khong Runnable `auto` cua no van
            // post Delayed mãi trên Handler chung, leak pager + data cu, hero tu lat theo trang cu.
            (h.pager.adapter as? HeroAdapter)?.detach()
            val hero = HeroAdapter(groups.take(5)) { g -> onGroupClick(g) }
            h.pager.adapter = hero
            hero.attach(h.pager)
            return
        }
        val (league, list) = rows[pos - 1]
        val vh = h as RowVH
        vh.title.text = league
        // cho phep card scale 1.07 lo ra ngoai container/scrollview (neu khong bi clip mat vien bong)
        vh.container.clipChildren = false
        vh.container.clipToPadding = false
        (vh.container.parent as? ViewGroup)?.let { it.clipChildren = false; it.clipToPadding = false }
        (vh.itemView as? ViewGroup)?.clipChildren = false
        // Nếu hàng này ĐANG chứa view được focus → nhớ vị trí con để khôi phục sau khi rebuild.
        // Không làm vậy: removeAllViews destroy view focused → Android xóa focus →
        // phím D-pad kế tiếp nhảy đi lung tung (bug báo cáo).
        val focusedIdx = if (pendingRestore?.first == pos) { val i = pendingRestore!!.second; pendingRestore = null; i }
                         else focusedChildIndex(vh.container)
        vh.container.removeAllViews()
        val inf = LayoutInflater.from(vh.container.context)
        list.forEachIndexed { idx, g ->
            val card = inf.inflate(R.layout.item_card_horizontal, vh.container, false)
            // Mép hàng: KHÔNG dùng nextFocusLeftId/RightId — mọi card trùng id `cardRoot`
            // nên Android phân giải thành card của HÀNG KHÁC → focus "nhảy lung tung".
            // Thay bằng OnKeyListener nuốt phím ở đúng card biên (xác định, không phụ thuộc id).
            applyEdgeFocusGuard(card, pos, idx, list.size)
            val thumb = card.findViewById<ImageView>(R.id.cardThumb)
            val avatar = card.findViewById<ImageView>(R.id.cardAvatar)
            val blv = card.findViewById<TextView>(R.id.cardBlv)
            val match = card.findViewById<TextView>(R.id.cardMatch)
            val viewers = card.findViewById<TextView>(R.id.cardViewers)
            val badge = card.findViewById<TextView>(R.id.roomBadge)
            // thumbnail = ảnh cover trận (BLV), fallback avatar
            thumb.load(g.top.cover.ifBlank { g.top.avatar }) {
                crossfade(if (KenhLiveApp.lowRam) 0 else 150)
                transformations(RoundedCornersTransformation(14f))
                placeholder(R.drawable.hero_fallback)
                error(R.drawable.hero_fallback)
            }
            avatar.load(g.top.avatar) {
                crossfade(if (KenhLiveApp.lowRam) 0 else 80)
                transformations(CircleCropTransformation())
                placeholder(R.drawable.logo_placeholder)
                error(R.drawable.logo_placeholder)
            }
            blv.text = g.top.blvName + if (g.count > 1) " +${g.count - 1} BLV" else ""
            match.text = g.matchTitle
            viewers.text = "👁 ${SocoliveRepository.fmtViewers(g.totalViewers)}"
            if (g.count > 1) {
                badge.text = "${g.count} phòng"
                badge.visibility = View.VISIBLE
            } else badge.visibility = View.GONE
            card.setOnClickListener { onGroupClick(g) }
            card.setOnLongClickListener { onLongClickGroup(g); true }
            vh.container.addView(card)
        }
        // khôi phục focus vào đúng ô cũ của hàng này (nếu hàng đang giữ focus)
        if (focusedIdx in 0 until vh.container.childCount) {
            vh.container.getChildAt(focusedIdx).requestFocus()
            vh.itemView.post {
                val c = vh.container
                if (c.focusedChild == null && !c.hasFocus()) {
                    c.getChildAt(focusedIdx.coerceAtMost(c.childCount - 1)).requestFocus()
                }
            }
        }
    }

    /** Index của child đang giữ focus trong container (-1 nếu hàng này không chứa focus). */
    private fun focusedChildIndex(container: ViewGroup): Int {
        if (!container.hasFocus()) return -1
        val f = container.findFocus() ?: return -1
        var v: View? = f
        while (v != null && v.parent !== container) v = v.parent as? View
        return if (v == null) -1 else container.indexOfChild(v)
    }
}
