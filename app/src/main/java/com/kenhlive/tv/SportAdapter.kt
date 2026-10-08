package com.kenhlive.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.kenhlive.tv.ui.FocusKit

/**
 * Sport Zone (kieu IMG_2815, toi uu dac thu Socolive):
 *  pos 0: Hero banner (Tran tam diem + Dong ho & Lich am duong + Xem ngay & Chi tiet)
 *  pos 1: Rail "Trực Tiếp & Tâm Điểm Thể Thao" (Card ti so 0:0, co/logo 2 doi, LIVE do / dem nguoc ho phach)
 *  pos 2: Rail "Bình Luận Viên Tâm Điểm" (Top phong live BLV Socolive dang phat voi the gradient da sac)
 *  pos 3+: "Khám Phá Nhanh" / Danh sach tran theo ngay
 */
class SportAdapter(
    private val onGroupClick: (LiveMatchGroup) -> Unit,
    private val onGroupLong: (LiveMatchGroup) -> Unit,
    private val onFixtureClick: (ScheduleMatch) -> Unit,
    private val onLeaguePick: (Int) -> Unit,
    private val onGroupDetails: ((LiveMatchGroup) -> Unit)? = null,
    private val onRoomClick: ((LiveRoom) -> Unit)? = null
) : ListAdapter<Any, RecyclerView.ViewHolder>(DIFF), FocusKit.RowHost {

    data class ChipsItem(val labels: List<String>, val sel: Int)
    data class HeroItem(val groups: List<LiveMatchGroup>)
    data class RailItem(
        val groups: List<LiveMatchGroup>,
        val upcoming: List<ScheduleMatch> = emptyList(),
        val title: String = ""
    )
    data class BlvRowItem(val rooms: List<LiveRoom>)

    companion object {
        private const val TYPE_CHIPS = 0
        private const val TYPE_HERO = 1
        private const val TYPE_RAIL = 2
        private const val TYPE_BLV = 3
        private const val TYPE_DAY = 4
        private const val TYPE_MATCH = 5

        private val DIFF = object : DiffUtil.ItemCallback<Any>() {
            override fun areItemsTheSame(a: Any, b: Any) = when {
                a is ChipsItem && b is ChipsItem -> true
                a is HeroItem && b is HeroItem -> true
                a is RailItem && b is RailItem -> a.title == b.title
                a is BlvRowItem && b is BlvRowItem -> true
                a is String && b is String -> a == b
                a is ScheduleMatch && b is ScheduleMatch -> a.scheduleId == b.scheduleId
                else -> false
            }
            override fun areContentsTheSame(a: Any, b: Any) = when {
                a is ChipsItem && b is ChipsItem -> a.labels == b.labels && a.sel == b.sel
                a is HeroItem && b is HeroItem -> a.groups.map { it.matchTitle } == b.groups.map { it.matchTitle }
                a is RailItem && b is RailItem ->
                    a.title == b.title &&
                        a.groups.map { it.matchTitle to it.totalViewers } == b.groups.map { it.matchTitle to it.totalViewers } &&
                        a.upcoming.map { it.scheduleId } == b.upcoming.map { it.scheduleId }
                a is BlvRowItem && b is BlvRowItem ->
                    a.rooms.map { it.roomNum to it.viewers } == b.rooms.map { it.roomNum to it.viewers }
                else -> a == b
            }
        }
    }

    override var outerRecyclerView: RecyclerView? = null
    override val headerPositions: Int get() = 2

    private val pool = RecyclerView.RecycledViewPool()
    private val blvPool = RecyclerView.RecycledViewPool()

    override fun onAttachedToRecyclerView(rv: RecyclerView) { outerRecyclerView = rv }
    override fun onDetachedFromRecyclerView(rv: RecyclerView) { outerRecyclerView = null }

    override fun slotFor(key: String): Pair<Int, Int>? {
        val parts = key.split('|', limit = 3)
        if (parts.size < 3) return null
        val (kind, lg, mt) = parts
        for (pos in 0 until itemCount) {
            when (val cur = getItem(pos)) {
                is RailItem -> {
                    val all = (cur.groups as? List<LiveMatchGroup>) ?: emptyList()
                    var i = all.indexOfFirst { g -> g.league == lg && g.matchTitle == mt }
                    if (i < 0) {
                        val j = cur.upcoming.indexOfFirst { m -> m.league == lg && "${m.host} vs ${m.guest}" == mt }
                        if (j >= 0) i = all.size + j
                    }
                    if (i >= 0) return pos to i
                }
                is ScheduleMatch -> if (kind == "M" && cur.league == lg && "${cur.host} vs ${cur.guest}" == mt) return pos to 0
                else -> {}
            }
        }
        return null
    }

    override fun focusHero(): Boolean {
        val rv = outerRecyclerView ?: return false
        for (i in 0 until itemCount) {
            if (getItem(i) is HeroItem) {
                val vh = rv.findViewHolderForAdapterPosition(i) as? HeroVH
                if (vh != null) {
                    val inner = vh.pager.getChildAt(0) as? RecyclerView
                    val page = inner?.findViewHolderForAdapterPosition(vh.pager.currentItem)?.itemView
                    if (page?.findViewById<View>(R.id.heroPlay)?.requestFocus() == true) return true
                } else {
                    rv.scrollToPosition(i)
                    rv.post {
                        val vh2 = rv.findViewHolderForAdapterPosition(i) as? HeroVH
                        val inner = vh2?.pager?.getChildAt(0) as? RecyclerView
                        val page = inner?.findViewHolderForAdapterPosition(vh2?.pager?.currentItem ?: 0)?.itemView
                        page?.findViewById<View>(R.id.heroPlay)?.requestFocus()
                    }
                    return true
                }
            }
        }
        return false
    }

    fun restoreFocus() = FocusKit.restore(this)

    fun focusHeroPlay(): Boolean {
        val rv = outerRecyclerView ?: return false
        for (i in 0 until itemCount) {
            if (getItem(i) is HeroItem) {
                val vh = rv.findViewHolderForAdapterPosition(i) as? HeroVH ?: continue
                val inner = vh.pager.getChildAt(0) as? RecyclerView ?: continue
                val page = inner.findViewHolderForAdapterPosition(vh.pager.currentItem)?.itemView ?: continue
                return page.findViewById<View>(R.id.heroPlay)?.requestFocus() == true
            }
        }
        return false
    }

    fun focusRailFirst(): Boolean {
        val rv = outerRecyclerView ?: return false
        for (i in 0 until itemCount) {
            if (getItem(i) is RailItem) {
                // Giữ outer RecyclerView ở đỉnh (offset 0) để Hero banner không bị đẩy sát mép trên
                rv.scrollToPosition(0)
                val vh = rv.findViewHolderForAdapterPosition(i) as? RailVH
                if (vh != null) {
                    vh.list.scrollToPosition(0)
                    val card0 = vh.list.findViewHolderForAdapterPosition(0)?.itemView
                        ?: vh.list.layoutManager?.findViewByPosition(0)
                        ?: FocusKit.firstFocusableIn(vh.list)
                    if (card0 != null && card0.requestFocus()) {
                        FocusKit.remember(i, 0)
                        return true
                    }
                }
                var left = 8
                val runnable = object : Runnable {
                    override fun run() {
                        val vh2 = rv.findViewHolderForAdapterPosition(i) as? RailVH
                        if (vh2 != null) {
                            vh2.list.scrollToPosition(0)
                            val c = vh2.list.findViewHolderForAdapterPosition(0)?.itemView
                                ?: vh2.list.layoutManager?.findViewByPosition(0)
                                ?: FocusKit.firstFocusableIn(vh2.list)
                            if (c != null && c.requestFocus()) {
                                FocusKit.remember(i, 0)
                                return
                            }
                        }
                        if (--left > 0) {
                            rv.postDelayed(this, 50)
                        } else {
                            // Cứu cánh nếu không bắt được card: đưa về nút Hero hoặc phần tử đầu tiên
                            if (rv.findFocus() == null) {
                                if (!focusHero()) FocusKit.firstFocusableIn(rv)?.requestFocus()
                            }
                        }
                    }
                }
                rv.post(runnable)
                return true
            }
        }
        return false
    }

    inner class ChipsVH(v: View) : RecyclerView.ViewHolder(v) {
        val row: LinearLayout = v.findViewById(R.id.chipRow)
    }

    inner class HeroVH(v: View) : RecyclerView.ViewHolder(v) {
        val pager: androidx.viewpager2.widget.ViewPager2 = v.findViewById(R.id.heroPager)
        val dots: LinearLayout = v.findViewById(R.id.heroDots)
        init {
            pager.clipToOutline = true
        }
    }

    inner class RailVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val count: TextView = v.findViewById(R.id.rowCount)
        val list: RecyclerView = v.findViewById(R.id.rowList)
        val cards = MatchCardsAdapter(onGroupClick, onGroupLong, onFixtureClick)
        init {
            (v as? ViewGroup)?.clipChildren = false
            (v as? ViewGroup)?.clipToPadding = false
            list.layoutManager = object : LinearLayoutManager(list.context, HORIZONTAL, false) {
                override fun requestChildRectangleOnScreen(
                    parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean
                ): Boolean {
                    val density = parent.context.resources.displayMetrics.density
                    val extraPeek = (140 * density).toInt()
                    val targetRect = android.graphics.Rect(rect.left, rect.top, rect.right + extraPeek, rect.bottom)
                    return super.requestChildRectangleOnScreen(parent, child, targetRect, immediate, focusedChildVisible)
                }
            }
            list.setHasFixedSize(true)
            list.setItemViewCacheSize(8)
            list.setRecycledViewPool(pool)
            list.adapter = cards
            list.clipChildren = false
            list.clipToPadding = false
            (list.parent as? ViewGroup)?.let { it.clipChildren = false; it.clipToPadding = false }
            cards.keyHandler = { rowPos, idx, size -> FocusKit.rowCardKey(this@SportAdapter, rowPos, idx, size) }
        }
    }

    inner class BlvRowVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val count: TextView = v.findViewById(R.id.rowCount)
        val list: RecyclerView = v.findViewById(R.id.rowList)
        val cards = BlvCardsAdapter(
            onRoomClick = { r -> onRoomClick?.invoke(r) }
        )
        init {
            (v as? ViewGroup)?.clipChildren = false
            (v as? ViewGroup)?.clipToPadding = false
            list.layoutManager = object : LinearLayoutManager(list.context, HORIZONTAL, false) {
                override fun requestChildRectangleOnScreen(
                    parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean
                ): Boolean {
                    val density = parent.context.resources.displayMetrics.density
                    val extraPeek = (140 * density).toInt()
                    val targetRect = android.graphics.Rect(rect.left, rect.top, rect.right + extraPeek, rect.bottom)
                    return super.requestChildRectangleOnScreen(parent, child, targetRect, immediate, focusedChildVisible)
                }
            }
            list.setHasFixedSize(true)
            list.setItemViewCacheSize(8)
            list.setRecycledViewPool(blvPool)
            list.adapter = cards
            list.clipChildren = false
            list.clipToPadding = false
            (list.parent as? ViewGroup)?.let { it.clipChildren = false; it.clipToPadding = false }
        }
    }

    inner class DayVH(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(R.id.dayLabel)
        val count: TextView = v.findViewById(R.id.dayCount)
    }

    inner class MatchVH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.matchTime)
        val hostIcon: android.widget.ImageView = v.findViewById(R.id.hostIcon)
        val guestIcon: android.widget.ImageView = v.findViewById(R.id.guestIcon)
        val name: TextView = v.findViewById(R.id.matchName)
        val crest: android.widget.ImageView = v.findViewById(R.id.leagueCrest)
        val league: TextView = v.findViewById(R.id.matchLeague)
        val badge: TextView = v.findViewById(R.id.statusBadge)
        val anchorRow: LinearLayout = v.findViewById(R.id.anchorRow)
    }

    override fun getItemViewType(pos: Int) = when (getItem(pos)) {
        is ChipsItem -> TYPE_CHIPS
        is HeroItem -> TYPE_HERO
        is RailItem -> TYPE_RAIL
        is BlvRowItem -> TYPE_BLV
        is String -> TYPE_DAY
        else -> TYPE_MATCH
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_CHIPS -> ChipsVH(inf.inflate(R.layout.item_league_chips, parent, false))
            TYPE_HERO -> HeroVH(inf.inflate(R.layout.item_hero_pager, parent, false))
            TYPE_RAIL -> RailVH(inf.inflate(R.layout.item_row, parent, false))
            TYPE_BLV -> BlvRowVH(inf.inflate(R.layout.item_row, parent, false))
            TYPE_DAY -> DayVH(inf.inflate(R.layout.item_day_header, parent, false))
            else -> MatchVH(inf.inflate(R.layout.item_schedule_match, parent, false))
        }
    }

    override fun onViewRecycled(h: RecyclerView.ViewHolder) {
        if (h is HeroVH) (h.pager.adapter as? HeroPagerAdapter)?.detach()
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        when (val item = getItem(pos)) {
            is ChipsItem -> {
                val vh = h as ChipsVH
                vh.row.removeAllViews()
                val inf = LayoutInflater.from(vh.row.context)
                item.labels.forEachIndexed { i, lab ->
                    val chip = inf.inflate(R.layout.item_search_chip, vh.row, false) as TextView
                    chip.text = lab
                    chip.isSelected = i == item.sel
                    chip.setOnClickListener { onLeaguePick(i) }
                    chip.setOnKeyListener { _, code, ev ->
                        if (ev.action == android.view.KeyEvent.ACTION_DOWN &&
                            code == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                            if (!focusHeroPlay()) focusRailFirst(); true
                        } else false
                    }
                    vh.row.addView(chip)
                }
            }
            is HeroItem -> {
                val vh = h as HeroVH
                if (item.groups.isNotEmpty()) {
                    vh.itemView.visibility = View.VISIBLE
                    var cur = (vh.pager.adapter as? HeroPagerAdapter)
                    if (cur == null || cur.itemCount != item.groups.size.coerceAtMost(8)) {
                        cur = HeroPagerAdapter(item.groups, onGroupClick, onGroupDetails, onDownKey = { focusRailFirst() })
                        vh.pager.adapter = cur
                        cur.attach(vh.pager, vh.dots)
                    } else {
                        cur.onDownKey = { focusRailFirst() }
                    }
                    vh.pager.setOnKeyListener { _, code, ev ->
                        if (ev.action == android.view.KeyEvent.ACTION_DOWN &&
                            code == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                            focusRailFirst()
                            true
                        } else false
                    }
                } else {
                    vh.itemView.visibility = View.GONE
                }
            }
            is RailItem -> {
                val vh = h as RailVH
                val empty = item.groups.isEmpty() && item.upcoming.isEmpty()
                vh.itemView.visibility = if (empty) View.GONE else View.VISIBLE
                if (item.title.isNotBlank()) {
                    vh.title.text = item.title
                } else {
                    vh.title.setText(R.string.sport_rail_title)
                }
                vh.count.text = if (item.groups.isNotEmpty()) "${item.groups.size} trận" else ""
                vh.count.visibility = if (item.groups.isNotEmpty()) View.VISIBLE else View.GONE
                vh.cards.rowPos = pos
                vh.cards.submitList((item.groups as List<Any>) + item.upcoming)
            }
            is BlvRowItem -> {
                val vh = h as BlvRowVH
                val empty = item.rooms.isEmpty()
                vh.itemView.visibility = if (empty) View.GONE else View.VISIBLE
                vh.title.setText(R.string.sport_blv_title)
                vh.count.visibility = View.GONE
                vh.cards.submitList(item.rooms)
            }
            is String -> {
                val vh = h as DayVH
                vh.tv.text = item
                var n = 0
                for (j in pos + 1 until itemCount) { if (getItem(j) !is ScheduleMatch) break; n++ }
                vh.count.text = if (n > 0) h.itemView.context.getString(R.string.sched_matches_count, n) else ""
            }
            is ScheduleMatch -> {
                val vh = h as MatchVH
                val ctx = h.itemView.context
                vh.itemView.setOnFocusChangeListener { v, has ->
                    if (has) FocusKit.remember(pos, 0, "M|${item.league}|${item.host} vs ${item.guest}")
                    v.animate().scaleX(if (has) 1.015f else 1f).scaleY(if (has) 1.015f else 1f)
                        .setDuration(130).start()
                    v.elevation = if (has) 10f else 0f
                }
                vh.itemView.setOnClickListener { onFixtureClick(item) }
                vh.itemView.setOnKeyListener { _, keyCode, event ->
                    if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                        for (prevPos in pos - 1 downTo 0) {
                            val prevItem = getItem(prevPos)
                            if (prevItem is RailItem) {
                                if (focusRailFirst()) return@setOnKeyListener true
                                outerRecyclerView?.let { rv ->
                                    if (FocusKit.focusNow(rv, prevPos, 0)) return@setOnKeyListener true
                                }
                                return@setOnKeyListener true
                            } else if (prevItem is ScheduleMatch) {
                                break
                            }
                        }
                    }
                    false
                }
                vh.name.text = "${item.host} vs ${item.guest}"
                vh.league.text = item.league
                vh.time.text = SocoliveRepository.formatTime(item.matchTimeMs)
                vh.badge.visibility = View.VISIBLE
                if (item.isLive) {
                    vh.badge.setText(R.string.sched_live)
                    vh.badge.setBackgroundResource(R.drawable.bg_badge_live_red)
                    vh.badge.setTextColor(0xFFFFFFFF.toInt())
                    vh.time.setTextColor(ContextCompat.getColor(ctx, R.color.kl_live))
                } else if (item.hasRoom) {
                    vh.badge.setText(R.string.badge_has_room)
                    vh.badge.setBackgroundResource(R.drawable.bg_badge_glass)
                    vh.badge.setTextColor(ContextCompat.getColor(ctx, R.color.kl_text_2))
                    vh.time.setTextColor(ContextCompat.getColor(ctx, R.color.kl_text_1))
                } else {
                    vh.badge.setText(R.string.badge_no_room)
                    vh.badge.setBackgroundResource(R.drawable.bg_badge_glass)
                    vh.badge.setTextColor(ContextCompat.getColor(ctx, R.color.kl_text_3))
                    vh.time.setTextColor(ContextCompat.getColor(ctx, R.color.kl_text_1))
                }
                vh.hostIcon.load(item.hostIcon) { crossfade(0); placeholder(R.drawable.logo_placeholder); error(null) }
                vh.guestIcon.load(item.guestIcon) { crossfade(0); placeholder(R.drawable.logo_placeholder); error(null) }
                vh.crest.load(item.leagueCrest.ifBlank { item.hostIcon }) { crossfade(0); error(null) }
                vh.anchorRow.removeAllViews()
                val inf = LayoutInflater.from(ctx)
                item.anchors.filter { it.roomNum.isNotBlank() }.take(4).forEach { a ->
                    val iv = inf.inflate(R.layout.item_anchor_avatar, vh.anchorRow, false) as android.widget.ImageView
                    iv.load(a.icon) { crossfade(0); placeholder(R.drawable.logo_placeholder); error(null) }
                    vh.anchorRow.addView(iv)
                }
            }
        }
    }
}
