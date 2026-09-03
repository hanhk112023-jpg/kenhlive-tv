package com.kenhlive.tv

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation

/** Danh sách kết quả tìm kiếm: mỗi dòng = 1 trận (avatar BLV top, meta viewers/phòng, badge giải). */
class SearchResultAdapter(
    private val onClick: (LiveMatchGroup) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.VH>() {

    private var items: List<LiveMatchGroup> = emptyList()

    fun submit(list: List<LiveMatchGroup>) { items = list; notifyDataSetChanged() }

    class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.srAvatar)
        val match: TextView = v.findViewById(R.id.srMatch)
        val meta: TextView = v.findViewById(R.id.srMeta)
        val league: TextView = v.findViewById(R.id.srLeague)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val g = items[pos]
        h.match.text = g.matchTitle
        h.meta.text = "${g.top.blvName}${if (g.count > 1) " +${g.count - 1} BLV" else ""} · 👁 ${SocoliveRepository.fmtViewers(g.totalViewers)} · ${g.count} phòng"
        h.league.text = g.league
        h.avatar.load(g.top.avatar) {
            crossfade(80); transformations(CircleCropTransformation())
            placeholder(R.drawable.logo_placeholder); error(R.drawable.logo_placeholder)
        }
        h.itemView.setOnClickListener { onClick(g) }
    }
}
