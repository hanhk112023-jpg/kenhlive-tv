package com.kenhlive.tv

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation

/** Grid card BLV đang live: avatar tròn viền gradient + pills. Long-click = chọn multiview. */
class LiveRoomAdapter(
    private val onClick: (LiveRoom) -> Unit,
    private val onLongClick: (LiveRoom) -> Unit = {}
) : ListAdapter<LiveRoom, LiveRoomAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<LiveRoom>() {
            override fun areItemsTheSame(a: LiveRoom, b: LiveRoom) = a.roomNum == b.roomNum
            override fun areContentsTheSame(a: LiveRoom, b: LiveRoom) = a == b
        }
    }

    var highlightRoomNums: Set<String> = emptySet()

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.avatar)
        val blv: TextView = v.findViewById(R.id.blvName)
        val viewers: TextView = v.findViewById(R.id.viewers)
        val match: TextView = v.findViewById(R.id.matchTitle)
        val league: TextView = v.findViewById(R.id.league)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_live_room, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = getItem(pos)
        h.blv.text = r.blvName
        h.viewers.text = "👁 ${SocoliveRepository.fmtViewers(r.viewers)} đang xem"
        h.match.text = r.matchTitle
        h.league.text = r.league
        h.avatar.load(r.avatar) {
            crossfade(120)
            transformations(CircleCropTransformation())
            placeholder(R.drawable.logo_placeholder)
            error(R.drawable.logo_placeholder)
        }
        val picked = r.roomNum in highlightRoomNums
        h.blv.setTextColor(if (picked) 0xFFFFD166.toInt() else Color.WHITE)
        h.itemView.setOnClickListener { onClick(r) }
        h.itemView.setOnLongClickListener { onLongClick(r); true }
    }
}
