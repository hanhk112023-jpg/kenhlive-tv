package com.kenhlive.tv.phim

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.kenhlive.tv.DeviceMode
import com.kenhlive.tv.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * EpisodePickerDialog Pro — Hộp thoại chọn Server & Tập phim chuẩn Android TV & Mobile:
 * - Hỗ trợ đa server: Chuyển đổi mượt mà giữa Vietsub, Thuyết Minh, Lồng Tiếng.
 * - Phân đoạn tập thông minh (Chunking): Chia cụm 30 tập (1-30, 31-60...) cho các bộ phim dài tập.
 * - Hiển thị nhãn phát sóng HLS 4K Native, đánh dấu tập đang xem & tập đã xem.
 * - Tối ưu điều hướng D-pad Remote TV: Server -> Cụm tập -> Lưới tập phim.
 */
object EpisodePickerDialog {

    fun show(
        context: Context,
        scope: CoroutineScope,
        film: NguoncFilm,
        onSelectEpisode: (film: NguoncFilm, episode: NguoncEpisodeItem) -> Unit
    ): AlertDialog {
        return show(context, scope, film) { f, ep, _ ->
            onSelectEpisode(f, ep)
        }
    }

    fun show(
        context: Context,
        scope: CoroutineScope,
        film: NguoncFilm,
        onSelectEpisode: (film: NguoncFilm, episode: NguoncEpisodeItem, startPosMs: Long) -> Unit
    ): AlertDialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_film_episodes, null)
        val ivPoster = view.findViewById<ImageView>(R.id.dialogFilmPoster)
        val tvTitle = view.findViewById<TextView>(R.id.dialogFilmTitle)
        val tvOrigTitle = view.findViewById<TextView>(R.id.dialogFilmOrigTitle)
        val tvYear = view.findViewById<TextView>(R.id.dialogFilmYear)
        val tvQuality = view.findViewById<TextView>(R.id.dialogFilmQuality)
        val tvEpisodesCount = view.findViewById<TextView>(R.id.dialogFilmEpisodesCount)
        val tvDesc = view.findViewById<TextView>(R.id.dialogFilmDesc)
        val loading = view.findViewById<ProgressBar>(R.id.dialogLoading)
        val rvServers = view.findViewById<RecyclerView>(R.id.dialogServersRow)
        val rvChunks = view.findViewById<RecyclerView>(R.id.dialogChunksRow)
        val rvEpisodes = view.findViewById<RecyclerView>(R.id.dialogEpisodesGrid)
        val tvNoEpisodes = view.findViewById<TextView>(R.id.dialogNoEpisodes)
        val btnClose = view.findViewById<Button>(R.id.dialogBtnClose)
        val btnFavorite = view.findViewById<Button>(R.id.dialogBtnFavorite)

        scope.launch {
            val isFav = withContext(Dispatchers.IO) { WatchHistoryManager.isFavorite(film.slug) }
            val progress = withContext(Dispatchers.IO) { WatchHistoryManager.getProgressForFilm(film.slug) }
            val timePart = progress?.formattedTimeOnly()?.let { if (it.isNotBlank()) " · $it" else "" } ?: ""
            val epSuffix = if (progress != null) " (${progress.episodeName}$timePart)" else ""
            btnFavorite.text = if (isFav) "Đã Lưu$epSuffix" else "Lưu Phim$epSuffix"
            btnFavorite.setCompoundDrawablesWithIntrinsicBounds(
                if (isFav) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark, 0, 0, 0
            )
        }

        btnFavorite.setOnClickListener {
            scope.launch {
                val progress = withContext(Dispatchers.IO) { WatchHistoryManager.getProgressForFilm(film.slug) }
                val nowFav = withContext(Dispatchers.IO) {
                    WatchHistoryManager.toggleFavorite(
                        film = film,
                        episodeName = progress?.episodeName,
                        episodeSlug = progress?.episodeSlug,
                        embedUrl = progress?.embedUrl,
                        positionMs = progress?.positionMs,
                        durationMs = progress?.durationMs
                    )
                }
                val timePart = progress?.formattedTimeOnly()?.let { if (it.isNotBlank()) " · $it" else "" } ?: ""
                val epSuffix = if (progress != null) " (${progress.episodeName}$timePart)" else ""
                btnFavorite.text = if (nowFav) "Đã Lưu$epSuffix" else "Lưu Phim$epSuffix"
                btnFavorite.setCompoundDrawablesWithIntrinsicBounds(
                    if (nowFav) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark, 0, 0, 0
                )
                android.widget.Toast.makeText(
                    context,
                    if (nowFav) "Đã lưu bộ phim \"${film.name}\"$epSuffix vào Lịch Sử Xem" else "Đã bỏ lưu \"${film.name}\"",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        tvTitle.text = film.name
        tvOrigTitle.text = film.originalName.ifBlank { film.categories.joinToString(" · ") }
        tvYear.text = film.year.ifBlank { "2026" }
        tvQuality.text = "${film.quality} · ${film.language}"
        tvEpisodesCount.text = film.currentEpisode.ifBlank {
            if (film.totalEpisodes.isNotBlank()) "Tổng ${film.totalEpisodes} tập" else "Trọn bộ"
        }
        tvDesc.text = film.description.ifBlank { "Không có mô tả chi tiết." }

        val posterUrl = film.posterUrl.ifBlank { film.thumbUrl }
        if (posterUrl.isNotBlank()) {
            ivPoster.load(posterUrl) {
                crossfade(false)
                placeholder(R.drawable.hero_fallback)
                error(R.drawable.hero_fallback)
            }
        }

        val dialog = AlertDialog.Builder(context, R.style.Theme_KenhLive_Dialog)
            .setView(view)
            .create()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        val spanCount = if (DeviceMode.isTv) 5 else 3
        rvEpisodes.layoutManager = GridLayoutManager(context, spanCount)

        rvServers.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        rvChunks.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)

        scope.launch {
            loading.visibility = View.VISIBLE
            rvServers.visibility = View.GONE
            rvChunks.visibility = View.GONE
            rvEpisodes.visibility = View.GONE
            tvNoEpisodes.visibility = View.GONE

            val detail = withContext(Dispatchers.IO) {
                NguoncRepository.fetchFilmDetail(film.slug, context)
            }
            val progress = withContext(Dispatchers.IO) {
                WatchHistoryManager.getProgressForFilm(film.slug)
            }

            loading.visibility = View.GONE
            val serverList = detail?.episodes?.filter { it.items.isNotEmpty() } ?: emptyList()

            if (serverList.isNotEmpty()) {
                var selectedServerIndex = 0
                // Nếu tập đang xem dở thuộc server nào, ưu tiên chọn server đó
                progress?.episodeSlug?.let { curSlug ->
                    val foundIdx = serverList.indexOfFirst { srv -> srv.items.any { it.slug == curSlug } }
                    if (foundIdx >= 0) selectedServerIndex = foundIdx
                }

                fun renderChunksAndEpisodes() {
                    val currentServer = serverList.getOrNull(selectedServerIndex) ?: return
                    val serverItems = currentServer.items
                    val chunkSize = 30
                    val totalChunks = if (serverItems.size > chunkSize) (serverItems.size + chunkSize - 1) / chunkSize else 1

                    var selectedChunkIndex = 0
                    progress?.episodeSlug?.let { curSlug ->
                        val epIdx = serverItems.indexOfFirst { it.slug == curSlug }
                        if (epIdx >= 0) selectedChunkIndex = epIdx / chunkSize
                    }

                    fun updateEpisodesForChunk(chunkIdx: Int) {
                        val start = chunkIdx * chunkSize
                        val end = (start + chunkSize).coerceAtMost(serverItems.size)
                        val chunkItems = serverItems.subList(start, end)

                        scope.launch {
                            val watchedSlugs = withContext(Dispatchers.IO) {
                                chunkItems.filter { ep ->
                                    WatchHistoryManager.isEpisodeWatched(film.slug, ep.slug)
                                }.map { it.slug }.toSet()
                            }

                            rvEpisodes.visibility = View.VISIBLE
                            val adapter = EpisodeAdapter(
                                items = chunkItems,
                                currentEpSlug = progress?.episodeSlug,
                                watchedEpSlugs = watchedSlugs,
                                currentEpTimeLabel = progress?.formattedTimeOnly().orEmpty(),
                                onClick = { item ->
                                    dialog.dismiss()
                                    scope.launch {
                                        val epProgress = withContext(Dispatchers.IO) {
                                            WatchHistoryManager.getProgressForEpisode(film.slug, item.slug)
                                                ?: WatchHistoryManager.getProgressForFilm(film.slug)?.takeIf { it.episodeSlug == item.slug }
                                        }
                                        val startPos = epProgress?.positionMs
                                            ?: if (item.slug == progress?.episodeSlug) (progress?.positionMs ?: 0L) else 0L
                                        withContext(Dispatchers.Main) {
                                            onSelectEpisode(film, item, startPos)
                                        }
                                    }
                                },
                                onBookmark = { item ->
                                    scope.launch {
                                        val existing = withContext(Dispatchers.IO) {
                                            WatchHistoryManager.getProgressForEpisode(film.slug, item.slug)
                                                ?: WatchHistoryManager.getProgressForFilm(film.slug)?.takeIf { it.episodeSlug == item.slug }
                                        }
                                        val pos = existing?.positionMs ?: 0L
                                        val dur = existing?.durationMs ?: 0L
                                        val m3u8Url = if (item.m3u8.isNotBlank()) item.m3u8 else item.embed
                                        withContext(Dispatchers.IO) {
                                            WatchHistoryManager.saveProgress(
                                                slug = film.slug,
                                                filmName = film.name,
                                                posterUrl = film.posterUrl.ifBlank { film.thumbUrl },
                                                episodeName = item.name,
                                                episodeSlug = item.slug,
                                                embedUrl = m3u8Url,
                                                positionMs = pos,
                                                durationMs = dur
                                            )
                                            if (!WatchHistoryManager.isFavorite(film.slug)) {
                                                WatchHistoryManager.toggleFavorite(
                                                    film = film,
                                                    episodeName = item.name,
                                                    episodeSlug = item.slug,
                                                    embedUrl = m3u8Url,
                                                    positionMs = pos,
                                                    durationMs = dur
                                                )
                                            }
                                        }
                                        withContext(Dispatchers.Main) {
                                            val timeStr = if (pos > 0L) {
                                                val m = pos / 60000L; val s = (pos % 60000L) / 1000L
                                                String.format(java.util.Locale.US, " · %02d:%02d", m, s)
                                            } else ""
                                            btnFavorite.text = "Đã Lưu (${item.name}$timeStr)"
                                            btnFavorite.setCompoundDrawablesWithIntrinsicBounds(
                                                R.drawable.ic_bookmark_filled, 0, 0, 0
                                            )
                                            android.widget.Toast.makeText(
                                                context,
                                                "Đã lưu \"${film.name}\" tại ${item.name}$timeStr!",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            )
                            rvEpisodes.adapter = adapter
                        }
                    }

                    if (totalChunks > 1) {
                        rvChunks.visibility = View.VISIBLE
                        val chunkLabels = (0 until totalChunks).map { c ->
                            val s = c * chunkSize + 1
                            val e = ((c + 1) * chunkSize).coerceAtMost(serverItems.size)
                            "Tập $s - $e"
                        }
                        rvChunks.adapter = ChipSelectorAdapter(
                            labels = chunkLabels,
                            selectedIndex = selectedChunkIndex,
                            onSelect = { idx ->
                                selectedChunkIndex = idx
                                updateEpisodesForChunk(idx)
                            }
                        )
                    } else {
                        rvChunks.visibility = View.GONE
                    }

                    updateEpisodesForChunk(selectedChunkIndex)
                }

                if (serverList.size > 1) {
                    rvServers.visibility = View.VISIBLE
                    val serverNames = serverList.map { srv ->
                        val clean = srv.serverName.replace("#", "").trim()
                        val count = srv.items.size
                        "$clean ($count tập)"
                    }
                    rvServers.adapter = ChipSelectorAdapter(
                        labels = serverNames,
                        selectedIndex = selectedServerIndex,
                        onSelect = { idx ->
                            selectedServerIndex = idx
                            renderChunksAndEpisodes()
                        }
                    )
                } else {
                    rvServers.visibility = View.GONE
                }

                renderChunksAndEpisodes()

                if (DeviceMode.isTv) {
                    rvEpisodes.post {
                        rvEpisodes.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    }
                }
            } else {
                tvNoEpisodes.visibility = View.VISIBLE
            }
        }

        dialog.show()
        return dialog
    }

    private class ChipSelectorAdapter(
        private val labels: List<String>,
        private var selectedIndex: Int,
        private val onSelect: (Int) -> Unit
    ) : RecyclerView.Adapter<ChipSelectorAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvEpisodeName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_episode_chip, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val label = labels[position]
            val isSel = position == selectedIndex
            holder.tv.text = label

            if (isSel) {
                holder.tv.setBackgroundResource(R.drawable.bg_badge_hd)
                holder.tv.setTextColor(Color.WHITE)
            } else {
                holder.tv.setBackgroundResource(R.drawable.bg_badge_glass)
                holder.tv.setTextColor(0xFFCBD5E1.toInt())
            }

            holder.itemView.setOnClickListener {
                if (selectedIndex != position) {
                    val prev = selectedIndex
                    selectedIndex = position
                    notifyItemChanged(prev)
                    notifyItemChanged(position)
                    onSelect(position)
                }
            }

            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.08f else 1.0f).scaleY(if (hasFocus) 1.08f else 1.0f).setDuration(100).start()
                if (hasFocus) {
                    holder.tv.setTextColor(0xFFFF334B.toInt())
                } else if (!isSel) {
                    holder.tv.setTextColor(0xFFCBD5E1.toInt())
                }
            }
        }

        override fun getItemCount(): Int = labels.size
    }

    private class EpisodeAdapter(
        private val items: List<NguoncEpisodeItem>,
        private var currentEpSlug: String?,
        private val watchedEpSlugs: Set<String>,
        private var currentEpTimeLabel: String = "",
        private val onClick: (NguoncEpisodeItem) -> Unit,
        private val onBookmark: (NguoncEpisodeItem) -> Unit
    ) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

        fun updateCurrent(slug: String, timeLabel: String = "") {
            currentEpSlug = slug
            if (timeLabel.isNotBlank()) currentEpTimeLabel = timeLabel
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvEpisodeName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_episode_chip, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val baseName = if (item.name.all { it.isDigit() }) "Tập ${item.name}" else item.name

            val isCurrent = item.slug == currentEpSlug
            val isWatched = watchedEpSlugs.contains(item.slug)

            holder.tv.text = if (isCurrent && currentEpTimeLabel.isNotBlank()) {
                "$baseName ($currentEpTimeLabel)"
            } else {
                baseName
            }

            val iconRes = when {
                isCurrent -> R.drawable.ic_play_arrow
                isWatched -> R.drawable.ic_check
                else -> 0
            }
            if (iconRes != 0) {
                holder.tv.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
                holder.tv.compoundDrawablePadding = 6
            } else {
                holder.tv.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            }

            if (isCurrent) {
                holder.tv.setTextColor(0xFFFF334B.toInt()) // Crimson Red active
            } else if (isWatched) {
                holder.tv.setTextColor(0xFF3DDC97.toInt()) // Green checked
            } else {
                holder.tv.setTextColor(0xFFE2E8F0.toInt())
            }

            holder.tv.setOnClickListener {
                onClick(item)
            }

            holder.tv.setOnLongClickListener {
                updateCurrent(item.slug)
                onBookmark(item)
                true
            }

            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.08f else 1.0f).scaleY(if (hasFocus) 1.08f else 1.0f).setDuration(100).start()
                if (hasFocus) {
                    holder.tv.setTextColor(Color.WHITE)
                } else if (isCurrent) {
                    holder.tv.setTextColor(0xFFFF334B.toInt())
                } else if (isWatched) {
                    holder.tv.setTextColor(0xFF3DDC97.toInt())
                } else {
                    holder.tv.setTextColor(0xFFE2E8F0.toInt())
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
