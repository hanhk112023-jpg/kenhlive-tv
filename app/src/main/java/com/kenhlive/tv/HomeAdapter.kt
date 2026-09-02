package com.kenhlive.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
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

    /** Cập nhật dữ liệu mới (auto-refresh) — rebuild rows + notify. */
    fun submit(newGroups: List<LiveMatchGroup>) {
        groups = newGroups
        rows = buildRows(groups)
        notifyDataSetChanged()
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

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        if (h is HeroVH) {
            val hero = HeroAdapter(groups.take(5)) { g -> onGroupClick(g) }
            h.pager.adapter = hero
            hero.attach(h.pager)
            return
        }
        val (league, list) = rows[pos - 1]
        val vh = h as RowVH
        vh.title.text = league
        vh.container.removeAllViews()
        val inf = LayoutInflater.from(vh.container.context)
        list.forEach { g ->
            val card = inf.inflate(R.layout.item_card_horizontal, vh.container, false)
            val thumb = card.findViewById<ImageView>(R.id.cardThumb)
            val avatar = card.findViewById<ImageView>(R.id.cardAvatar)
            val blv = card.findViewById<TextView>(R.id.cardBlv)
            val match = card.findViewById<TextView>(R.id.cardMatch)
            val viewers = card.findViewById<TextView>(R.id.cardViewers)
            val badge = card.findViewById<TextView>(R.id.roomBadge)
            // thumbnail = ảnh cover trận (BLV), fallback avatar
            thumb.load(g.top.cover.ifBlank { g.top.avatar }) {
                crossfade(150)
                transformations(RoundedCornersTransformation(14f))
                placeholder(R.drawable.hero_fallback)
                error(R.drawable.hero_fallback)
            }
            avatar.load(g.top.avatar) {
                crossfade(80)
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
    }
}
