package com.kenhlive.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** Lịch trình: header ngày + card trận (2 logo + giờ + hàng avatar BLV). */
class ScheduleAdapter(
    private val onAnchorClick: (AnchorInfo, ScheduleMatch) -> Unit
) : ListAdapter<Any, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_DAY = 0
        private const val TYPE_MATCH = 1
        val DIFF = object : DiffUtil.ItemCallback<Any>() {
            override fun areItemsTheSame(a: Any, b: Any): Boolean = when {
                a is String && b is String -> a == b
                a is ScheduleMatch && b is ScheduleMatch -> a.scheduleId == b.scheduleId
                else -> false
            }
            override fun areContentsTheSame(a: Any, b: Any) = a == b
        }
    }

    inner class DayVH(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(R.id.dayLabel)
    }

    inner class MatchVH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.matchTime)
        val hostIcon: ImageView = v.findViewById(R.id.hostIcon)
        val guestIcon: ImageView = v.findViewById(R.id.guestIcon)
        val name: TextView = v.findViewById(R.id.matchName)
        val league: TextView = v.findViewById(R.id.matchLeague)
        val badge: TextView = v.findViewById(R.id.statusBadge)
        val anchorRow: LinearLayout = v.findViewById(R.id.anchorRow)
    }

    override fun getItemViewType(pos: Int): Int =
        if (getItem(pos) is String) TYPE_DAY else TYPE_MATCH

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_DAY)
            DayVH(inf.inflate(R.layout.item_day_header, parent, false))
        else
            MatchVH(inf.inflate(R.layout.item_schedule_match, parent, false))
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        when (val item = getItem(pos)) {
            is String -> (h as DayVH).tv.text = item
            is ScheduleMatch -> {
                val vh = h as MatchVH
                vh.name.text = "${item.host} vs ${item.guest}"
                vh.league.text = item.league
                vh.time.text = SocoliveRepository.formatTime(item.matchTimeMs)
                if (item.isLive) {
                    vh.badge.text = "● Đang trực tiếp"
                    vh.badge.setTextColor(0xFFFF5D5D.toInt())
                    vh.badge.visibility = View.VISIBLE
                } else {
                    vh.badge.text = "Chưa bắt đầu"
                    vh.badge.setTextColor(0xFF6B7490.toInt())
                    vh.badge.visibility = View.VISIBLE
                }
                vh.hostIcon.load(item.hostIcon) { crossfade(80); error(R.drawable.logo_placeholder) }
                vh.guestIcon.load(item.guestIcon) { crossfade(80); error(R.drawable.logo_placeholder) }
                // hàng avatar BLV
                vh.anchorRow.removeAllViews()
                val inf = LayoutInflater.from(vh.anchorRow.context)
                item.anchors.take(6).forEach { a ->
                    val av = inf.inflate(R.layout.item_anchor_chip, vh.anchorRow, false)
                    val img = av.findViewById<ImageView>(R.id.anchorAvatar)
                    val txt = av.findViewById<TextView>(R.id.anchorName)
                    txt.text = a.nickName
                    img.load(a.icon) { crossfade(60); error(R.drawable.logo_placeholder) }
                    av.setOnClickListener { if (a.roomNum.isNotBlank()) onAnchorClick(a, item) }
                    av.alpha = if (a.roomNum.isNotBlank()) 1f else 0.4f
                    vh.anchorRow.addView(av)
                }
            }
        }
    }
}
