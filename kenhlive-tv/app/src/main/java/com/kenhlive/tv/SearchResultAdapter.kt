package com.kenhlive.tv

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation

/** Kết quả tìm kiếm: mỗi dòng = 1 trận. DiffUtil giữ view + focus khi query đổi. */
class SearchResultAdapter(
    private val onClick: (LiveMatchGroup) -> Unit
) : ListAdapter<LiveMatchGroup, SearchResultAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<LiveMatchGroup>() {
            override fun areItemsTheSame(a: LiveMatchGroup, b: LiveMatchGroup) =
                a.league == b.league && a.matchTitle == b.matchTitle
            override fun areContentsTheSame(a: LiveMatchGroup, b: LiveMatchGroup) =
                a.count == b.count && a.totalViewers == b.totalViewers && a.top.blvName == b.top.blvName
        }
    }

    class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.srAvatar)
        val match: TextView = v.findViewById(R.id.srMatch)
        val meta: TextView = v.findViewById(R.id.srMeta)
        val league: TextView = v.findViewById(R.id.srLeague)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val g = currentList[pos]
        val ctx = h.itemView.context
        h.match.text = g.matchTitle
        h.meta.text = "${g.top.blvName}${if (g.count > 1) " ${ctx.getString(R.string.live_blv_more, g.count - 1)}" else ""}" +
            " · ${SocoliveRepository.fmtViewers(g.totalViewers)} · ${ctx.getString(R.string.live_rooms_badge, g.count)}"
        h.league.text = g.league
        h.avatar.load(g.top.avatar) {
            crossfade(if (DeviceMode.lowRam) 0 else 80); transformations(CircleCropTransformation())
            placeholder(R.drawable.logo_placeholder); error(R.drawable.logo_placeholder)
        }
        h.itemView.setOnClickListener { onClick(g) }
        h.itemView.setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.02f else 1f).scaleY(if (has) 1.02f else 1f)
                .setDuration(130).start()
            v.elevation = if (has) 10f else 0f
        }
        h.itemView.contentDescription = g.matchTitle
    }
}
