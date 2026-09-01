package com.socolive.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class ChannelAdapter(
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private var items = listOf<Channel>()

    fun setData(list: List<Channel>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.channelLogo)
        val name: TextView = v.findViewById(R.id.channelName)
        val group: TextView = v.findViewById(R.id.channelGroup)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val c = items[pos]
        h.name.text = c.name
        h.group.text = c.group
        Glide.with(h.logo).load(c.logo).placeholder(R.drawable.banner).into(h.logo)
        h.itemView.setOnClickListener { onClick(c) }
    }

    override fun getItemCount() = items.size
}
