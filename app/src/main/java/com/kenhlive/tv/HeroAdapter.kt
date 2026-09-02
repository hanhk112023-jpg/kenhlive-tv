package com.kenhlive.tv

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load

/** Hero banner Netflix-style, mỗi slide = 1 TRẬN (gộp N phòng). */
class HeroAdapter(
    private val groups: List<LiveMatchGroup>,
    private val onClick: (LiveMatchGroup) -> Unit
) : RecyclerView.Adapter<HeroAdapter.HV>() {

    private val handler = Handler(Looper.getMainLooper())
    private var pager: ViewPager2? = null
    private val auto = object : Runnable {
        override fun run() {
            pager?.let { if (it.adapter != null && groups.isNotEmpty()) {
                it.currentItem = (it.currentItem + 1) % groups.size } }
            handler.postDelayed(this, 5000)
        }
    }

    fun attach(p: ViewPager2) { pager = p; handler.removeCallbacks(auto); handler.postDelayed(auto, 5000) }
    fun detach() { handler.removeCallbacks(auto); pager = null }

    inner class HV(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.heroCover)
        val title: TextView = v.findViewById(R.id.heroTitle)
        val league: TextView = v.findViewById(R.id.heroLeague)
        val blv: TextView = v.findViewById(R.id.heroBlv)
        val tagline: TextView = v.findViewById(R.id.heroTagline)
        val viewers: TextView = v.findViewById(R.id.heroViewers)
        val play: View = v.findViewById(R.id.heroPlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HV {
        return HV(LayoutInflater.from(parent.context).inflate(R.layout.item_hero, parent, false))
    }

    override fun getItemCount() = groups.size.coerceAtMost(8)

    override fun onBindViewHolder(h: HV, pos: Int) {
        val g = groups[pos]
        val top = g.top
        h.blv.text = top.blvName
        h.tagline.text = if (g.count > 1) "${g.count} phòng · gộp từ các BLV" else "${top.blvName} · CUỒNG NHIỆT TRÊN LIVE"
        h.title.text = g.matchTitle
        h.league.text = g.league
        h.viewers.text = "👁 ${SocoliveRepository.fmtViewers(g.totalViewers)}"
        h.cover.load(top.cover.ifBlank { top.avatar }) {
            crossfade(200)
            placeholder(R.drawable.hero_fallback)
            error(R.drawable.hero_fallback)
        }
        h.itemView.setOnClickListener { onClick(g) }
        h.play.setOnClickListener { onClick(g) }
    }

    fun groupAt(pos: Int): LiveMatchGroup = groups[pos % groups.size]
}
