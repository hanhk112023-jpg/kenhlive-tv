package com.kenhlive.tv

import android.content.Intent
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

/** Hero banner Netflix-style: full-width cover + overlay info, tự chạy 5s/slide. */
class HeroAdapter(
    private val rooms: List<LiveRoom>,
    private val onClick: (LiveRoom) -> Unit
) : RecyclerView.Adapter<HeroAdapter.HV>() {

    private val handler = Handler(Looper.getMainLooper())
    private var pager: ViewPager2? = null
    private val auto = object : Runnable {
        override fun run() {
            pager?.let {
                if (it.adapter != null && rooms.isNotEmpty()) {
                    it.currentItem = (it.currentItem + 1) % rooms.size
                }
            }
            handler.postDelayed(this, 5000)
        }
    }

    fun attach(p: ViewPager2) {
        pager = p
        handler.removeCallbacks(auto)
        handler.postDelayed(auto, 5000)
    }

    fun detach() { handler.removeCallbacks(auto); pager = null }

    inner class HV(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.heroCover)
        val title: TextView = v.findViewById(R.id.heroTitle)
        val league: TextView = v.findViewById(R.id.heroLeague)
        val blv: TextView = v.findViewById(R.id.heroBlv)
        val viewers: TextView = v.findViewById(R.id.heroViewers)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HV {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_hero, parent, false)
        return HV(v)
    }

    override fun getItemCount() = rooms.size.coerceAtMost(8)

    override fun onBindViewHolder(h: HV, pos: Int) {
        val r = rooms[pos]
        h.title.text = r.matchTitle
        h.league.text = r.league
        h.blv.text = r.blvName
        h.viewers.text = "👁 ${SocoliveRepository.fmtViewers(r.viewers)} đang xem"
        h.cover.load(r.cover.ifBlank { r.avatar }) {
            crossfade(200)
            placeholder(R.drawable.logo_placeholder)
            error(R.drawable.logo_placeholder)
        }
        h.itemView.setOnClickListener { onClick(r) }
    }

    fun roomAt(pos: Int): LiveRoom = rooms[pos % rooms.size]
}
