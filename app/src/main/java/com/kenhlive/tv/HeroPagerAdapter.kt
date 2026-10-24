package com.kenhlive.tv

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.kenhlive.tv.ui.LunarCalendar

/**
 * Hero carousel (top trận hot). Tự lật 5s (9s máy yếu) nhưng DỪNG khi user đang focus/touch
 * để không giật focus giữa chừng trên TV. Dots indicator đồng bộ trang.
 * Hỗ trợ giao diện kiểu IMG_2815: đồng hồ + lịch âm dương, LIVE meta, nút Xem ngay + Chi tiết.
 */
class HeroPagerAdapter(
    private val groups: List<LiveMatchGroup>,
    private val onClick: (LiveMatchGroup) -> Unit,
    private val onDetailsClick: ((LiveMatchGroup) -> Unit)? = null,
    var onDownKey: (() -> Boolean)? = null
) : RecyclerView.Adapter<HeroPagerAdapter.HV>() {

    private val handler = Handler(Looper.getMainLooper())
    private var pager: ViewPager2? = null
    private var dots: LinearLayout? = null

    private val auto = object : Runnable {
        override fun run() {
            val p = pager
            val currentFocus = p?.rootView?.findFocus()
            val userIsBrowsing = currentFocus != null && !p.hasFocus()
            if (p != null && groups.size > 1 && !p.hasFocus() && !userIsBrowsing) {
                p.currentItem = (p.currentItem + 1) % groups.size
            }
            handler.postDelayed(this, if (DeviceMode.lowRam) 9000 else 6000)
        }
    }

    private val pageCb = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(pos: Int) = paintDots(pos)
    }

    fun attach(p: ViewPager2, dotsBox: LinearLayout?) {
        pager = p
        dots = dotsBox
        buildDots()
        p.unregisterOnPageChangeCallback(pageCb)
        p.registerOnPageChangeCallback(pageCb)
        handler.removeCallbacks(auto)
        handler.postDelayed(auto, if (DeviceMode.lowRam) 9000 else 6000)
    }

    fun detach() {
        handler.removeCallbacks(auto)
        pager?.unregisterOnPageChangeCallback(pageCb)
        pager = null
        dots = null
    }

    private fun buildDots() {
        val box = dots ?: return
        box.removeAllViews()
        val ctx = box.context
        val d = (6 * ctx.resources.displayMetrics.density).toInt()
        val m = (3 * ctx.resources.displayMetrics.density).toInt()
        repeat(groups.size.coerceAtMost(8)) {
            val v = View(ctx)
            val lp = LinearLayout.LayoutParams(d, d)
            lp.setMargins(m, 0, m, 0)
            v.layoutParams = lp
            v.setBackgroundResource(R.drawable.bg_circle)
            box.addView(v)
        }
        paintDots(pager?.currentItem ?: 0)
    }

    private fun paintDots(pos: Int) {
        val box = dots ?: return
        val ctx = box.context
        val d = (6 * ctx.resources.displayMetrics.density).toInt()
        val wOn = (16 * ctx.resources.displayMetrics.density).toInt()
        for (i in 0 until box.childCount) {
            val v = box.getChildAt(i)
            val on = i == pos
            v.setBackgroundResource(if (on) R.drawable.bg_badge_live else R.drawable.bg_circle)
            v.alpha = if (on) 1f else 0.35f
            val lp = v.layoutParams
            if (lp.width != (if (on) wOn else d)) {
                lp.width = if (on) wOn else d
                v.layoutParams = lp
            }
        }
    }

    inner class HV(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.heroCover)
        val title: TextView = v.findViewById(R.id.heroTitle)
        val league: TextView = v.findViewById(R.id.heroLeague)
        val blv: TextView = v.findViewById(R.id.heroBlv)
        val viewers: TextView = v.findViewById(R.id.heroViewers)
        val play: View = v.findViewById(R.id.heroPlay)
        val details: View? = v.findViewById(R.id.heroDetails)
        val clockText: TextView? = v.findViewById(R.id.heroClockText)
        val solarDate: TextView? = v.findViewById(R.id.heroSolarDate)
        val lunarDate: TextView? = v.findViewById(R.id.heroLunarDate)
        val liveBadge: TextView? = v.findViewById(R.id.heroLiveBadge)
        val playText: TextView? = v.findViewById(R.id.heroPlayText)
        val arenaContainer: View? = v.findViewById(R.id.heroArenaContainer)
        val hostIcon: ImageView? = v.findViewById(R.id.heroHostIcon)
        val hostName: TextView? = v.findViewById(R.id.heroHostName)
        val scoreText: TextView? = v.findViewById(R.id.heroScoreText)
        val guestName: TextView? = v.findViewById(R.id.heroGuestName)
        val guestIcon: ImageView? = v.findViewById(R.id.heroGuestIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HV =
        HV(LayoutInflater.from(parent.context).inflate(R.layout.item_hero, parent, false))

    override fun getItemCount(): Int = groups.size.coerceAtMost(8)

    override fun onBindViewHolder(h: HV, pos: Int) {
        val g = groups[pos]
        val top = g.top
        val parsed = DataNormalizer.parseMatch(g.matchTitle, g.league)
        h.league.text = "• ${parsed.league}"

        val isTrulyLive = MatchCardsAdapter.isMatchLive(g, parsed)
        val kickoff = MatchCardsAdapter.extractKickoffTime(g.matchTitle, top.notice)

        val hasArenaTeams = parsed.host.isNotBlank() && parsed.guest.isNotBlank()
        if (hasArenaTeams) {
            h.arenaContainer?.visibility = View.VISIBLE
            h.title.visibility = View.GONE
            h.hostName?.text = parsed.host
            h.guestName?.text = parsed.guest

            val hostLogo = if (g.hostIcon.isNotBlank()) g.hostIcon else com.kenhlive.tv.sports.TeamLogoRegistry.findLogo(parsed.host).orEmpty()
            if (hostLogo.isNotBlank()) {
                h.hostIcon?.load(hostLogo) {
                    crossfade(false)
                    placeholder(R.drawable.bg_team_crest_placeholder)
                    error(R.drawable.bg_team_crest_placeholder)
                }
            } else {
                h.hostIcon?.setImageResource(R.drawable.bg_team_crest_placeholder)
            }

            val guestLogo = if (g.guestIcon.isNotBlank()) g.guestIcon else com.kenhlive.tv.sports.TeamLogoRegistry.findLogo(parsed.guest).orEmpty()
            if (guestLogo.isNotBlank()) {
                h.guestIcon?.load(guestLogo) {
                    crossfade(false)
                    placeholder(R.drawable.bg_team_crest_placeholder)
                    error(R.drawable.bg_team_crest_placeholder)
                }
            } else {
                h.guestIcon?.setImageResource(R.drawable.bg_team_crest_placeholder)
            }

            if (isTrulyLive) {
                if (parsed.hostScore != null && parsed.guestScore != null) {
                    h.scoreText?.text = "${parsed.hostScore} : ${parsed.guestScore}"
                    h.scoreText?.setBackgroundResource(R.drawable.bg_score_pill_live)
                    h.scoreText?.setTextColor(android.graphics.Color.parseColor("#FFD700"))
                } else {
                    h.scoreText?.text = "VS"
                    h.scoreText?.setBackgroundResource(R.drawable.bg_score_pill)
                    h.scoreText?.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                }
            } else {
                if (kickoff.isNotBlank()) {
                    h.scoreText?.text = kickoff
                    h.scoreText?.setBackgroundResource(R.drawable.bg_score_pill)
                    h.scoreText?.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
                } else {
                    h.scoreText?.text = "VS"
                    h.scoreText?.setBackgroundResource(R.drawable.bg_score_pill)
                    h.scoreText?.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                }
            }
        } else {
            h.arenaContainer?.visibility = View.GONE
            h.title.visibility = View.VISIBLE
            h.title.text = parsed.cleanTitle
        }

        if (isTrulyLive) {
            h.liveBadge?.text = "● TRỰC TIẾP"
            h.liveBadge?.setBackgroundResource(R.drawable.bg_badge_live_red)
            h.liveBadge?.setTextColor(android.graphics.Color.WHITE)
            h.playText?.text = "[OK] Xem ngay"
        } else {
            h.liveBadge?.text = if (kickoff.isNotBlank()) "SẮP DIỄN RA • $kickoff" else "SẮP DIỄN RA"
            h.liveBadge?.setBackgroundResource(R.drawable.bg_badge_countdown)
            h.liveBadge?.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
            h.playText?.text = "[OK] Đặt lịch / Xem trước"
        }

        val desc = buildString {
            if (isTrulyLive) {
                append("Trực tiếp")
            } else {
                if (kickoff.isNotBlank()) append("Sắp diễn ra lúc $kickoff") else append("Sắp diễn ra")
            }
            if (parsed.league.isNotBlank()) append(" • ${parsed.league}")
            val rawBlv = top.blvName.trim()
            val formattedBlv = if (rawBlv.startsWith("BLV ", ignoreCase = true)) rawBlv else "BLV $rawBlv"
            append(" • $formattedBlv")
            append(" • Tín hiệu HD")
        }
        h.blv.text = desc
        h.viewers.text = SocoliveRepository.fmtViewers(g.totalViewers)

        // Đồng hồ & Lịch Âm - Dương
        h.clockText?.text = LunarCalendar.formatTime()
        h.solarDate?.text = LunarCalendar.formatSolarDate()
        h.lunarDate?.text = LunarCalendar.formatLunarDate()

        val imgSrc = top.cover.ifBlank { top.avatar }
        h.cover.load(imgSrc) {
            crossfade(if (DeviceMode.lowRam) 0 else 200)
            placeholder(R.drawable.hero_fallback)
            error(R.drawable.hero_fallback)
        }

        h.itemView.setOnClickListener { onClick(g) }
        h.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                onDownKey?.invoke()
                true
            } else false
        }
        h.play.setOnClickListener { onClick(g) }
        h.details?.setOnClickListener {
            if (onDetailsClick != null) onDetailsClick.invoke(g) else onClick(g)
        }

        h.play.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    onDownKey?.invoke()
                    true // Nuốt triệt để để Android FocusFinder không tự nhảy chéo xuống Card 2
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> true
                else -> false
            }
        }

        h.details?.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    onDownKey?.invoke()
                    true // Nuốt triệt để để Android FocusFinder không tự nhảy chéo xuống Card 2
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> true
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> false
            }
        }

        if (pos == 0 && DeviceMode.isTv) {
            h.play.post {
                val f = h.play.rootView?.findFocus()
                if (f == null || f is RecyclerView || f is ViewPager2) {
                    h.play.requestFocus()
                }
            }
        }
    }
}
