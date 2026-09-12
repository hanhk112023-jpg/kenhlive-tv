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

/** Lịch trình: header ngày + card trận. Card focusable chuẩn D-pad (phóng nhẹ + viền). */
class ScheduleAdapter(
    private val onMatchClick: (ScheduleMatch) -> Unit
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
        return if (viewType == TYPE_DAY) DayVH(inf.inflate(R.layout.item_day_header, parent, false))
        else MatchVH(inf.inflate(R.layout.item_schedule_match, parent, false))
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        when (val item = getItem(pos)) {
            is String -> {
                val vh = h as DayVH
                vh.tv.text = item
                var n = 0
                for (j in pos + 1 until itemCount) { if (getItem(j) !is ScheduleMatch) break; n++ }
                vh.count.text = if (n > 0) h.itemView.context.getString(R.string.sched_matches_count, n) else ""
            }
            is ScheduleMatch -> {
                val vh = h as MatchVH
                val ctx = h.itemView.context
                vh.itemView.setOnFocusChangeListener { v, has ->
                    v.animate().scaleX(if (has) 1.015f else 1f).scaleY(if (has) 1.015f else 1f)
                        .setDuration(130).start()
                    v.elevation = if (has) 10f else 0f
                }
                vh.itemView.setOnClickListener { onMatchClick(item) }
                vh.name.text = "${item.host} vs ${item.guest}"
                vh.league.text = item.league
                vh.time.text = SocoliveRepository.formatTime(item.matchTimeMs)
                vh.badge.visibility = View.VISIBLE
                if (item.isLive) {
                    vh.badge.text = ctx.getString(R.string.sched_live).uppercase()
                    vh.badge.setTextColor(0xFFFFFFFF.toInt())
                    vh.badge.setBackgroundResource(R.drawable.bg_badge_live)
                    vh.time.setTextColor(ctx.getColorCompat(R.color.kl_live))
                } else {
                    vh.badge.text = SocoliveRepository.formatTime(item.matchTimeMs)
                    vh.badge.setTextColor(ctx.getColorCompat(R.color.kl_text_3))
                    vh.badge.setBackgroundResource(R.drawable.bg_badge_glass)
                    vh.time.setTextColor(ctx.getColorCompat(R.color.kl_text_1))
                }
                vh.hostIcon.load(item.hostIcon) {
                    crossfade(if (DeviceMode.lowRam) 0 else 80)
                    error(R.drawable.logo_placeholder); placeholder(R.drawable.logo_placeholder)
                }
                vh.guestIcon.load(item.guestIcon) {
                    crossfade(if (DeviceMode.lowRam) 0 else 80)
                    error(R.drawable.logo_placeholder); placeholder(R.drawable.logo_placeholder)
                }
                vh.crest.load(item.leagueCrest) {
                    crossfade(if (DeviceMode.lowRam) 0 else 60)
                    error(R.drawable.logo_placeholder); placeholder(R.drawable.logo_placeholder)
                }
                vh.anchorRow.removeAllViews()
                val inf = LayoutInflater.from(ctx)
                item.anchors.take(MAX_ANCHORS).forEach { a ->
                    val av = inf.inflate(R.layout.item_anchor_avatar, vh.anchorRow, false)
                    val img = av as ImageView
                    img.contentDescription = a.nickName
                    img.load(a.icon) {
                        crossfade(if (DeviceMode.lowRam) 0 else 80)
                        transformations(CircleCropTransformation())
                        error(R.drawable.logo_placeholder)
                        placeholder(R.drawable.logo_placeholder)
                    }
                    img.alpha = if (a.roomNum.isNotBlank()) 1f else 0.4f
                    vh.anchorRow.addView(av)
                }
                val extra = item.anchors.size - MAX_ANCHORS
                if (extra > 0) {
                    val av = inf.inflate(R.layout.item_anchor_plus, vh.anchorRow, false)
                    av.findViewById<TextView>(R.id.anchorPlus).text = "+$extra"
                    vh.anchorRow.addView(av)
                }
                vh.itemView.contentDescription = "${item.host} vs ${item.guest}, ${item.league}"
            }
        }
    }
}

internal fun android.content.Context.getColorCompat(id: Int): Int =
    androidx.core.content.ContextCompat.getColor(this, id)
