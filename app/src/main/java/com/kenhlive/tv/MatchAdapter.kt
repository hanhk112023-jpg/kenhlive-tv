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

class MatchAdapter(
    private val onClick: (Match) -> Unit
) : ListAdapter<Any, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_DAY = 0
        private const val TYPE_MATCH = 1
        private const val TYPE_ROOM = 2

        val DIFF = object : DiffUtil.ItemCallback<Any>() {
            override fun areItemsTheSame(a: Any, b: Any): Boolean = when {
                a is String && b is String -> a == b
                a is Match && b is Match -> a.title == b.title && a.matchTime == b.matchTime
                else -> false
            }
            override fun areContentsTheSame(a: Any, b: Any): Boolean = a == b
        }
    }

    inner class DayVH(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(R.id.dayLabel)
    }

    inner class MatchVH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.matchTime)
        val name: TextView = v.findViewById(R.id.matchName)
        val league: TextView = v.findViewById(R.id.matchLeague)
        val host: ImageView = v.findViewById(R.id.hostIcon)
        val guest: ImageView = v.findViewById(R.id.guestIcon)
        val live: TextView = v.findViewById(R.id.liveBadge)
    }

    inner class RoomVH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.channelName)
        val group: TextView = v.findViewById(R.id.channelGroup)
        val logo: ImageView = v.findViewById(R.id.channelLogo)
    }

    override fun getItemViewType(pos: Int): Int = when (getItem(pos)) {
        is String -> TYPE_DAY
        is Match -> TYPE_MATCH
        else -> TYPE_ROOM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_DAY -> DayVH(inf.inflate(R.layout.item_day_header, parent, false))
            TYPE_MATCH -> MatchVH(inf.inflate(R.layout.item_match, parent, false))
            else -> RoomVH(inf.inflate(R.layout.item_channel, parent, false))
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        when (val item = getItem(pos)) {
            is String -> (h as DayVH).tv.text = item
            is Match -> {
                val vh = h as MatchVH
                vh.name.text = item.title
                vh.league.text = item.league
                vh.time.text = MatchRepository.formatTime(item.matchTime)
                val isLive = item.hasRoom && item.matchTime > 0 &&
                    System.currentTimeMillis() / 1000 >= item.matchTime - 900
                vh.live.visibility = if (isLive) View.VISIBLE else View.GONE
                if (item.hostIcon.isNotBlank()) vh.host.load(item.hostIcon) {
                    crossfade(80); error(R.drawable.logo_placeholder)
                } else vh.host.setImageResource(R.drawable.logo_placeholder)
                if (item.guestIcon.isNotBlank()) vh.guest.load(item.guestIcon) {
                    crossfade(80); error(R.drawable.logo_placeholder)
                } else vh.guest.setImageResource(R.drawable.logo_placeholder)
                // trận có phòng + đã tới giờ → bấm xem trực tiếp
                vh.itemView.setOnClickListener {
                    if (item.hasRoom) onClick(item)
                }
                vh.itemView.isEnabled = item.hasRoom
                vh.itemView.alpha = if (item.hasRoom) 1f else 0.55f
            }
        }
    }
}
