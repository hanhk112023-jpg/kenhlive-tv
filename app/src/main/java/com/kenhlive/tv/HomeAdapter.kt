package com.kenhlive.tv

import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.launch

/**
 * Trang chủ Netflix-style:
 *  Row 0: HERO banner tự chạy (top 5 phòng theo viewers)
 *  Row 1: "🔴 Đang live — N phòng" (card dọc cuộn ngang)
 *  Row 2+: "Giải XXX" — các phòng theo giải, cuộn ngang
 */
class HomeAdapter(
    private val rooms: List<LiveRoom>,
    private val lifecycleOwner: LifecycleOwner,
    private val onPlay: (LiveRoom) -> Unit,
    private val onLongClick: (LiveRoom) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HERO = 0
        private const val TYPE_ROW = 1
    }

    // gom theo giải, giữ thứ tự viewers
    private val rows: List<Pair<String, List<LiveRoom>>> = run {
        val grouped = rooms.groupBy { it.league }
        val sorted = grouped.entries.sortedByDescending { e -> e.value.maxOf { it.viewers } }
        sorted.map { it.key to it.value }
    }

    inner class HeroVH(v: View) : RecyclerView.ViewHolder(v) {
        val pager: ViewPager2 = v.findViewById(R.id.heroPager)
    }

    inner class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val scroll: HorizontalScrollView = v.findViewById(R.id.rowScroll)
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
            val hero = HeroAdapter(rooms.take(5)) { r -> onPlay(r) }
            h.pager.adapter = hero
            hero.attach(h.pager)
            return
        }
        val (league, list) = rows[pos - 1]
        val vh = h as RowVH
        vh.title.text = "$league  ·  ${list.size} phòng"
        vh.container.removeAllViews()
        val inf = LayoutInflater.from(vh.container.context)
        list.forEach { r ->
            val card = inf.inflate(R.layout.item_card_horizontal, vh.container, false)
            val avatar = card.findViewById<ImageView>(R.id.cardAvatar)
            val blv = card.findViewById<TextView>(R.id.cardBlv)
            val match = card.findViewById<TextView>(R.id.cardMatch)
            val viewers = card.findViewById<TextView>(R.id.cardViewers)
            blv.text = r.blvName
            match.text = r.matchTitle
            viewers.text = "👁 ${SocoliveRepository.fmtViewers(r.viewers)}"
            avatar.load(r.avatar) {
                crossfade(100)
                transformations(CircleCropTransformation())
                placeholder(R.drawable.logo_placeholder)
                error(R.drawable.logo_placeholder)
            }
            card.setOnClickListener { onPlay(r) }
            card.setOnLongClickListener { onLongClick(r); true }
            vh.container.addView(card)
        }
    }
}
