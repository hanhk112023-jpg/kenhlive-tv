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
import coil.transform.CircleCropTransformation

/**
 * Adapter cho hàng "Bình Luận Viên Tâm Điểm" — hiển thị các phòng live BLV hot nhất của Socolive
 * với thiết kế thẻ gradient đa sắc màu sang trọng theo phong cách IMG_2815.
 */
class BlvCardsAdapter(
    private val onRoomClick: (LiveRoom) -> Unit,
    private val onRoomLong: ((LiveRoom) -> Unit)? = null
) : ListAdapter<LiveRoom, BlvCardsAdapter.BlvVH>(DIFF) {

    companion object {
        private const val TYPE_BLV = 10

        private val DIFF = object : DiffUtil.ItemCallback<LiveRoom>() {
            override fun areItemsTheSame(a: LiveRoom, b: LiveRoom) = a.roomNum == b.roomNum
            override fun areContentsTheSame(a: LiveRoom, b: LiveRoom) =
                a.viewers == b.viewers && a.blvName == b.blvName && a.matchTitle == b.matchTitle
        }

        private val GRADIENTS = intArrayOf(
            R.drawable.bg_blv_card_1,
            R.drawable.bg_blv_card_2,
            R.drawable.bg_blv_card_3,
            R.drawable.bg_blv_card_4,
            R.drawable.bg_blv_card_5,
            R.drawable.bg_blv_card_6
        )
    }

    inner class BlvVH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v.findViewById(R.id.blvCardRoot)
        val rank: TextView = v.findViewById(R.id.blvRank)
        val liveTag: TextView = v.findViewById(R.id.blvLiveTag)
        val avatar: ImageView = v.findViewById(R.id.blvAvatar)
        val name: TextView = v.findViewById(R.id.blvName)
        val levelBadge: TextView? = v.findViewById(R.id.blvLevelBadge)
        val league: TextView = v.findViewById(R.id.blvLeague)
        val matchTitle: TextView = v.findViewById(R.id.blvMatchTitle)
        val viewers: TextView = v.findViewById(R.id.blvViewers)
    }

    override fun getItemViewType(position: Int): Int = TYPE_BLV

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BlvVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_blv_card, parent, false)
        return BlvVH(v)
    }

    override fun onBindViewHolder(h: BlvVH, pos: Int) {
        val r = getItem(pos)
        h.root.setBackgroundResource(GRADIENTS[pos % GRADIENTS.size])
        h.rank.text = "TOP %02d".format(pos + 1)
        h.name.text = r.blvName
        if (r.blvLevel.isNotBlank()) {
            h.levelBadge?.visibility = View.VISIBLE
            h.levelBadge?.text = r.blvLevel
        } else {
            h.levelBadge?.visibility = View.GONE
        }
        h.league.text = r.league.ifBlank { "Socolive" }
        h.matchTitle.text = r.matchTitle
        val viewersText = "👁 " + SocoliveRepository.fmtViewers(r.viewers) + " lượt xem"
        h.viewers.text = if (r.score > 0) "$viewersText • ⭐ ${SocoliveRepository.fmtViewers(r.score)}" else viewersText

        h.avatar.load(r.avatar) {
            crossfade(if (DeviceMode.lowRam) 0 else 80)
            transformations(CircleCropTransformation())
            placeholder(R.drawable.logo_placeholder)
            error(R.drawable.logo_placeholder)
        }

        h.root.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.05f else 1.0f)
                .scaleY(if (hasFocus) 1.05f else 1.0f)
                .setDuration(150)
                .start()
            v.elevation = if (hasFocus) 16f else 0f
        }

        h.root.setOnClickListener { onRoomClick(r) }
        h.root.setOnLongClickListener {
            onRoomLong?.invoke(r)
            true
        }
    }
}
