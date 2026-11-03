package com.kenhlive.tv

import android.text.format.DateFormat
import java.util.TimeZone
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
            val parsed = DataNormalizer.parseMatch(t)
            return parsed.host to parsed.guest
        }

        fun countdownLabel(at: Long): String {
            val mins = ((at - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
            return if (mins < 60) "Còn ${mins}p"
            else "Còn ${mins / 60}h ${mins % 60}p"
        }

        fun isMatchLive(g: LiveMatchGroup, parsed: DataNormalizer.ParsedMatch): Boolean {
            // 1. Trạng thái từ API chính thức (ColaTV, Gà Vàng 33, Giờ Vàng TV)
            if (g.isLiveGame != null) {
                return g.isLiveGame == true
            }

            val title = (g.matchTitle + " " + g.top.notice).lowercase(Locale.ROOT)
            if (title.contains("[sắp đá]") || title.contains("[sắp diễn ra]") ||
                title.contains("[chưa đá]") || title.contains("[sắp]") ||
                title.contains("[upcoming]") || title.contains("[chờ]") ||
                title.contains("chưa bắt đầu") || title.contains("chưa đến giờ")) {
                return false
            }

            val effectiveHostScore = g.hostScore ?: parsed.hostScore
            val effectiveGuestScore = g.guestScore ?: parsed.guestScore
            if (effectiveHostScore != null && effectiveGuestScore != null) {
                return true
            }

            if (title.contains("[live]") || title.contains("[trực tiếp]") || title.contains("[đang đá]")) {
                return true
            }

            // 2. Kiểm tra theo mốc thời gian đá chuẩn (Epoch Timestamp)
            if (g.matchTimeMs > 0L) {
                val now = System.currentTimeMillis()
                val ms = if (g.matchTimeMs in 1..100_000_000_000L) g.matchTimeMs * 1000L else g.matchTimeMs
                if (ms > now + 10 * 60_000L) {
                    return false
                }
                if (ms in (now - 140 * 60_000L)..(now + 10 * 60_000L)) {
                    return true
                }
                if (now > ms + 140 * 60_000L) {
                    return false
                }
            }

            val kickoff = g.kickoffTime.ifBlank { extractKickoffTime(g.matchTitle, g.top.notice) }
            if (kickoff.isNotBlank() && effectiveHostScore == null) {
                if (kickoff.contains("Ngày mai") || kickoff.contains("/")) {
                    return false
                }
                try {
                    val timePart = kickoff.split(" ")[0].trim()
                    val parts = timePart.split(":")
                    if (parts.size == 2) {
                        val kh = parts[0].toIntOrNull() ?: 0
                        val km = parts[1].toIntOrNull() ?: 0
                        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh"))
                        val curH = cal.get(java.util.Calendar.HOUR_OF_DAY)
                        val curM = cal.get(java.util.Calendar.MINUTE)
                        val curTotalMin = curH * 60 + curM
                        val kickTotalMin = kh * 60 + km
                        if (kickTotalMin - curTotalMin > 15) {
                            return false
                        }
                    }
                } catch (_: Exception) {}
                if (g.totalViewers < 25) return false
            }
            val timeRegex = Regex("""^\s*(\d{1,2}):(\d{2})""")
            if (timeRegex.containsMatchIn(g.matchTitle.trim()) && effectiveHostScore == null) {
                if (g.totalViewers < 25) return false
            }
            return true
        }

        fun extractKickoffTime(matchTitle: String, notice: String): String {
            val timeRegex = Regex("""\b(\d{1,2}:\d{2})\b""")
            val m = timeRegex.find(matchTitle) ?: timeRegex.find(notice)
            return m?.value?.trim().orEmpty()
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
        val provider: TextView? = v.findViewById(R.id.cardProvider)
        val liveBadge: TextView? = v.findViewById(R.id.cardLive)
        val timeBadge: TextView? = v.findViewById(R.id.cardTimeBadge)
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

    // v7.9 (fix issue #1, phía rail ngang): giữ focus theo vị trí khi submitList làm detach card đang focus.
    private var hostRv: RecyclerView? = null

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        hostRv = rv
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        if (hostRv === rv) hostRv = null
        super.onDetachedFromRecyclerView(rv)
    }

    override fun submitList(list: MutableList<Any>?) {
        submitList(list, null)
    }

    override fun submitList(list: MutableList<Any>?, commitCallback: Runnable?) {
        val rv = hostRv
        val focusedPos = if (rv != null && rv.hasFocus()) {
            val f = rv.findFocus()
            if (f != null) rv.getChildAdapterPosition(f) else -1
        } else -1
        super.submitList(list) {
            commitCallback?.run()
            if (rv != null && focusedPos >= 0 && rv.findFocus() == null) {
                val target = focusedPos.coerceAtMost(itemCount - 1)
                if (target >= 0) {
                    rv.post {
                        if (rv.findFocus() == null) {
                            val v = rv.layoutManager?.findViewByPosition(target)
                                ?: rv.findViewHolderForAdapterPosition(target)?.itemView
                            v?.requestFocus()
                        }
                    }
                }
            }
        }
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
            v.animate().cancel()
            v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f)
                .setDuration(100).start()
            v.elevation = if (has) 16f else 0f
        }
        when (h) {
            is LiveVH -> bindLive(h, getItem(pos) as LiveMatchGroup)
            is FixtureVH -> bindFixture(h, getItem(pos) as ScheduleMatch)
        }
    }

    private fun bindLive(h: LiveVH, g: LiveMatchGroup) {
        val ctx = h.itemView.context
        val parsed = DataNormalizer.parseMatch(g.matchTitle, g.league)
        h.league.text = parsed.league
        h.host.text = parsed.host
        h.guest.text = parsed.guest
        h.matchTitle?.text = parsed.cleanTitle

        // 1. Tag đài phát sóng (Branded Source Badge)
        val src = com.kenhlive.tv.sports.SportsSource.detectSource(g.top.roomNum, g.top.blvName)
        if (h.provider != null) {
            h.provider.text = src.shortTag
            h.provider.visibility = View.VISIBLE
            val badgeColor = when (src) {
                com.kenhlive.tv.sports.SportsSource.COLATV -> android.graphics.Color.parseColor("#E11D48") // Đỏ Rose
                com.kenhlive.tv.sports.SportsSource.GAVANG33 -> android.graphics.Color.parseColor("#D97706") // Hổ Phách
                com.kenhlive.tv.sports.SportsSource.GIOVANG -> android.graphics.Color.parseColor("#2563EB") // Xanh Royal
                com.kenhlive.tv.sports.SportsSource.SOCOLIVE -> android.graphics.Color.parseColor("#059669") // Xanh Ngọc
            }
            val grad = android.graphics.drawable.GradientDrawable().apply {
                setColor(badgeColor)
                cornerRadius = 10f * ctx.resources.displayMetrics.density
            }
            h.provider.background = grad
            h.provider.setTextColor(android.graphics.Color.WHITE)
        }

        // 2. Phân biệt rõ Trực Tiếp (LIVE) và Sắp Diễn Ra (UPCOMING) kèm giờ thi đấu
        val isTrulyLive = isMatchLive(g, parsed)
        val kickoff = if (g.kickoffTime.isNotBlank()) g.kickoffTime else extractKickoffTime(g.matchTitle, g.top.notice)
        val effectiveHostScore = g.hostScore ?: parsed.hostScore
        val effectiveGuestScore = g.guestScore ?: parsed.guestScore

        if (isTrulyLive) {
            h.liveBadge?.visibility = View.VISIBLE
            if (g.liveClock.isNotBlank()) {
                h.liveBadge?.text = "● ${g.liveClock}"
            } else {
                h.liveBadge?.text = "● LIVE"
            }
            h.liveBadge?.setBackgroundResource(R.drawable.bg_badge_live_red)
            h.liveBadge?.setTextColor(android.graphics.Color.WHITE)

            if (effectiveHostScore != null && effectiveGuestScore != null) {
                h.score?.setBackgroundResource(R.drawable.bg_score_pill_live)
                h.score?.setTextColor(android.graphics.Color.parseColor("#FFD700")) // Vàng Neon rực rỡ
                h.score?.text = "$effectiveHostScore : $effectiveGuestScore"
            } else {
                h.score?.setBackgroundResource(R.drawable.bg_score_pill)
                h.score?.setTextColor(android.graphics.Color.parseColor("#94A3B8")) // Xám Bạc
                h.score?.text = "VS"
            }

            if (h.timeBadge != null) {
                if (g.liveClock.isNotBlank()) {
                    h.timeBadge.text = g.liveClock
                    h.timeBadge.visibility = View.VISIBLE
                } else {
                    h.timeBadge.visibility = View.GONE
                }
            }
        } else {
            h.liveBadge?.visibility = View.VISIBLE
            h.liveBadge?.text = "SẮP ĐÁ"
            h.liveBadge?.setBackgroundResource(R.drawable.bg_badge_countdown)
            h.liveBadge?.setTextColor(android.graphics.Color.parseColor("#F59E0B")) // Hổ Phách Amber

            h.score?.setBackgroundResource(R.drawable.bg_score_pill)
            h.score?.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            h.score?.text = if (kickoff.isNotBlank()) kickoff else "VS"

            if (h.timeBadge != null) {
                if (kickoff.isNotBlank()) {
                    h.timeBadge.text = kickoff
                    h.timeBadge.visibility = View.VISIBLE
                } else {
                    h.timeBadge.visibility = View.GONE
                }
            }
        }

        // 3. Logo 2 đội bóng cân đối đối xứng 100%
        val teamA = if (parsed.host.isNotBlank()) parsed.host else g.matchTitle
        val teamB = parsed.guest
        h.host.text = teamA
        h.guest.text = teamB

        h.hostIcon?.visibility = View.VISIBLE
        val hostLogo = if (g.hostIcon.isNotBlank()) g.hostIcon else com.kenhlive.tv.sports.TeamLogoRegistry.findLogo(teamA).orEmpty()
        if (hostLogo.isNotBlank()) {
            h.hostIcon?.load(hostLogo) {
                crossfade(false)
                placeholder(R.drawable.bg_team_crest_placeholder)
                error(R.drawable.bg_team_crest_placeholder)
            }
        } else {
            h.hostIcon?.setImageResource(R.drawable.bg_team_crest_placeholder)
        }

        h.guestIcon?.visibility = View.VISIBLE
        val guestLogo = if (g.guestIcon.isNotBlank()) g.guestIcon else com.kenhlive.tv.sports.TeamLogoRegistry.findLogo(teamB).orEmpty()
        if (guestLogo.isNotBlank()) {
            h.guestIcon?.load(guestLogo) {
                crossfade(false)
                placeholder(R.drawable.bg_team_crest_placeholder)
                error(R.drawable.bg_team_crest_placeholder)
            }
        } else {
            h.guestIcon?.setImageResource(R.drawable.bg_team_crest_placeholder)
        }

        h.viewers.text = SocoliveRepository.fmtViewers(g.totalViewers) + " xem"
        val rawBlv = g.top.blvName.trim()
        val formattedBlv = if (rawBlv.startsWith("BLV ", ignoreCase = true)) rawBlv else "BLV $rawBlv"
        val countSuffix = if (g.count > 1) " +${g.count - 1}" else ""
        if (isTrulyLive) {
            h.blv.text = if (kickoff.isNotBlank()) "Trực tiếp ($kickoff) • $formattedBlv$countSuffix" else "Đang trực tiếp • $formattedBlv$countSuffix"
        } else {
            h.blv.text = if (kickoff.isNotBlank()) "Lên sóng lúc $kickoff • $formattedBlv$countSuffix" else "Sắp lên sóng • $formattedBlv$countSuffix"
        }
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
        h.itemView.contentDescription = "${g.matchTitle}, ${if (isTrulyLive) "TRỰC TIẾP" else "SẮP DIỄN RA"}, ${g.top.blvName}"
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
            val timeStr = SocoliveRepository.formatTime(m.matchTimeMs)
            val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"), Locale.US).apply { time = Date(m.matchTimeMs) }
            val dateStr = String.format(Locale.US, "%02d.%02d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1)
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
