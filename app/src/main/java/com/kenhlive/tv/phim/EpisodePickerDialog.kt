package com.kenhlive.tv.phim

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.kenhlive.tv.DeviceMode
import com.kenhlive.tv.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * EpisodePickerDialog — Hộp thoại chọn tập phim Nguồn C chuẩn Android TV & Mobile:
 * - Hiển thị poster, thông tin phim, năm phát hành, chất lượng
 * - Tự động tải danh sách tập phim từ API hoặc Fallback cache
 * - Tích hợp xem tiếp: Nhận diện tập đang xem dở và đánh dấu các tập đã xem
 * - Lưới tập phim trực quan, điều hướng D-pad remote TV mượt mà
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
        val rvEpisodes = view.findViewById<RecyclerView>(R.id.dialogEpisodesGrid)
        val tvNoEpisodes = view.findViewById<TextView>(R.id.dialogNoEpisodes)
        val btnClose = view.findViewById<Button>(R.id.dialogBtnClose)
        val btnFavorite = view.findViewById<Button>(R.id.dialogBtnFavorite)

        scope.launch {
            val isFav = withContext(Dispatchers.IO) { WatchHistoryManager.isFavorite(film.slug) }
            val progress = withContext(Dispatchers.IO) { WatchHistoryManager.getProgressForFilm(film.slug) }
            val epSuffix = if (progress != null) " (${progress.episodeName})" else ""
            btnFavorite.text = if (isFav) "Đã Lưu$epSuffix" else "Lưu Phim"
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
                        embedUrl = progress?.embedUrl
                    )
                }
                val epSuffix = if (progress != null) " (${progress.episodeName})" else ""
                btnFavorite.text = if (nowFav) "Đã Lưu$epSuffix" else "Lưu Phim"
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

        val posterUrl = film.thumbUrl.ifBlank { film.posterUrl }
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

        scope.launch {
            loading.visibility = View.VISIBLE
            rvEpisodes.visibility = View.GONE
            tvNoEpisodes.visibility = View.GONE

            val detail = withContext(Dispatchers.IO) {
                NguoncRepository.fetchFilmDetail(film.slug, context)
            }
            val progress = withContext(Dispatchers.IO) {
                WatchHistoryManager.getProgressForFilm(film.slug)
            }

            loading.visibility = View.GONE
            val allEpisodes = detail?.episodes?.flatMap { it.items } ?: emptyList()

            if (allEpisodes.isNotEmpty()) {
                val watchedSlugs = withContext(Dispatchers.IO) {
                    allEpisodes.filter { ep ->
                        WatchHistoryManager.isEpisodeWatched(film.slug, ep.slug)
                    }.map { it.slug }.toSet()
                }

                rvEpisodes.visibility = View.VISIBLE
                val adapter = EpisodeAdapter(
                    items = allEpisodes,
                    currentEpSlug = progress?.episodeSlug,
                    watchedEpSlugs = watchedSlugs,
                    onClick = { item ->
                        dialog.dismiss()
                        val startPos = if (item.slug == progress?.episodeSlug) progress.positionMs else 0L
                        onSelectEpisode(film, item, startPos)
                    },
                    onBookmark = { item ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                WatchHistoryManager.saveProgress(
                                    slug = film.slug,
                                    filmName = film.name,
                                    posterUrl = film.posterUrl.ifBlank { film.thumbUrl },
                                    episodeName = item.name,
                                    episodeSlug = item.slug,
                                    embedUrl = item.embed,
                                    positionMs = 1000L,
                                    durationMs = 0L
                                )
                                if (!WatchHistoryManager.isFavorite(film.slug)) {
                                    WatchHistoryManager.toggleFavorite(film, item.name, item.slug, item.embed)
                                }
                            }
                            withContext(Dispatchers.Main) {
                                btnFavorite.text = "Đã Lưu (${item.name})"
                                btnFavorite.setCompoundDrawablesWithIntrinsicBounds(
                                    R.drawable.ic_bookmark_filled, 0, 0, 0
                                )
                                android.widget.Toast.makeText(
                                    context,
                                    "Đã lưu \"${film.name}\" tại ${item.name}!",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                )
                rvEpisodes.adapter = adapter
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

    private class EpisodeAdapter(
        private val items: List<NguoncEpisodeItem>,
        private var currentEpSlug: String?,
        private val watchedEpSlugs: Set<String>,
        private val onClick: (NguoncEpisodeItem) -> Unit,
        private val onBookmark: (NguoncEpisodeItem) -> Unit
    ) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

        fun updateCurrent(slug: String) {
            currentEpSlug = slug
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

            holder.tv.text = baseName

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
                holder.tv.setTextColor(0xFFFF9F0A.toInt()) // Apple Orange highlight
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
        }

        override fun getItemCount(): Int = items.size
    }
}
