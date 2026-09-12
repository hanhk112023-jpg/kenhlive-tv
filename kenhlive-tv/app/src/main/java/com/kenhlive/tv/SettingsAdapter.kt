package com.kenhlive.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Danh sách cài đặt: section header + row focusable (D-pad OK). */
class SettingsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Item {
        data class Section(val title: String) : Item()
        data class Row(
            val icon: Int,
            val title: String,
            val desc: String? = null,
            val value: String? = null,
            val chevron: Boolean = true,
            val action: () -> Unit = {}
        ) : Item()
    }

    private val items = mutableListOf<Item>()

    fun submit(list: List<Item>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class SectionVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.sectionTitle)
    }

    class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.rowIcon)
        val title: TextView = v.findViewById(R.id.rowTitle)
        val desc: TextView = v.findViewById(R.id.rowDesc)
        val value: TextView = v.findViewById(R.id.rowValue)
        val chevron: ImageView = v.findViewById(R.id.rowChevron)
    }

    override fun getItemViewType(pos: Int) = if (items[pos] is Item.Section) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) SectionVH(inf.inflate(R.layout.item_settings_section, parent, false))
        else RowVH(inf.inflate(R.layout.item_settings_row, parent, false))
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        when (val item = items[pos]) {
            is Item.Section -> (h as SectionVH).title.text = item.title
            is Item.Row -> {
                val vh = h as RowVH
                vh.icon.setImageResource(item.icon)
                vh.title.text = item.title
                if (item.desc.isNullOrBlank()) vh.desc.visibility = View.GONE
                else { vh.desc.visibility = View.VISIBLE; vh.desc.text = item.desc }
                if (item.value.isNullOrBlank()) vh.value.visibility = View.GONE
                else { vh.value.visibility = View.VISIBLE; vh.value.text = item.value }
                vh.chevron.visibility = if (item.chevron) View.VISIBLE else View.GONE
                vh.itemView.setOnFocusChangeListener { v, has ->
                    v.animate().scaleX(if (has) 1.01f else 1f).scaleY(if (has) 1.01f else 1f)
                        .setDuration(120).start()
                    v.elevation = if (has) 8f else 0f
                }
                vh.itemView.setOnClickListener { item.action() }
            }
        }
    }
}
