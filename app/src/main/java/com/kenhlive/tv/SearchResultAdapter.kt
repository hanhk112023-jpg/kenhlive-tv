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

/** Danh sách kết quả tìm kiếm: mỗi dòng = 1 trận (avatar BLV top, meta viewers/phòng, badge giải).
 *  Dùng ListAdapter + DiffUtil: khi query đổi, dòng nào giữ được thì GIỮ nguyên view + focus.
 *  notifyDataSetChanged() cũ destroy view đang focus giữa lúc user bấm D-pad = "nhảy lung tung". */
class SearchResultAdapter(
    private val onClick: (LiveMatchGroup) -> Unit
) : ListAdapter<LiveMatchGroup, SearchResultAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<LiveMatchGroup>() {
            override fun areItemsTheSame(a: LiveMatchGroup, b: LiveMatchGroup) =
                a.league == b.league && a.matchTitle == b.matchTitle
            override fun areContentsTheSame(a: LiveMatchGroup, b: LiveMatchGroup) =
                a.matchTitle == b.matchTitle && a.count == b.count &&
                    a.totalViewers == b.totalViewers && a.top.blvName == b.top.blvName
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

    override fun getItemCount() = currentList.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val g = currentList[pos]
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
