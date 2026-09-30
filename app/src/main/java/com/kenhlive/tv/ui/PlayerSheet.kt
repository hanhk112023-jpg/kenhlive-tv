package com.kenhlive.tv.ui

import android.app.Activity
import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kenhlive.tv.DeviceMode
import com.kenhlive.tv.R

/**
 * Bảng điều khiển Player v7 — MỘT component cho cả hai nền tảng:
 *  - Điện thoại dọc: bottom sheet (vuốt/chạm ngoài để đóng)
 *  - Điện thoại ngang + TV: side panel bên phải (D-pad được, không che hết hình)
 * Hỗ trợ menu lồng nhau (push/pop), BACK quay lại một cấp.
 */
class PlayerSheet(private val activity: Activity, root: Page) {

    class Row(
        @DrawableRes val icon: Int,
        val title: String,
        val value: String = "",
        val selected: Boolean = false,
        val chevron: Boolean = false,
        val onClick: (Ctl) -> Unit
    )

    /** rows dựng lại mỗi lần render → trang tự cập nhật giá trị sau khi bấm (refresh). */
    class Page(val title: String, val focusIndex: () -> Int = { 0 }, val rows: () -> List<Row>)

    class Ctl internal constructor(private val s: PlayerSheet) {
        fun push(p: Page) = s.push(p)
        fun refresh() = s.render(keepFocus = true)
        fun dismiss() = s.dialog.dismiss()
    }

    private val stack = ArrayList<Page>().apply { add(root) }
    private val ctl = Ctl(this)
    private lateinit var list: RecyclerView
    private lateinit var titleView: TextView
    private lateinit var backView: View
    private val adapter = RowAdapter()

    private val side = DeviceMode.isTv ||
        activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val dialog: Dialog = object : Dialog(activity, R.style.Theme_KenhLive_Sheet) {
        @Deprecated("Deprecated in Java")
        override fun onBackPressed() {
            if (stack.size > 1) pop() else super.onBackPressed()
        }
    }

    fun show(onDismiss: () -> Unit = {}): PlayerSheet {
        val v = LayoutInflater.from(activity).inflate(R.layout.dialog_player_sheet, null)
        v.setBackgroundResource(if (side) R.drawable.bg_sheet_side else R.drawable.bg_sheet_bottom)
        list = v.findViewById(R.id.sheetList)
        titleView = v.findViewById(R.id.sheetTitle)
        backView = v.findViewById(R.id.sheetBack)
        v.findViewById<View>(R.id.sheetHandle).visibility = if (side) View.GONE else View.VISIBLE
        backView.setOnClickListener { pop() }
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        list.itemAnimator = null

        dialog.setContentView(
            v,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (side) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        dialog.window?.apply {
            val dm = activity.resources.displayMetrics
            if (side) {
                val w = minOf(activity.resources.getDimensionPixelSize(R.dimen.sheet_w), (dm.widthPixels * 0.6f).toInt())
                setLayout(w, ViewGroup.LayoutParams.MATCH_PARENT)
                setGravity(Gravity.END)
                setWindowAnimations(R.style.KL_SheetAnim_Side)
            } else {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.BOTTOM)
                setWindowAnimations(R.style.KL_SheetAnim_Bottom)
            }
        }
        dialog.setOnDismissListener { onDismiss() }
        render(keepFocus = false)
        dialog.show()
        return this
    }

    fun push(p: Page) { stack.add(p); render(keepFocus = false) }

    private fun pop() {
        if (stack.size > 1) { stack.removeAt(stack.size - 1); render(keepFocus = false) }
        else dialog.dismiss()
    }

    private fun render(keepFocus: Boolean) {
        val page = stack.last()
        val rows = page.rows()
        var focusPos = if (keepFocus) list.focusedChild?.let { list.getChildAdapterPosition(it) } ?: -1 else -1
        if (focusPos < 0) focusPos = page.focusIndex().coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        titleView.text = page.title
        backView.visibility = if (stack.size > 1) View.VISIBLE else View.GONE
        adapter.submit(rows)
        if (DeviceMode.isTv || keepFocus) {
            list.post {
                list.scrollToPosition(focusPos)
                list.post { list.findViewHolderForAdapterPosition(focusPos)?.itemView?.requestFocus() }
            }
        }
    }

    private inner class RowAdapter : RecyclerView.Adapter<VH>() {
        private var rows: List<Row> = emptyList()
        fun submit(r: List<Row>) { rows = r; notifyDataSetChanged() }
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_sheet_row, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            h.icon.setImageResource(r.icon)
            h.icon.setColorFilter(if (r.selected) androidx.core.content.ContextCompat.getColor(activity, R.color.kl_brand) else Color.WHITE, PorterDuff.Mode.SRC_IN)
            h.title.text = r.title
            h.title.setTextColor(androidx.core.content.ContextCompat.getColor(activity, if (r.selected) R.color.kl_brand else R.color.kl_text_1))
            h.value.text = r.value
            h.value.visibility = if (r.value.isEmpty()) View.GONE else View.VISIBLE
            h.check.visibility = if (r.chevron) View.VISIBLE else View.GONE
            h.itemView.isSelected = r.selected
            h.itemView.setOnClickListener { r.onClick(ctl) }
        }
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.rowIcon)
        val title: TextView = v.findViewById(R.id.rowTitle)
        val value: TextView = v.findViewById(R.id.rowValue)
        val check: ImageView = v.findViewById(R.id.rowCheck)
    }
}
