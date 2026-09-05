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
import coil.transform.CircleCropTransformation

/** Lịch trình v5: tối ưu RAM, Coil size nhỏ, focus stable */
class ScheduleAdapter(
    private val onAnchorClick: (AnchorInfo, ScheduleMatch) -> Unit,
    private val onMatchClick: (ScheduleMatch) -> Unit = {}
) : ListAdapter<Any, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_DAY = 0
        private const val TYPE_MATCH = 1
        private const val MAX_ANCHORS = 4
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
        val count: TextView = v.findViewById(R.id.dayCount)
    }

    inner class MatchVH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.matchTime)
        val hostIcon: ImageView = v.findViewById(R.id.hostIcon)
        val guestIcon: ImageView = v.findViewById(R.id.guestIcon)
        val name: TextView = v.findViewById(R.id.matchName)
        val crest: ImageView = v.findViewById(R.id.leagueCrest)
        val league: TextView = v.findViewById(R.id.matchLeague)
        val badge: TextView = v.findViewById(R.id.statusBadge)
        val anchorRow: LinearLayout = v.findViewById(R.id.anchorRow)
    }

    override fun getItemViewType(pos: Int): Int = if (getItem(pos) is String) TYPE_DAY else TYPE_MATCH

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_DAY)
            DayVH(inf.inflate(R.layout.item_day_header, parent, false))
        else
            MatchVH(inf.inflate(R.layout.item_schedule_match, parent, false))
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        when (val item = getItem(pos)) {
            is String -> {
                val vh = h as DayVH
                vh.tv.text = item
                var n = 0
                for (j in pos + 1 until itemCount) { if (getItem(j) !is ScheduleMatch) break; n++ }
                vh.count.text = if (n > 0) "$n trận" else ""
            }
            is ScheduleMatch -> {
                val vh = h as MatchVH
                vh.itemView.setBackgroundResource(if (pos % 2 == 0) R.drawable.card_a_focus else R.drawable.card_b_focus)
                vh.itemView.setOnClickListener { onMatchClick(item) }
                vh.name.text = "${item.host} vs ${item.guest}"
                vh.league.text = item.league
                vh.time.text = SocoliveRepository.formatTime(item.matchTimeMs)
                if (item.isLive) {
                    vh.badge.text = "● LIVE"
                    vh.badge.setTextColor(0xFFFFFFFF.toInt())
                    vh.badge.setBackgroundResource(R.drawable.badge_live)
                } else {
                    vh.badge.text = SocoliveRepository.formatTime(item.matchTimeMs)
                    vh.badge.setTextColor(0xFF9AA4C0.toInt())
                    vh.badge.setBackgroundResource(R.drawable.badge_grey)
                }
                vh.badge.visibility = View.VISIBLE
                vh.hostIcon.load(item.hostIcon) {
                    size(64, 64)
                    error(R.drawable.logo_placeholder); placeholder(R.drawable.logo_placeholder)
                }
                vh.guestIcon.load(item.guestIcon) {
                    size(64, 64)
                    error(R.drawable.logo_placeholder); placeholder(R.drawable.logo_placeholder)
                }
                vh.crest.load(item.leagueCrest) {
                    size(48, 48)
                    error(R.drawable.logo_placeholder); placeholder(R.drawable.logo_placeholder)
                }

                vh.anchorRow.removeAllViews()
                val inf = LayoutInflater.from(vh.anchorRow.context)
                item.anchors.take(MAX_ANCHORS).forEach { a ->
                    val av = inf.inflate(R.layout.item_anchor_avatar, vh.anchorRow, false)
                    val img = av.findViewById<ImageView>(R.id.anchorAvatar)
                    img.contentDescription = a.nickName
                    img.load(a.icon) {
                        size(64, 64)
                        transformations(CircleCropTransformation())
                        error(R.drawable.logo_placeholder)
                        placeholder(R.drawable.logo_placeholder)
                    }
                    val live = a.roomNum.isNotBlank()
                    img.alpha = if (live) 1f else 0.4f
                    av.setOnClickListener { if (live) onAnchorClick(a, item) }
                    av.setOnLongClickListener {
                        android.widget.Toast.makeText(av.context, a.nickName, android.widget.Toast.LENGTH_SHORT).show(); true
                    }
                    vh.anchorRow.addView(av)
                }
                val extra = item.anchors.size - MAX_ANCHORS
                if (extra > 0) {
                    val av = inf.inflate(R.layout.item_anchor_plus, vh.anchorRow, false)
                    val txt = av.findViewById<TextView>(R.id.anchorPlus)
                    txt.text = "+$extra"
                    vh.anchorRow.addView(av)
                }
            }
        }
    }
}
