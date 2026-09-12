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

/**
 * Hero carousel (top trận hot). Tự lật 5s (9s máy yếu) nhưng DỪNG khi user đang focus/touch
 * để không giật focus giữa chừng trên TV. Dots indicator đồng bộ trang.
 */
class HeroPagerAdapter(
    private val groups: List<LiveMatchGroup>,
    private val onClick: (LiveMatchGroup) -> Unit
) : RecyclerView.Adapter<HeroPagerAdapter.HV>() {

    private val handler = Handler(Looper.getMainLooper())
    private var pager: ViewPager2? = null
    private var dots: LinearLayout? = null

    private val auto = object : Runnable {
        override fun run() {
            val p = pager
            if (p != null && groups.size > 1 && !p.hasFocus()) {
                p.currentItem = (p.currentItem + 1) % groups.size
            }
            handler.postDelayed(this, if (DeviceMode.lowRam) 9000 else 5000)
        }
    }

    private val pageCb = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(pos: Int) = paintDots(pos)
    }

    fun attach(p: ViewPager2, dotsBox: LinearLayout?) {
        pager = p
        dots = dotsBox
        buildDots()
        p.unregisterOnPageChangeCallback(pageCb)
        p.registerOnPageChangeCallback(pageCb)
        handler.removeCallbacks(auto)
        handler.postDelayed(auto, if (DeviceMode.lowRam) 9000 else 5000)
    }

    fun detach() {
        handler.removeCallbacks(auto)
        pager?.unregisterOnPageChangeCallback(pageCb)
        pager = null
        dots = null
    }

    private fun buildDots() {
        val box = dots ?: return
        box.removeAllViews()
        val ctx = box.context
        val d = (6 * ctx.resources.displayMetrics.density).toInt()
        val m = (3 * ctx.resources.displayMetrics.density).toInt()
        repeat(groups.size.coerceAtMost(8)) {
            val v = View(ctx)
            val lp = LinearLayout.LayoutParams(d, d)
            lp.setMargins(m, 0, m, 0)
            v.layoutParams = lp
            v.setBackgroundResource(R.drawable.bg_circle)
            box.addView(v)
        }
        paintDots(pager?.currentItem ?: 0)
    }

    private fun paintDots(pos: Int) {
        val box = dots ?: return
        for (i in 0 until box.childCount) {
            val on = i == pos
            box.getChildAt(i).setBackgroundResource(if (on) R.drawable.bg_badge_live else R.drawable.bg_circle)
            box.getChildAt(i).alpha = if (on) 1f else 0.45f
        }
    }

    inner class HV(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.heroCover)
        val title: TextView = v.findViewById(R.id.heroTitle)
        val league: TextView = v.findViewById(R.id.heroLeague)
        val blv: TextView = v.findViewById(R.id.heroBlv)
        val viewers: TextView = v.findViewById(R.id.heroViewers)
        val play: View = v.findViewById(R.id.heroPlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HV =
        HV(LayoutInflater.from(parent.context).inflate(R.layout.item_hero, parent, false))

    override fun getItemCount(): Int = groups.size.coerceAtMost(8)

    override fun onBindViewHolder(h: HV, pos: Int) {
        val g = groups[pos]
        val top = g.top
        h.title.text = g.matchTitle
        h.league.text = g.league
        h.blv.text = top.blvName
        h.viewers.text = SocoliveRepository.fmtViewers(g.totalViewers)
        h.cover.load(top.cover.ifBlank { top.avatar }) {
            crossfade(if (DeviceMode.lowRam) 0 else 200)
            placeholder(R.drawable.hero_fallback)
            error(R.drawable.hero_fallback)
        }
        h.itemView.setOnClickListener { onClick(g) }
        h.play.setOnClickListener { onClick(g) }
    }
}
