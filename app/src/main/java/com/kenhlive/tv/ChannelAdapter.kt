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
import coil.request.CachePolicy

class ChannelAdapter(
    private val gridMode: Boolean,
    private val onClick: (Channel) -> Unit
) : ListAdapter<Channel, ChannelAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Channel>() {
            override fun areItemsTheSame(a: Channel, b: Channel) = a.url == b.url
            override fun areContentsTheSame(a: Channel, b: Channel) = a == b
        }
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.channelLogo)
        val name: TextView = v.findViewById(R.id.channelName)
        val group: TextView = v.findViewById(R.id.channelGroup)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (gridMode) R.layout.item_channel_grid else R.layout.item_channel
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val c = getItem(pos)
        h.name.text = c.name
        h.group.text = c.group
        if (c.logo.isNotBlank()) {
            h.logo.load(c.logo) {
                crossfade(80)
                memoryCachePolicy(CachePolicy.ENABLED)
                placeholder(R.drawable.logo_placeholder)
                error(R.drawable.logo_placeholder)
            }
        } else {
            h.logo.setImageResource(R.drawable.logo_placeholder)
        }
        h.itemView.setOnClickListener { onClick(c) }
    }
}
