package com.kenhlive.tv

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import coil.request.CachePolicy
import coil.size.Scale
import coil.transform.CircleCropTransformation
import coil.transform.RoundedCornersTransformation

/**
 * Trang chủ chuẩn TV 10-foot v5:
 * - Hero full-bleed top 5 trận (ViewPager2)
 * - Row theo giải dùng RecyclerView ngang có RecycledViewPool chung -> tiết kiệm RAM, recycle view
 * - Focus guard, payload update để giữ focus D-pad ổn định
 * - Coil size() + hardware bitmap để giảm RAM TV thấp
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

        // Shared pool cho tất cả row ngang -> giảm tạo ViewHolder
        val sharedPool = RecyclerView.RecycledViewPool().apply {
            setMaxRecycledViews(0, 20)
        }
    }

    private var rows: List<Pair<String, List<LiveMatchGroup>>> = buildRows(groups)

    fun submit(newGroups: List<LiveMatchGroup>) {
        val oldRows = rows
        groups = newGroups
        val newRows = buildRows(groups)
        val sameStructure = oldRows.map { it.first } == newRows.map { it.first }
        if (sameStructure) {
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
            override fun areItemsTheSame(o: Int, n: Int) =
                if (o == 0 || n == 0) o == n else oldRows[o - 1].first == newRows[n - 1].first
            override fun areContentsTheSame(o: Int, n: Int) =
                if (o == 0 || n == 0) true
                else oldRows[o - 1].second.map { it.matchTitle } == newRows[n - 1].second.map { it.matchTitle }
        }, false).dispatchUpdatesTo(this)
    }

    inner class HeroVH(v: View) : RecyclerView.ViewHolder(v) {
        val pager: ViewPager2 = v.findViewById(R.id.heroPager)
        val dots: LinearLayout? = v.findViewById(R.id.heroDots)
        var heroAdapter: HeroAdapter? = null
    }

    inner class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val count: TextView? = v.findViewById(R.id.rowCount)
        val recycler: RecyclerView = v.findViewById(R.id.rowRecycler)
        // legacy container (gone) kept for fallback
        val legacyContainer: LinearLayout? = v.findViewById(R.id.rowContainer)
        var rowAdapter: RowCardAdapter? = null
    }

    override fun getItemViewType(pos: Int) = if (pos == 0) TYPE_HERO else TYPE_ROW
    override fun getItemCount() = 1 + rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HERO)
            HeroVH(inf.inflate(R.layout.item_hero_pager, parent, false))
        else {
            val vh = RowVH(inf.inflate(R.layout.item_row, parent, false))
            // setup horizontal recycler
            vh.recycler.apply {
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                setRecycledViewPool(sharedPool)
                setHasFixedSize(true)
                setItemViewCacheSize(4)
                isNestedScrollingEnabled = false
            }
            vh
        }
    }

    private fun applyEdgeFocusGuard(card: View, idx: Int, size: Int) {
        val blockLeft = idx == 0
        val blockRight = idx == size - 1
        if (!blockLeft && !blockRight) {
            card.setOnKeyListener(null)
            return
        }
        card.setOnKeyListener { _, keyCode, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) false
            else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && blockLeft) true
            else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && blockRight) true
            else false
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int, payloads: List<Any>) {
        if (payloads.isNotEmpty() && payloads[0] == "data") {
            if (h is RowVH && pos - 1 < rows.size) {
                h.rowAdapter?.updatePayload(rows[pos - 1].second)
            }
            return
        }
        onBindViewHolder(h, pos)
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        if (h is HeroVH) {
            // detach old
            h.heroAdapter?.detach()
            val heroList = groups.take(5)
            val hero = HeroAdapter(heroList) { g -> onGroupClick(g) }
            h.pager.adapter = hero
            h.heroAdapter = hero
            hero.attach(h.pager, h.dots)
            // dots init
            h.dots?.let { dots ->
                dots.removeAllViews()
                val ctx = dots.context
                heroList.forEachIndexed { idx, _ ->
                    val dot = View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(12, 12).apply {
                            leftMargin = 6; rightMargin = 6
                        }
                        setBackgroundResource(if (idx == 0) R.drawable.bg_live_dot else R.drawable.bg_dot_inactive)
                        alpha = if (idx == 0) 1f else 0.6f
                    }
                    dots.addView(dot)
                }
                h.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(p: Int) {
                        for (i in 0 until dots.childCount) {
                            dots.getChildAt(i).alpha = if (i == p) 1f else 0.35f
                        }
                    }
                })
            }
            return
        }
        val (league, list) = rows[pos - 1]
        val vh = h as RowVH
        vh.title.text = league
        vh.count?.let {
            it.text = "${list.size} trận"
            it.visibility = View.VISIBLE
        }
        // Row adapter
        if (vh.rowAdapter == null) {
            vh.rowAdapter = RowCardAdapter(list, onGroupClick, onLongClickGroup)
            vh.recycler.adapter = vh.rowAdapter
        } else {
            vh.rowAdapter?.submit(list)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is HeroVH) {
            holder.heroAdapter?.detach()
        }
        super.onViewRecycled(holder)
    }

    /** Adapter con cho từng hàng ngang — nhẹ, chỉ bind card */
    inner class RowCardAdapter(
        private var items: List<LiveMatchGroup>,
        private val onClick: (LiveMatchGroup) -> Unit,
        private val onLong: (LiveMatchGroup) -> Unit
    ) : RecyclerView.Adapter<RowCardAdapter.CardVH>() {

        inner class CardVH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.cardThumb)
            val avatar: ImageView = v.findViewById(R.id.cardAvatar)
            val blv: TextView = v.findViewById(R.id.cardBlv)
            val match: TextView = v.findViewById(R.id.cardMatch)
            val viewers: TextView = v.findViewById(R.id.cardViewers)
            val badge: TextView = v.findViewById(R.id.roomBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card_horizontal, parent, false)
            return CardVH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: CardVH, pos: Int) {
            val g = items[pos]
            applyEdgeFocusGuard(h.itemView, pos, items.size)

            // Coil với size cố định để giảm RAM: thumb 280x158, avatar 30x30
            h.thumb.load(g.top.cover.ifBlank { g.top.avatar }) {
                size(280, 158)
                scale(Scale.FILL)
                transformations(RoundedCornersTransformation(12f))
                placeholder(R.drawable.thumb_round_bg)
                error(R.drawable.hero_fallback)
                memoryCachePolicy(CachePolicy.ENABLED)
                diskCachePolicy(CachePolicy.ENABLED)
            }
            h.avatar.load(g.top.avatar) {
                size(60, 60)
                transformations(CircleCropTransformation())
                placeholder(R.drawable.logo_placeholder)
                error(R.drawable.logo_placeholder)
            }
            h.blv.text = g.top.blvName + if (g.count > 1) " +${g.count - 1}" else ""
            h.match.text = g.matchTitle
            h.viewers.text = "👁 ${SocoliveRepository.fmtViewers(g.totalViewers)}"
            if (g.count > 1) {
                h.badge.text = "${g.count} phòng"
                h.badge.visibility = View.VISIBLE
            } else h.badge.visibility = View.GONE

            h.itemView.setOnClickListener { onClick(g) }
            h.itemView.setOnLongClickListener { onLong(g); true }
        }

        fun submit(newItems: List<LiveMatchGroup>) {
            val old = items
            items = newItems
            if (old.map { it.matchTitle } != newItems.map { it.matchTitle }) {
                DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = old.size
                    override fun getNewListSize() = newItems.size
                    override fun areItemsTheSame(o: Int, n: Int) = old[o].matchTitle == newItems[n].matchTitle && old[o].league == newItems[n].league
                    override fun areContentsTheSame(o: Int, n: Int) = old[o].totalViewers == newItems[n].totalViewers && old[o].count == newItems[n].count
                }, false).dispatchUpdatesTo(this)
            } else {
                // chỉ update viewers tại chỗ
                notifyItemRangeChanged(0, itemCount, "payload")
            }
        }

        fun updatePayload(newItems: List<LiveMatchGroup>) {
            if (newItems.size != items.size) {
                submit(newItems)
                return
            }
            items = newItems
            // update lightweight
            notifyItemRangeChanged(0, itemCount, "payload")
        }

        override fun onBindViewHolder(h: CardVH, pos: Int, payloads: List<Any>) {
            if (payloads.isNotEmpty()) {
                val g = items[pos]
                h.viewers.text = "👁 ${SocoliveRepository.fmtViewers(g.totalViewers)}"
                h.blv.text = g.top.blvName + if (g.count > 1) " +${g.count - 1}" else ""
                if (g.count > 1) {
                    h.badge.text = "${g.count} phòng"
                    h.badge.visibility = View.VISIBLE
                } else h.badge.visibility = View.GONE
                return
            }
            onBindViewHolder(h, pos)
        }
    }
}
