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
import com.kenhlive.tv.ui.FocusKit

/**
 * Adapter cho hàng "Bình Luận Viên Tâm Điểm" — hiển thị các phòng live BLV hot nhất của Socolive
 * với thiết kế thẻ gradient đa sắc màu sang trọng theo phong cách IMG_2815.
 */
class BlvCardsAdapter(
    private val onRoomClick: (LiveRoom) -> Unit,
    private val onRoomLong: ((LiveRoom) -> Unit)? = null
) : ListAdapter<LiveRoom, BlvCardsAdapter.BlvVH>(DIFF) {

    /** Vị trí của hàng BLV trong adapter cha (SportAdapter) — do BlvRowVH gán khi bind. */
    var rowPos: Int = -1

    /** Điều hướng trái/phải ở biên hàng (FocusKit.rowCardKey) — do BlvRowVH gán. */
    var keyHandler: ((rowPos: Int, idx: Int, size: Int) -> Boolean)? = null

    // Khôi phục focus sau refresh (port từ bản vá v7.9 của MatchCardsAdapter):
    // hàng BLV trước đây không nhớ/khôi phục gì nên mỗi lần LiveFragment rebuild là mất focus.
    private var hostRv: RecyclerView? = null

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        hostRv = rv
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        if (hostRv === rv) hostRv = null
        super.onDetachedFromRecyclerView(rv)
    }

    override fun submitList(list: List<LiveRoom>?) {
        submitList(list, null)
    }

    override fun submitList(list: List<LiveRoom>?, commitCallback: Runnable?) {
        val rv = hostRv
        var focusedRoom: Int? = null
        var focusedPos = -1
        if (rv != null && rv.hasFocus()) {
            val f = rv.findFocus()
            val vh = if (f != null) rv.findContainingViewHolder(f) else null
            val p = vh?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
            if (p != RecyclerView.NO_POSITION) {
                focusedPos = p
                focusedRoom = currentList.getOrNull(p)?.roomNum
            }
        }
        super.submitList(list) {
            commitCallback?.run()
            if (rv != null && focusedPos >= 0) {
                restoreFocusAfterSubmit(rv, focusedRoom, focusedPos)
            }
        }
    }

    private fun restoreFocusAfterSubmit(rv: RecyclerView, focusedRoom: Int?, focusedPos: Int) {
        if (rv.findFocus() != null) return
        var target = -1
        if (focusedRoom != null) {
            for (i in 0 until itemCount) {
                if (currentList.getOrNull(i)?.roomNum == focusedRoom) { target = i; break }
            }
        }
        if (target < 0) target = focusedPos.coerceAtMost(itemCount - 1)
        if (target < 0) return
        rv.scrollToPosition(target)
        rv.post {
            val itemView = rv.findViewHolderForAdapterPosition(target)?.itemView
                ?: rv.layoutManager?.findViewByPosition(target)
            val v = itemView?.findViewById<View>(R.id.blvCardRoot)
                ?: itemView?.let { FocusKit.firstFocusableIn(it) }
            v?.requestFocus()
        }
    }

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
        val viewersText = SocoliveRepository.fmtViewers(r.viewers) + " lượt xem"
        h.viewers.text = if (r.score > 0) "$viewersText • Điểm: ${SocoliveRepository.fmtViewers(r.score)}" else viewersText

        h.avatar.load(r.avatar) {
            crossfade(if (DeviceMode.lowRam) 0 else 80)
            transformations(CircleCropTransformation())
            placeholder(R.drawable.logo_placeholder)
            error(R.drawable.logo_placeholder)
        }

        h.root.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_LEFT &&
                keyCode != android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
            val idx = h.bindingAdapterPosition
            if (idx == RecyclerView.NO_POSITION) return@setOnKeyListener false
            keyHandler?.invoke(rowPos, idx, itemCount) ?: false
        }

        h.root.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                val idx = h.bindingAdapterPosition
                if (idx != RecyclerView.NO_POSITION) {
                    // Key FocusKit cho hàng BLV: "B|<roomNum>|" — SportAdapter.slotFor tra cứu theo định dạng này
                    FocusKit.remember(rowPos, idx, "B|${r.roomNum}|")
                }
            }
            v.animate().cancel()
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
