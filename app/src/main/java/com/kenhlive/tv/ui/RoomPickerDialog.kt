package com.kenhlive.tv.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.kenhlive.tv.KenhLiveApp
import com.kenhlive.tv.LiveMatchGroup
import com.kenhlive.tv.LiveRoom
import com.kenhlive.tv.R
import com.kenhlive.tv.SocoliveRepository

/**
 * Dialog chọn phòng BLV trong 1 trận (+ tuỳ chọn đổi sang trận khác).
 * Dùng chung cho Live / Search / Schedule / MultiView — hết cảnh copy-paste 4 nơi.
 * Focus D-pad: danh sách là RecyclerView, item focusable, dialog tự trap focus.
 */
object RoomPickerDialog {

    fun show(
        context: Context,
        group: LiveMatchGroup,
        onPickRoom: (LiveRoom) -> Unit,
        otherGroups: List<LiveMatchGroup> = emptyList(),
        onPickGroup: (LiveMatchGroup) -> Unit = {}
    ): AlertDialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_room_picker, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = group.matchTitle
        view.findViewById<TextView>(R.id.dialogLeague).text =
            context.getString(R.string.picker_rooms_meta, group.league, group.count)

        val list = view.findViewById<RecyclerView>(R.id.roomList)
        list.layoutManager = LinearLayoutManager(context)

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .create()

        val items = mutableListOf<Pair<Any, () -> Unit>>()
        group.rooms.forEach { r ->
            items.add(r to {
                onPickRoom(r)
                dialog.dismiss()
            })
        }
        otherGroups.filter { it !== group }.take(6).forEach { og ->
            items.add(og to {
                onPickGroup(og)
                dialog.dismiss()
            })
        }
        list.adapter = Adapter(context, items, group)
        dialog.show()
        // focus item đầu cho remote
        list.post { list.getChildAt(0)?.requestFocus() }
        return dialog
    }

    private class Adapter(
        private val ctx: Context,
        private val items: List<Pair<Any, () -> Unit>>,
        private val group: LiveMatchGroup
    ) : RecyclerView.Adapter<Adapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val avatar: ImageView = v.findViewById(R.id.roomAvatar)
            val name: TextView = v.findViewById(R.id.roomName)
            val meta: TextView = v.findViewById(R.id.roomMeta)
            val live: TextView = v.findViewById(R.id.roomLive)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(ctx).inflate(R.layout.item_room_option, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val (model, action) = items[pos]
            FocusKit.decorateCard(h.itemView, scale = 1.02f, elevation = 8f)
            when (model) {
                is LiveRoom -> {
                    h.name.text = model.blvName
                    h.meta.text = ctx.getString(
                        R.string.picker_viewers_live, SocoliveRepository.fmtViewers(model.viewers)
                    )
                    h.live.visibility = View.VISIBLE
                    h.avatar.load(model.avatar) {
                        crossfade(if (KenhLiveApp.lowRam) 0 else 80)
                        transformations(CircleCropTransformation())
                        placeholder(R.drawable.logo_placeholder); error(R.drawable.logo_placeholder)
                    }
                }
                is LiveMatchGroup -> {
                    h.name.text = model.matchTitle
                    h.meta.text = ctx.getString(
                        R.string.picker_other_meta, model.league, model.count,
                        SocoliveRepository.fmtViewers(model.totalViewers)
                    )
                    h.live.visibility = View.GONE
                    h.avatar.load(model.top.avatar) {
                        crossfade(if (KenhLiveApp.lowRam) 0 else 80)
                        transformations(CircleCropTransformation())
                        placeholder(R.drawable.logo_placeholder); error(R.drawable.logo_placeholder)
                    }
                }
            }
            h.itemView.setOnClickListener { action() }
        }
    }
}
