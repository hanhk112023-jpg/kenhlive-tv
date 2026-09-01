package com.kenhlive.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** Grid card BLV đang live: avatar tròn + tên + viewers + trận + giải. */
class LiveRoomAdapter(
    private val onClick: (LiveRoom) -> Unit
) : ListAdapter<LiveRoom, LiveRoomAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<LiveRoom>() {
            override fun areItemsTheSame(a: LiveRoom, b: LiveRoom) = a.roomNum == b.roomNum
            override fun areContentsTheSame(a: LiveRoom, b: LiveRoom) = a == b
        }
    }

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
        h.viewers.text = "👁 ${SocoliveRepository.fmtViewers(r.viewers)}"
        h.match.text = r.matchTitle
        h.league.text = r.league
        h.avatar.load(r.avatar) {
            crossfade(80)
            placeholder(R.drawable.logo_placeholder)
            error(R.drawable.logo_placeholder)
        }
        h.itemView.setOnClickListener { onClick(r) }
    }
}
