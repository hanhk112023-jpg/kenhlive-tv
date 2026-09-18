package com.kenhlive.tv

import android.text.format.DateFormat
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
import com.kenhlive.tv.ui.FocusKit
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Card kiểu Sport Zone FPT Play / IMG_2815, 2 loại trong cùng 1 rail "Trực Tiếp & Tâm Điểm Thể Thao":
 *  - LIVE:  tag giải cyan + pill LIVE ĐỎ, tỉ số 0:0, 2 tên đội 2 bên, avatar BLV + viewers, viền focus cyan neon
 *  - SOON:  giờ HH:MM, tag đếm ngược "còn Xp", VS badge tròn, tên trận + thời gian bắt đầu
 */
class MatchCardsAdapter(
    private val onLiveClick: (LiveMatchGroup) -> Unit,
    private val onLiveLong: (LiveMatchGroup) -> Unit,
    private val onFixtureClick: (ScheduleMatch) -> Unit
) : ListAdapter<Any, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_LIVE = 0
        private const val TYPE_FIXTURE = 1

        private val DIFF = object : DiffUtil.ItemCallback<Any>() {
            override fun areItemsTheSame(a: Any, b: Any) = when {
                a is LiveMatchGroup && b is LiveMatchGroup -> a.league == b.league && a.matchTitle == b.matchTitle
                a is ScheduleMatch && b is ScheduleMatch -> a.scheduleId == b.scheduleId
                else -> false
            }
            override fun areContentsTheSame(a: Any, b: Any) = when {
                a is LiveMatchGroup && b is LiveMatchGroup ->
                    a.count == b.count && a.totalViewers == b.totalViewers &&
                        a.top.blvName == b.top.blvName && a.top.cover == b.top.cover
                else -> a == b
            }
        }

        fun splitTeams(t: String): Pair<String, String> {
            val i = t.indexOf(" vs ", ignoreCase = true)
            return if (i > 0) t.substring(0, i).trim() to t.substring(i + 4).trim() else t to ""
        }

        fun countdownLabel(at: Long): String {
            val mins = ((at - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
            return if (mins < 60) "⏳ còn ${mins}p"
            else "⏳ còn ${mins / 60}h ${mins % 60}p"
        }
    }

    var rowPos: Int = 0
    var cardFraction: Float = 0.33f
    var keyHandler: ((Int, Int, Int) -> android.view.View.OnKeyListener)? = null

    inner class LiveVH(v: View) : RecyclerView.ViewHolder(v) {
        val league: TextView = v.findViewById(R.id.cardLeague)
        val host: TextView = v.findViewById(R.id.cardHost)
        val guest: TextView = v.findViewById(R.id.cardGuest)
        val avatar: ImageView = v.findViewById(R.id.cardAvatar)
        val viewers: TextView = v.findViewById(R.id.cardViewers)
        val blv: TextView = v.findViewById(R.id.cardBlv)
        val rooms: TextView = v.findViewById(R.id.cardRooms)
        val score: TextView? = v.findViewById(R.id.cardScore)
        val matchTitle: TextView? = v.findViewById(R.id.cardMatchTitle)
        val hostIcon: ImageView? = v.findViewById(R.id.cardHostIcon)
        val guestIcon: ImageView? = v.findViewById(R.id.cardGuestIcon)
    }

    inner class FixtureVH(v: View) : RecyclerView.ViewHolder(v) {
        val league: TextView = v.findViewById(R.id.fxLeague)
        val host: TextView = v.findViewById(R.id.fxHost)
        val guest: TextView = v.findViewById(R.id.fxGuest)
        val time: TextView = v.findViewById(R.id.fxTime)
        val date: TextView = v.findViewById(R.id.fxDate)
        val anchors: LinearLayout = v.findViewById(R.id.fxAnchorRow)
        val countdown: TextView = v.findViewById(R.id.fxCountdown)
        val matchTitle: TextView? = v.findViewById(R.id.fxMatchTitle)
        val hostIcon: ImageView? = v.findViewById(R.id.fxHostIcon)
        val guestIcon: ImageView? = v.findViewById(R.id.fxGuestIcon)
    }

    override fun getItemViewType(pos: Int) =
        if (getItem(pos) is LiveMatchGroup) TYPE_LIVE else TYPE_FIXTURE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        val v = if (viewType == TYPE_LIVE) inf.inflate(R.layout.item_match_card, parent, false)
                else inf.inflate(R.layout.item_fixture_card, parent, false)
        val px = parent.context.resources.displayMetrics.widthPixels
        val target = (px * if (DeviceMode.isTv) 0.33f else 0.62f).toInt()
        val lp = v.layoutParams
        lp.width = target
        v.layoutParams = lp
        return if (viewType == TYPE_LIVE) LiveVH(v) else FixtureVH(v)
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        val card = h.itemView
        keyHandler?.let { card.setOnKeyListener(it(rowPos, pos, itemCount)) }
        card.setOnFocusChangeListener { v, has ->
            if (has) {
                val key = when (val it0 = getItem(pos)) {
                    is LiveMatchGroup -> "L|${it0.league}|${it0.matchTitle}"
                    is ScheduleMatch -> "M|${it0.league}|${it0.host} vs ${it0.guest}"
                    else -> null
                }
                FocusKit.remember(rowPos, pos, key)
            }
            v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f)
                .setDuration(150).start()
            v.elevation = if (has) 16f else 0f
        }
        when (h) {
            is LiveVH -> bindLive(h, getItem(pos) as LiveMatchGroup)
            is FixtureVH -> bindFixture(h, getItem(pos) as ScheduleMatch)
        }
    }

    private fun bindLive(h: LiveVH, g: LiveMatchGroup) {
        val ctx = h.itemView.context
        h.league.text = g.league
        val (host, guest) = splitTeams(g.matchTitle)
        h.host.text = host
        h.guest.text = guest
        h.matchTitle?.text = g.matchTitle
        h.score?.text = "0 : 0"

        if (g.hostIcon.isNotBlank()) {
            h.hostIcon?.visibility = View.VISIBLE
            h.hostIcon?.load(g.hostIcon) {
                crossfade(0)
                placeholder(R.drawable.ic_sports)
                error(R.drawable.ic_sports)
            }
        } else {
            h.hostIcon?.visibility = View.GONE
        }

        if (g.guestIcon.isNotBlank()) {
            h.guestIcon?.visibility = View.VISIBLE
            h.guestIcon?.load(g.guestIcon) {
                crossfade(0)
                placeholder(R.drawable.ic_sports)
                error(R.drawable.ic_sports)
            }
        } else {
            h.guestIcon?.visibility = View.GONE
        }

        h.viewers.text = "👁 " + SocoliveRepository.fmtViewers(g.totalViewers)
        h.blv.text = "Đang trực tiếp • BLV ${g.top.blvName}" + if (g.count > 1) " +${g.count - 1}" else ""
        if (g.count > 1) {
            h.rooms.text = ctx.getString(R.string.live_rooms_badge, g.count)
            h.rooms.visibility = View.VISIBLE
        } else h.rooms.visibility = View.GONE

        h.avatar.load(g.top.avatar) {
            crossfade(if (DeviceMode.lowRam) 0 else 80)
            transformations(CircleCropTransformation())
            placeholder(R.drawable.logo_placeholder)
            error(R.drawable.logo_placeholder)
        }
        h.itemView.setOnClickListener { onLiveClick(g) }
        h.itemView.setOnLongClickListener { onLiveLong(g); true }
        h.itemView.contentDescription = "${g.matchTitle}, LIVE, ${g.top.blvName}"
    }

    private fun bindFixture(h: FixtureVH, m: ScheduleMatch) {
        val ctx = h.itemView.context
        h.league.text = m.league.ifBlank { m.category }
        h.host.text = m.host
        h.guest.text = m.guest
        h.matchTitle?.text = "${m.host} vs ${m.guest}"

        if (m.hostIcon.isNotBlank()) {
            h.hostIcon?.visibility = View.VISIBLE
            h.hostIcon?.load(m.hostIcon) {
                crossfade(0)
                placeholder(R.drawable.ic_sports)
                error(R.drawable.ic_sports)
            }
        } else {
            h.hostIcon?.visibility = View.GONE
        }

        if (m.guestIcon.isNotBlank()) {
            h.guestIcon?.visibility = View.VISIBLE
            h.guestIcon?.load(m.guestIcon) {
                crossfade(0)
                placeholder(R.drawable.ic_sports)
                error(R.drawable.ic_sports)
            }
        } else {
            h.guestIcon?.visibility = View.GONE
        }

        if (m.matchTimeMs > 0) {
            val c = Calendar.getInstance(Locale.US).apply { time = Date(m.matchTimeMs) }
            val timeStr = DateFormat.format("HH:mm", c).toString()
            val dateStr = DateFormat.format("dd.MM", c).toString()
            h.time.text = timeStr
            h.date.text = "Bắt đầu: $timeStr • $dateStr"
            val soon = m.matchTimeMs - System.currentTimeMillis()
            if (soon in 1 until 24 * 3600_000L) {
                h.countdown.text = countdownLabel(m.matchTimeMs)
                h.countdown.visibility = View.VISIBLE
            } else h.countdown.visibility = View.GONE
        } else {
            h.time.text = "--:--"
            h.date.text = "Chưa có giờ"
            h.countdown.visibility = View.GONE
        }

        h.anchors.removeAllViews()
        val inf = LayoutInflater.from(ctx)
        val withRoom = m.anchors.filter { it.roomNum.isNotBlank() }
        (if (withRoom.isNotEmpty()) withRoom else m.anchors).take(4).forEach { a ->
            val iv = inf.inflate(R.layout.item_anchor_avatar, h.anchors, false) as ImageView
            iv.load(a.icon) {
                crossfade(0)
                transformations(CircleCropTransformation())
                placeholder(R.drawable.logo_placeholder)
                error(R.drawable.logo_placeholder)
            }
            h.anchors.addView(iv)
        }
        h.itemView.setOnClickListener { onFixtureClick(m) }
        h.itemView.contentDescription = "${m.host} vs ${m.guest}, ${h.time.text}"
    }
}
