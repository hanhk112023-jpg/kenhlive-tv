package com.kenhlive.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.kenhlive.tv.ui.FocusKit

/**
 * Sport Zone (kieu tab The thao FPT Play moi):
 *  pos 0: chip giai dau (Tong hop + cac giai, loc toan bo rail + danh sach)
 *  pos 1: rail "Truc tiep & Sap dien ra" (card doc ngang kieu FPT: LIVE do / gio lon)
 *  pos 2+: danh sach theo ngay (header ngay + tran) nhu cu
 */
class SportAdapter(
    private val onGroupClick: (LiveMatchGroup) -> Unit,
    private val onGroupLong: (LiveMatchGroup) -> Unit,
    private val onFixtureClick: (ScheduleMatch) -> Unit,
    private val onLeaguePick: (Int) -> Unit
) : ListAdapter<Any, RecyclerView.ViewHolder>(DIFF), FocusKit.RowHost {

    data class ChipsItem(val labels: List<String>, val sel: Int)
    data class HeroItem(val groups: List<LiveMatchGroup>)
    data class RailItem(val groups: List<LiveMatchGroup>, val upcoming: List<ScheduleMatch>)

    companion object {
        private const val TYPE_CHIPS = 0
        private const val TYPE_HERO = 1
        private const val TYPE_RAIL = 2
        private const val TYPE_DAY = 3
        private const val TYPE_MATCH = 4

        private val DIFF = object : DiffUtil.ItemCallback<Any>() {
            override fun areItemsTheSame(a: Any, b: Any) = when {
                a is ChipsItem && b is ChipsItem -> true
                a is HeroItem && b is HeroItem -> true
                a is RailItem && b is RailItem -> true
                a is String && b is String -> a == b
                a is ScheduleMatch && b is ScheduleMatch -> a.scheduleId == b.scheduleId
                else -> false
            }
            override fun areContentsTheSame(a: Any, b: Any) = when {
                a is ChipsItem && b is ChipsItem -> a.labels == b.labels && a.sel == b.sel
                a is HeroItem && b is HeroItem -> a.groups.map { it.matchTitle } == b.groups.map { it.matchTitle }
                a is RailItem && b is RailItem ->
                    a.groups.map { it.matchTitle to it.totalViewers } == b.groups.map { it.matchTitle to it.totalViewers } &&
                        a.upcoming.map { it.scheduleId } == b.upcoming.map { it.scheduleId }
                else -> a == b
            }
        }
    }

    override var outerRecyclerView: RecyclerView? = null
    override val headerPositions: Int get() = 3

    private val pool = RecyclerView.RecycledViewPool()

    override fun onAttachedToRecyclerView(rv: RecyclerView) { outerRecyclerView = rv }
    override fun onDetachedFromRecyclerView(rv: RecyclerView) { outerRecyclerView = null }

    /** Dinh vi slot theo khoa noi dung (chong drift vi tri sau auto-refresh). */
    override fun slotFor(key: String): Pair<Int, Int>? {
        val rv = outerRecyclerView ?: return null
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
                    if (i >= 0) return 2 to i
                }
                is ScheduleMatch -> if (kind == "M" && cur.league == lg && "${cur.host} vs ${cur.guest}" == mt) return pos to 0
                else -> {}
            }
        }
        return null
    }

    /** UP tu rail/danh sach -> nut XEM NGAY cua hero; hero chua layout -> chip. */
    override fun focusHero(): Boolean {
        val rv = outerRecyclerView ?: return false
        (rv.findViewHolderForAdapterPosition(1) as? HeroVH)?.let { vh ->
            val inner = vh.pager.getChildAt(0) as? RecyclerView
            val page = inner?.findViewHolderForAdapterPosition(vh.pager.currentItem)?.itemView
            if (page?.findViewById<View>(R.id.heroPlay)?.requestFocus() == true) return true
        }
        val ch = rv.findViewHolderForAdapterPosition(0) as? ChipsVH ?: return false
        return ch.row.getChildAt(0)?.requestFocus() ?: false
    }

    fun restoreFocus() = FocusKit.restore(this)

    /** Focus nut XEM NGAY cua page hero hien tai (dinh vi, khong de geometry doan). */
    fun focusHeroPlay(): Boolean {
        val rv = outerRecyclerView ?: return false
        val vh = rv.findViewHolderForAdapterPosition(1) as? HeroVH ?: return false
        val inner = vh.pager.getChildAt(0) as? RecyclerView ?: return false
        val page = inner.findViewHolderForAdapterPosition(vh.pager.currentItem)?.itemView ?: return false
        return page.findViewById<View>(R.id.heroPlay)?.requestFocus() == true
    }

    /** Focus card DAUTHIEN (idx 0) cua rail LIVE & SAP. */
    fun focusRailFirst(): Boolean {
        val rv = outerRecyclerView ?: return false
        if (FocusKit.focusNow(rv, 2, 0)) return true
        rv.smoothScrollBy(0, 300)
        var left = 6
        object : Runnable {
            override fun run() {
                if (left > 0 && !FocusKit.focusNow(rv, 2, 0)) { left--; rv.postDelayed(this, 160) }
            }
        }.run()
        return true
    }

    inner class ChipsVH(v: View) : RecyclerView.ViewHolder(v) {
        val row: LinearLayout = v.findViewById(R.id.chipRow)
    }

    inner class HeroVH(v: View) : RecyclerView.ViewHolder(v) {
        val pager: androidx.viewpager2.widget.ViewPager2 = v.findViewById(R.id.heroPager)
        val dots: LinearLayout = v.findViewById(R.id.heroDots)
        init {
            // XML clipToOutline chi hieu luc API31+; set bang code de bo goc banner tren moi ROM (API21+)
            pager.clipToOutline = true
        }
    }

    inner class RailVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val count: TextView = v.findViewById(R.id.rowCount)
        val list: RecyclerView = v.findViewById(R.id.rowList)
        val cards = MatchCardsAdapter(onGroupClick, onGroupLong, onFixtureClick)
        init {
            list.layoutManager = LinearLayoutManager(list.context, LinearLayoutManager.HORIZONTAL, false)
            list.setRecycledViewPool(pool)
            list.adapter = cards
            list.clipChildren = false
            list.clipToPadding = false
            (list.parent as? ViewGroup)?.let { it.clipChildren = false }
            cards.keyHandler = { rowPos, idx, size -> FocusKit.rowCardKey(this@SportAdapter, rowPos, idx, size) }
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
        is String -> TYPE_DAY
        else -> TYPE_MATCH
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_CHIPS -> ChipsVH(inf.inflate(R.layout.item_league_chips, parent, false))
            TYPE_HERO -> HeroVH(inf.inflate(R.layout.item_hero_pager, parent, false))
            TYPE_RAIL -> RailVH(inf.inflate(R.layout.item_row, parent, false))
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
                // canh phai chip row = right edge cua card 3 (kieu FPT: thanh loc liep rail)
                (vh.row.parent as? View)?.let { sv ->
                    val screen = sv.rootView.width.takeIf { it > 0 }
                        ?: sv.context.resources.displayMetrics.widthPixels
                    val frac = if (DeviceMode.isTv) 1f - 0.33f else 1f
                    val lp = sv.layoutParams
                    lp.width = (screen * frac).toInt()
                    sv.layoutParams = lp
                }
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
                (vh.pager.adapter as? HeroPagerAdapter)?.detach()
                if (item.groups.isNotEmpty()) {
                    vh.itemView.visibility = View.VISIBLE
                    val a = HeroPagerAdapter(item.groups) { g -> onGroupClick(g) }
                    vh.pager.adapter = a
                    vh.pager.isUserInputEnabled = !DeviceMode.isTv
                    a.attach(vh.pager, vh.dots)
                    vh.itemView.setOnKeyListener { _, code, ev ->
                        if (ev.action == android.view.KeyEvent.ACTION_DOWN &&
                            code == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                            if (!FocusKit.focusNow(outerRecyclerView ?: return@setOnKeyListener false, 2, 0))
                                focusRailFirst()
                            true
                        } else false
                    }
                } else {
                    // khong co tran live -> an banner trong, de man hinh empty khong loe banner den
                    vh.itemView.visibility = View.GONE
                }
            }
            is RailItem -> {
                val vh = h as RailVH
                val empty = item.groups.isEmpty() && item.upcoming.isEmpty()
                vh.itemView.visibility = if (empty) View.GONE else View.VISIBLE
                vh.title.setText(R.string.sport_rail_title)
                vh.count.visibility = View.GONE
                vh.cards.rowPos = pos
                vh.cards.submitList((item.groups as List<Any>) + item.upcoming)
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
                vh.name.text = "${item.host} vs ${item.guest}"
                vh.league.text = item.league
                vh.time.text = SocoliveRepository.formatTime(item.matchTimeMs)
                vh.badge.visibility = View.VISIBLE
                if (item.isLive) {
                    vh.badge.setText(R.string.sched_live)
                    vh.badge.setBackgroundResource(R.drawable.bg_badge_live_red)
                    vh.badge.setTextColor(0xFFFFFFFF.toInt())
                    vh.time.setTextColor(ctx.getColorCompat(R.color.kl_live))
                } else if (item.hasRoom) {
                    vh.badge.setText(R.string.badge_has_room)
                    vh.badge.setBackgroundResource(R.drawable.bg_badge_glass)
                    vh.badge.setTextColor(ctx.getColor(R.color.kl_text_2))
                    vh.time.setTextColor(ctx.getColorCompat(R.color.kl_text_1))
                } else {
                    vh.badge.setText(R.string.badge_no_room)
                    vh.badge.setBackgroundResource(R.drawable.bg_badge_glass)
                    vh.badge.setTextColor(ctx.getColor(R.color.kl_text_3))
                    vh.time.setTextColor(ctx.getColorCompat(R.color.kl_text_1))
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
