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
import coil.transform.RoundedCornersTransformation
import com.kenhlive.tv.ui.FocusKit

/**
 * Card trận trong 1 row ngang. ListAdapter + DiffUtil: refresh nền CHỈ rebind card đổi,
 * không destroy view đang focus (nguồn bug "nhảy lung tung" của bản cũ).
 */
class MatchCardsAdapter(
    private val onGroupClick: (LiveMatchGroup) -> Unit,
    private val onLongClickGroup: (LiveMatchGroup) -> Unit
) : ListAdapter<LiveMatchGroup, MatchCardsAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<LiveMatchGroup>() {
            override fun areItemsTheSame(a: LiveMatchGroup, b: LiveMatchGroup) =
                a.league == b.league && a.matchTitle == b.matchTitle
            override fun areContentsTheSame(a: LiveMatchGroup, b: LiveMatchGroup) =
                a.count == b.count && a.totalViewers == b.totalViewers &&
                    a.top.blvName == b.top.blvName && a.top.cover == b.top.cover
        }
    }

    /** Vị trí hàng trong adapter ngoài — FocusKit cần để đi UP/DOWN đúng hàng. */
    var rowPos: Int = 0
    var keyHandler: ((Int, Int, Int) -> android.view.View.OnKeyListener)? = null

    class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.cardThumb)
        val avatar: ImageView = v.findViewById(R.id.cardAvatar)
        val blv: TextView = v.findViewById(R.id.cardBlv)
        val match: TextView = v.findViewById(R.id.cardMatch)
        val viewers: TextView = v.findViewById(R.id.cardViewers)
        val badge: TextView = v.findViewById(R.id.roomBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_match_card, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val g = currentList[pos]
        val ctx = h.itemView.context
        keyHandler?.let { h.itemView.setOnKeyListener(it(rowPos, pos, currentList.size)) }
        h.itemView.setOnFocusChangeListener { _, has ->
            if (has) FocusKit.remember(rowPos, pos)
            h.itemView.animate()
                .scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f)
                .setDuration(150).start()
            h.itemView.elevation = if (has) 16f else 0f
        }
        h.thumb.load(g.top.cover.ifBlank { g.top.avatar }) {
            crossfade(if (DeviceMode.lowRam) 0 else 150)
            transformations(RoundedCornersTransformation(10f))
            placeholder(R.drawable.hero_fallback)
            error(R.drawable.hero_fallback)
        }
        h.avatar.load(g.top.avatar) {
            crossfade(if (DeviceMode.lowRam) 0 else 80)
            transformations(CircleCropTransformation())
            placeholder(R.drawable.logo_placeholder)
            error(R.drawable.logo_placeholder)
        }
        h.blv.text = g.top.blvName + if (g.count > 1) " ${ctx.getString(R.string.live_blv_more, g.count - 1)}" else ""
        h.match.text = g.matchTitle
        h.viewers.text = SocoliveRepository.fmtViewers(g.totalViewers)
        if (g.count > 1) {
            h.badge.text = ctx.getString(R.string.live_rooms_badge, g.count)
            h.badge.visibility = android.view.View.VISIBLE
        } else h.badge.visibility = android.view.View.GONE
        h.itemView.setOnClickListener { onGroupClick(g) }
        h.itemView.setOnLongClickListener { onLongClickGroup(g); true }
        h.itemView.contentDescription = "${g.matchTitle}, ${g.top.blvName}"
    }
}
