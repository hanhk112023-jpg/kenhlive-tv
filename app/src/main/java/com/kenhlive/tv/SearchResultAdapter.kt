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
import com.kenhlive.tv.phim.NguoncFilm
import com.kenhlive.tv.viewmodel.SearchItem

/**
 * Kết quả tìm kiếm đa nguồn (Trận đấu Thể Thao Live & Phim Nguồn C).
 * DiffUtil giữ view + focus khi query đổi, tối ưu cho D-pad Android TV.
 */
class SearchResultAdapter(
    private val onMatchClick: (LiveMatchGroup) -> Unit,
    private val onFilmClick: (NguoncFilm) -> Unit
) : ListAdapter<SearchItem, SearchResultAdapter.VH>(DIFF) {

    var onItemFocused: ((Int) -> Unit)? = null
    var onUpFromFirst: (() -> Unit)? = null

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SearchItem>() {
            override fun areItemsTheSame(a: SearchItem, b: SearchItem): Boolean = when {
                a is SearchItem.Match && b is SearchItem.Match ->
                    a.group.league == b.group.league && a.group.matchTitle == b.group.matchTitle
                a is SearchItem.Film && b is SearchItem.Film ->
                    a.film.slug == b.film.slug
                else -> false
            }

            override fun areContentsTheSame(a: SearchItem, b: SearchItem): Boolean = when {
                a is SearchItem.Match && b is SearchItem.Match ->
                    a.group.count == b.group.count && a.group.totalViewers == b.group.totalViewers && a.group.top.blvName == b.group.top.blvName
                a is SearchItem.Film && b is SearchItem.Film ->
                    a.film.name == b.film.name && a.film.currentEpisode == b.film.currentEpisode
                else -> false
            }
        }
    }

    class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.srAvatar)
        val match: TextView = v.findViewById(R.id.srMatch)
        val meta: TextView = v.findViewById(R.id.srMeta)
        val league: TextView = v.findViewById(R.id.srLeague)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = currentList[pos]
        val ctx = h.itemView.context

        when (item) {
            is SearchItem.Match -> {
                val g = item.group
                h.match.text = g.matchTitle
                h.meta.text = "${g.top.blvName}${if (g.count > 1) " ${ctx.getString(R.string.live_blv_more, g.count - 1)}" else ""}" +
                    " · ${SocoliveRepository.fmtViewers(g.totalViewers)} · ${ctx.getString(R.string.live_rooms_badge, g.count)}"
                h.league.text = g.league
                h.avatar.load(g.top.avatar) {
                    crossfade(if (DeviceMode.lowRam) 0 else 80)
                    transformations(CircleCropTransformation())
                    placeholder(R.drawable.logo_placeholder)
                    error(R.drawable.logo_placeholder)
                }
                h.itemView.setOnClickListener { onMatchClick(g) }
                h.itemView.contentDescription = g.matchTitle
            }
            is SearchItem.Film -> {
                val film = item.film
                h.match.text = film.name
                val epStr = film.currentEpisode.ifBlank {
                    if (film.totalEpisodes.isNotBlank()) "Tổng ${film.totalEpisodes} tập" else "Trọn bộ"
                }
                h.meta.text = "${film.year.ifBlank { "2026" }} · ${film.quality} · $epStr${if (film.originalName.isNotBlank()) " · ${film.originalName}" else ""}"
                h.league.text = "PHIM NGUỒN C"
                val posterUrl = film.thumbUrl.ifBlank { film.posterUrl }
                h.avatar.load(posterUrl) {
                    crossfade(if (DeviceMode.lowRam) 0 else 80)
                    transformations(RoundedCornersTransformation(12f))
                    placeholder(R.drawable.hero_fallback)
                    error(R.drawable.hero_fallback)
                }
                h.itemView.setOnClickListener { onFilmClick(film) }
                h.itemView.contentDescription = film.name
            }
        }

        h.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (pos >= itemCount - 1) {
                        return@setOnKeyListener true // nuốt ở cuối danh sách, giữ focus trong list
                    }
                    val rv = h.itemView.parent as? RecyclerView
                    if (rv != null) {
                        val nextView = rv.layoutManager?.findViewByPosition(pos + 1)
                            ?: rv.findViewHolderForAdapterPosition(pos + 1)?.itemView
                        if (nextView != null && nextView.requestFocus()) return@setOnKeyListener true
                        rv.scrollToPosition(pos + 1)
                        rv.post {
                            val v = rv.layoutManager?.findViewByPosition(pos + 1)
                                ?: rv.findViewHolderForAdapterPosition(pos + 1)?.itemView
                            if (v?.requestFocus() != true) {
                                rv.postDelayed({
                                    (rv.layoutManager?.findViewByPosition(pos + 1)
                                        ?: rv.findViewHolderForAdapterPosition(pos + 1)?.itemView)?.requestFocus()
                                }, 40)
                            }
                        }
                        return@setOnKeyListener true
                    }
                }
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                    if (pos == 0) {
                        onUpFromFirst?.invoke()
                        return@setOnKeyListener true
                    }
                    val rv = h.itemView.parent as? RecyclerView
                    if (rv != null) {
                        val prevView = rv.layoutManager?.findViewByPosition(pos - 1)
                            ?: rv.findViewHolderForAdapterPosition(pos - 1)?.itemView
                        if (prevView != null && prevView.requestFocus()) return@setOnKeyListener true
                        rv.scrollToPosition(pos - 1)
                        rv.post {
                            val v = rv.layoutManager?.findViewByPosition(pos - 1)
                                ?: rv.findViewHolderForAdapterPosition(pos - 1)?.itemView
                            if (v?.requestFocus() != true) {
                                rv.postDelayed({
                                    (rv.layoutManager?.findViewByPosition(pos - 1)
                                        ?: rv.findViewHolderForAdapterPosition(pos - 1)?.itemView)?.requestFocus()
                                }, 40)
                            }
                        }
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
        h.itemView.setOnFocusChangeListener { v, has ->
            if (has) {
                onItemFocused?.invoke(pos)
            }
            v.animate().cancel()
            v.animate().scaleX(if (has) 1.02f else 1f).scaleY(if (has) 1.02f else 1f)
                .setDuration(130).start()
            v.elevation = if (has) 10f else 0f
        }
    }
}
