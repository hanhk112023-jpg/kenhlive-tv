package com.kenhlive.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.kenhlive.tv.ui.FocusKit

/**
 * Màn TRỰC TIẾP dạng Netflix:
 *  - item 0: HERO carousel (top trận)
 *  - item 1+: row ngang theo giải, mỗi card = 1 TRẬN (gộp N phòng BLV)
 * Row trong là RecyclerView (recycle thật, khác HorizontalScrollView bản cũ)
 * + DiffUtil → refresh 3 phút không phá focus/scroll.
 */
class HomeRowsAdapter(
    private val onGroupClick: (LiveMatchGroup) -> Unit,
    private val onLongClickGroup: (LiveMatchGroup) -> Unit
) : ListAdapter<Any, RecyclerView.ViewHolder>(DIFF), FocusKit.RowHost {

    companion object {
        private const val TYPE_HERO = 0
        private const val TYPE_ROW = 1

        data class RowItem(val league: String, val groups: List<LiveMatchGroup>)

        private val DIFF = object : DiffUtil.ItemCallback<Any>() {
            override fun areItemsTheSame(a: Any, b: Any): Boolean = when {
                a is List<*> && b is List<*> -> true                      // hero slot
                a is RowItem && b is RowItem -> a.league == b.league
                else -> false
            }
            override fun areContentsTheSame(a: Any, b: Any): Boolean = when {
                a is List<*> && b is List<*> -> titles(a) == titles(b)
                a is RowItem && b is RowItem ->
                    a.groups.map { it.matchTitle to it.totalViewers to it.count } ==
                        b.groups.map { it.matchTitle to it.totalViewers to it.count }
                else -> false
            }
            private fun titles(x: Any) =
                (x as? List<*>)?.filterIsInstance<LiveMatchGroup>()?.map { it.matchTitle }
        }

        fun buildItems(groups: List<LiveMatchGroup>): List<Any> {
            val items = mutableListOf<Any>()
            items.add(groups.take(8))
            groups.groupBy { it.league }.entries
                .sortedByDescending { e -> e.value.sumOf { g -> g.totalViewers } }
                .forEach { items.add(RowItem(it.key, it.value)) }
            return items
        }
    }

    override var outerRecyclerView: RecyclerView? = null
    override val headerPositions: Int get() = 1

    private val pool = RecyclerView.RecycledViewPool()

    override fun onAttachedToRecyclerView(rv: RecyclerView) { outerRecyclerView = rv }
    override fun onDetachedFromRecyclerView(rv: RecyclerView) { outerRecyclerView = null }

    /** UP từ row đầu → nút XEM NGAY của hero trang hiện tại. */
    override fun focusHero(): Boolean {
        val rv = outerRecyclerView ?: return false
        val vh = rv.findViewHolderForAdapterPosition(0) as? HeroVH ?: return false
        val inner = vh.pager.getChildAt(0) as? RecyclerView ?: return false
        val page = inner.findViewHolderForAdapterPosition(vh.pager.currentItem)?.itemView ?: return false
        return page.findViewById<View>(R.id.heroPlay)?.requestFocus() ?: false
    }

    fun restoreFocus() = FocusKit.restore(this)

    inner class HeroVH(v: View) : RecyclerView.ViewHolder(v) {
        val pager: ViewPager2 = v.findViewById(R.id.heroPager)
        val dots: LinearLayout = v.findViewById(R.id.heroDots)
    }

    inner class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val count: TextView = v.findViewById(R.id.rowCount)
        val list: RecyclerView = v.findViewById(R.id.rowList)
        val cards = MatchCardsAdapter(onGroupClick, onLongClickGroup)

        init {
            list.layoutManager = LinearLayoutManager(
                list.context, LinearLayoutManager.HORIZONTAL, false
            )
            list.setRecycledViewPool(pool)
            list.adapter = cards
            list.clipChildren = false
            list.clipToPadding = false
            (list.parent as? ViewGroup)?.let { it.clipChildren = false }
            cards.keyHandler = { rowPos, idx, size ->
                FocusKit.rowCardKey(this@HomeRowsAdapter, rowPos, idx, size)
            }
        }
    }

    override fun getItemViewType(pos: Int) = if (pos == 0) TYPE_HERO else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HERO) HeroVH(inf.inflate(R.layout.item_hero_pager, parent, false))
        else RowVH(inf.inflate(R.layout.item_row, parent, false))
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        when (h) {
            is HeroVH -> {
                @Suppress("UNCHECKED_CAST")
                val groups = getItem(pos) as List<LiveMatchGroup>
                (h.pager.adapter as? HeroPagerAdapter)?.detach()
                val adapter = HeroPagerAdapter(groups, onGroupClick)
                h.pager.adapter = adapter
                h.pager.isUserInputEnabled = !DeviceMode.isTv   // TV: không swipe tay
                adapter.attach(h.pager, h.dots)
            }
            is RowVH -> {
                val row = getItem(pos) as RowItem
                h.title.text = row.league
                h.count.text = h.itemView.context.getString(R.string.sched_matches_count, row.groups.size)
                h.cards.rowPos = pos
                h.cards.submitList(row.groups)
            }
        }
    }

    override fun onViewRecycled(h: RecyclerView.ViewHolder) {
        if (h is HeroVH) (h.pager.adapter as? HeroPagerAdapter)?.detach()
    }
}
