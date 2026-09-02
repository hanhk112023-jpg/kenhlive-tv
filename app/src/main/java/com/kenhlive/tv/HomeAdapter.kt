package com.kenhlive.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Trang chủ Netflix-style theo TRẬN (gộp phòng):
 *  Row 0: HERO top 5 trận theo tổng viewers
 *  Row 1+: "Giải XXX" — card mỗi TRẬN (1 card = N phòng), click mở dialog chọn phòng.
 */
class HomeAdapter(
    private val groups: List<LiveMatchGroup>,
    private val lifecycleOwner: LifecycleOwner,
    private val onGroupClick: (LiveMatchGroup) -> Unit,
    private val onLongClickGroup: (LiveMatchGroup) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HERO = 0
        private const val TYPE_ROW = 1
    }

    // group theo giải, giữ thứ tự tổng viewers
    private val rows: List<Pair<String, List<LiveMatchGroup>>> = run {
        groups.groupBy { it.league }
            .entries.sortedByDescending { it.value.sumOf { g -> g.totalViewers } }
            .map { it.key to it.value }
    }

    inner class HeroVH(v: View) : RecyclerView.ViewHolder(v) {
        val pager: ViewPager2 = v.findViewById(R.id.heroPager)
    }

    inner class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val scroll: HorizontalScrollView = v.findViewById(R.id.rowScroll)
        val container: LinearLayout = v.findViewById(R.id.rowContainer)
        val progress: LinearProgressIndicator = v.findViewById(R.id.rowProgress)
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

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        if (h is HeroVH) {
            val hero = HeroAdapter(groups.take(5)) { g -> onGroupClick(g) }
            h.pager.adapter = hero
            hero.attach(h.pager)
            return
        }
        val (league, list) = rows[pos - 1]
        val vh = h as RowVH
        vh.title.text = "$league · ${list.size} trận"
        vh.container.removeAllViews()
        val inf = LayoutInflater.from(vh.container.context)
        list.forEach { g ->
            val card = inf.inflate(R.layout.item_card_horizontal, vh.container, false)
            val avatar = card.findViewById<ImageView>(R.id.cardAvatar)
            val blv = card.findViewById<TextView>(R.id.cardBlv)
            val match = card.findViewById<TextView>(R.id.cardMatch)
            val viewers = card.findViewById<TextView>(R.id.cardViewers)
            val blvRow = card.findViewById<LinearLayout>(R.id.blvRow)
            blv.text = g.matchTitle
            match.text = g.league
            viewers.text = SocoliveRepository.fmtViewers(g.totalViewers)
            avatar.load(g.top.avatar) {
                crossfade(100)
                transformations(CircleCropTransformation())
                placeholder(R.drawable.logo_placeholder)
                error(R.drawable.logo_placeholder)
            }
            // hàng avatar các BLV trong trận (tối đa 4) + badge số phòng
            blvRow.removeAllViews()
            g.rooms.take(4).forEach { r ->
                val mini = inf.inflate(R.layout.item_mini_avatar, blvRow, false)
                mini.findViewById<ImageView>(R.id.miniAvatar).load(r.avatar) {
                    crossfade(60); transformations(CircleCropTransformation())
                    placeholder(R.drawable.logo_placeholder); error(R.drawable.logo_placeholder)
                }
                blvRow.addView(mini)
            }
            if (g.count > 1) {
                card.findViewById<TextView>(R.id.roomBadge).text = "${g.count} phòng"
                card.findViewById<TextView>(R.id.roomBadge).visibility = View.VISIBLE
            } else {
                card.findViewById<TextView>(R.id.roomBadge).visibility = View.GONE
            }
            card.setOnClickListener { onGroupClick(g) }
            card.setOnLongClickListener { onLongClickGroup(g); true }
            vh.container.addView(card)
        }
        vh.scroll.post {
            val wide = (vh.scroll.getChildAt(0)?.width ?: vh.scroll.width).coerceAtLeast(1)
            val maxScroll = (wide - vh.scroll.width).coerceAtLeast(0)
            vh.progress.visibility = if (maxScroll > 0) View.VISIBLE else View.GONE
            vh.scroll.setOnScrollChangeListener { _, _, _, _, _ ->
                val p = if (maxScroll > 0) (vh.scroll.scrollX * 1000 / maxScroll).coerceIn(0, 1000) else 0
                vh.progress.setProgressCompat(p, true)
            }
        }
    }
}
