package com.kenhlive.tv

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import coil.request.CachePolicy
import coil.size.Scale

/** Hero banner Netflix-style v5, mỗi slide = 1 TRẬN (gộp N phòng). Tối ưu RAM + ổn định focus. */
class HeroAdapter(
    private val groups: List<LiveMatchGroup>,
    private val onClick: (LiveMatchGroup) -> Unit
) : RecyclerView.Adapter<HeroAdapter.HV>() {

    private val handler = Handler(Looper.getMainLooper())
    private var pager: ViewPager2? = null
    private var dots: LinearLayout? = null
    private var callback: ViewPager2.OnPageChangeCallback? = null

    private val auto = object : Runnable {
        override fun run() {
            pager?.let {
                if (it.adapter != null && groups.isNotEmpty() && !it.isFocused && !hasFocusedChild()) {
                    val next = (it.currentItem + 1) % groups.size
                    it.setCurrentItem(next, true)
                    // update dots
                    dots?.let { d ->
                        for (i in 0 until d.childCount) {
                            d.getChildAt(i).alpha = if (i == next) 1f else 0.35f
                        }
                    }
                }
            }
            handler.postDelayed(this, 6000)
        }
    }
    private fun hasFocusedChild(): Boolean = pager?.hasFocus() == true

    fun attach(p: ViewPager2, dotsContainer: LinearLayout? = null) {
        pager = p
        dots = dotsContainer
        handler.removeCallbacks(auto)
        handler.postDelayed(auto, 6000)

        // page callback để đồng bộ dots
        callback?.let { p.unregisterOnPageChangeCallback(it) }
        val cb = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(pos: Int) {
                dots?.let { d ->
                    for (i in 0 until d.childCount) {
                        d.getChildAt(i).alpha = if (i == pos) 1f else 0.35f
                    }
                }
            }
        }
        callback = cb
        p.registerOnPageChangeCallback(cb)
    }

    fun detach() {
        handler.removeCallbacks(auto)
        callback?.let { pager?.unregisterOnPageChangeCallback(it) }
        callback = null
        pager = null
        dots = null
    }

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
        h.league.text = g.league.uppercase()
        h.viewers.text = "👁 ${SocoliveRepository.fmtViewers(g.totalViewers)} · ${g.count} phòng"

        // Coil với size cố định 960x540 để giảm RAM so với full HD, vẫn nét trên TV
        h.cover.load(top.cover.ifBlank { top.avatar }) {
            size(960, 540)
            scale(Scale.FILL)
            placeholder(R.drawable.hero_fallback)
            error(R.drawable.hero_fallback)
            memoryCachePolicy(CachePolicy.ENABLED)
            diskCachePolicy(CachePolicy.ENABLED)
        }
        h.itemView.setOnClickListener { onClick(g) }
        h.play.setOnClickListener { onClick(g) }
        // focus scale effect
        h.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.02f else 1f).scaleY(if (hasFocus) 1.02f else 1f).setDuration(180).start()
        }
    }

    fun groupAt(pos: Int): LiveMatchGroup = groups[pos % groups.size]
}
