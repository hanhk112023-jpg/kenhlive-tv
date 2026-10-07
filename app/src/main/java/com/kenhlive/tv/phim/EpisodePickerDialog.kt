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
 * - Lưới tập phim trực quan, điều hướng D-pad remote TV mượt mà
 */
object EpisodePickerDialog {

    fun show(
        context: Context,
        scope: CoroutineScope,
        film: NguoncFilm,
        onSelectEpisode: (film: NguoncFilm, episode: NguoncEpisodeItem) -> Unit
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

            loading.visibility = View.GONE
            val allEpisodes = detail?.episodes?.flatMap { it.items } ?: emptyList()

            if (allEpisodes.isNotEmpty()) {
                rvEpisodes.visibility = View.VISIBLE
                rvEpisodes.adapter = EpisodeAdapter(allEpisodes) { item ->
                    dialog.dismiss()
                    onSelectEpisode(film, item)
                }
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
        private val onClick: (NguoncEpisodeItem) -> Unit
    ) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvEpisodeName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_episode_chip, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val displayName = if (item.name.all { it.isDigit() }) "Tập ${item.name}" else item.name
            holder.tv.text = displayName
            holder.tv.setOnClickListener {
                onClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
