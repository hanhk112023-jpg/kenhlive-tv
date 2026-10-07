package com.kenhlive.tv.phim

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.kenhlive.tv.DeviceMode
import com.kenhlive.tv.MainActivity
import com.kenhlive.tv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PhimSection(
    val title: String,
    val films: List<NguoncFilm>
)

/**
 * PhimFragment — Trung tâm Điện ảnh (VOD Cinema Hub) KenhLive TV:
 * - Thiết kế chuẩn 10-foot UI cho Android TV và màn hình ngang điện thoại
 * - Hero Spotlight Banner nổi bật ở đỉnh với nút Xem ngay và Danh sách tập
 * - Các hàng ngang Carousel phân loại: Phim Mới, Phim Bộ, Phim Lẻ, Hoạt Hình/Anime
 * - Hỗ trợ toàn diện điều khiển remote D-pad (phóng to 1.05x, viền sáng thương hiệu)
 */
class PhimFragment : Fragment() {

    private lateinit var mainList: RecyclerView
    private lateinit var loadingView: ProgressBar
    private lateinit var errorLayout: View
    private lateinit var btnRetry: Button

    private var dialog: AlertDialog? = null
    private var allFilms: List<NguoncFilm> = emptyList()
    private var heroFilm: NguoncFilm? = null
    private var sections: List<PhimSection> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_phim, container, false)
        mainList = v.findViewById(R.id.phimMainList)
        loadingView = v.findViewById(R.id.phimLoading)
        errorLayout = v.findViewById(R.id.phimErrorLayout)
        btnRetry = v.findViewById(R.id.btnPhimRetry)

        mainList.layoutManager = LinearLayoutManager(requireContext())
        mainList.itemAnimator = null

        btnRetry.setOnClickListener {
            loadFilms()
        }

        loadFilms()
        return v
    }

    private fun loadFilms() {
        loadingView.visibility = View.VISIBLE
        errorLayout.visibility = View.GONE
        mainList.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val films = withContext(Dispatchers.IO) {
                try {
                    val p1 = NguoncRepository.fetchNewFilms(requireContext(), page = 1)
                    val p2 = if (p1.isNotEmpty()) {
                        NguoncRepository.fetchNewFilms(requireContext(), page = 2)
                    } else emptyList()
                    val combined = (p1 + p2).distinctBy { it.slug }
                    if (combined.isNotEmpty()) combined
                    else NguoncRepository.loadFallbackFilms(requireContext()).map { it.film }
                } catch (_: Exception) {
                    NguoncRepository.loadFallbackFilms(requireContext()).map { it.film }
                }
            }

            loadingView.visibility = View.GONE

            if (films.isNotEmpty()) {
                allFilms = films
                heroFilm = films.firstOrNull { it.posterUrl.isNotBlank() || it.thumbUrl.isNotBlank() } ?: films.firstOrNull()

                val sMoi = films.take(24)
                val sBo = films.filter { f ->
                    f.totalEpisodes.isNotBlank() && f.totalEpisodes != "1" ||
                            f.categories.any { c -> c.contains("bộ", ignoreCase = true) }
                }.take(24)
                val sLe = films.filter { f ->
                    f.totalEpisodes == "1" || f.currentEpisode.contains("Full", ignoreCase = true) ||
                            f.categories.any { c -> c.contains("lẻ", ignoreCase = true) }
                }.take(24)
                val sAnime = films.filter { f ->
                    f.categories.any { c ->
                        c.contains("hoạt hình", ignoreCase = true) || c.contains("anime", ignoreCase = true)
                    }
                }.take(24)

                val builtSections = mutableListOf<PhimSection>()
                if (sMoi.isNotEmpty()) builtSections.add(PhimSection("🔥  PHIM MỚI CẬP NHẬT", sMoi))
                if (sBo.isNotEmpty()) builtSections.add(PhimSection("🎬  PHIM BỘ CHỌN LỌC", sBo))
                if (sLe.isNotEmpty()) builtSections.add(PhimSection("🍿  PHIM LẺ ĐẶC SẮC", sLe))
                if (sAnime.isNotEmpty()) builtSections.add(PhimSection("⚡  HOẠT HÌNH & ANIME", sAnime))

                sections = builtSections
                mainList.visibility = View.VISIBLE
                mainList.adapter = PhimMainAdapter(heroFilm, sections,
                    onFilmClick = { film -> openFilmDialog(film) },
                    onHeroPlay = { film -> openHeroPlay(film) }
                )
            } else {
                errorLayout.visibility = View.VISIBLE
            }
        }
    }

    private fun openFilmDialog(film: NguoncFilm) {
        dialog?.dismiss()
        dialog = EpisodePickerDialog.show(
            context = requireContext(),
            scope = viewLifecycleOwner.lifecycleScope,
            film = film,
            onSelectEpisode = { f, ep ->
                playEpisode(f, ep)
            }
        )
    }

    private fun openHeroPlay(film: NguoncFilm) {
        viewLifecycleOwner.lifecycleScope.launch {
            val detail = withContext(Dispatchers.IO) {
                NguoncRepository.fetchFilmDetail(film.slug, requireContext())
            }
            val firstEp = detail?.episodes?.firstOrNull()?.items?.firstOrNull()
            if (firstEp != null) {
                playEpisode(film, firstEp)
            } else {
                openFilmDialog(film)
            }
        }
    }

    private fun playEpisode(film: NguoncFilm, episode: NguoncEpisodeItem) {
        val intent = Intent(requireContext(), WebPlayerActivity::class.java)
            .putExtra("embed_url", episode.embed)
            .putExtra("film_title", film.name)
            .putExtra("episode_title", if (episode.name.all { it.isDigit() }) "Tập ${episode.name}" else episode.name)
        startActivity(intent)
        (activity as? MainActivity)?.hideKeyboard()
    }

    override fun onDestroyView() {
        dialog?.dismiss()
        dialog = null
        super.onDestroyView()
    }

    // ===== ADAPTER CHO VERTICAL RECYCLERVIEW =====

    private class PhimMainAdapter(
        private val hero: NguoncFilm?,
        private val sections: List<PhimSection>,
        private val onFilmClick: (NguoncFilm) -> Unit,
        private val onHeroPlay: (NguoncFilm) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_HERO = 0
            private const val TYPE_ROW = 1
        }

        override fun getItemViewType(position: Int): Int =
            if (position == 0 && hero != null) TYPE_HERO else TYPE_ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HERO) {
                val v = inf.inflate(R.layout.view_phim_hero, parent, false)
                HeroVH(v)
            } else {
                val v = inf.inflate(R.layout.item_phim_row, parent, false)
                RowVH(v)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is HeroVH && hero != null) {
                holder.bind(hero, onFilmClick, onHeroPlay)
            } else if (holder is RowVH) {
                val sectionIdx = if (hero != null) position - 1 else position
                if (sectionIdx in sections.indices) {
                    holder.bind(sections[sectionIdx], onFilmClick)
                }
            }
        }

        override fun getItemCount(): Int = (if (hero != null) 1 else 0) + sections.size
    }

    private class HeroVH(v: View) : RecyclerView.ViewHolder(v) {
        val backdrop: ImageView = v.findViewById(R.id.heroBackdrop)
        val title: TextView = v.findViewById(R.id.heroTitle)
        val year: TextView = v.findViewById(R.id.heroYear)
        val quality: TextView = v.findViewById(R.id.heroQuality)
        val desc: TextView = v.findViewById(R.id.heroDesc)
        val btnPlay: Button = v.findViewById(R.id.btnHeroPlay)
        val btnEpisodes: Button = v.findViewById(R.id.btnHeroEpisodes)

        fun bind(film: NguoncFilm, onFilmClick: (NguoncFilm) -> Unit, onHeroPlay: (NguoncFilm) -> Unit) {
            title.text = film.name
            year.text = film.year.ifBlank { "2026" }
            quality.text = "${film.quality} · ${film.language}"
            desc.text = film.description.ifBlank {
                "${film.originalName} — Đang chiếu trên Phim Nguồn C với chất lượng cao."
            }

            val imgUrl = film.posterUrl.ifBlank { film.thumbUrl }
            if (imgUrl.isNotBlank()) {
                backdrop.load(imgUrl) {
                    crossfade(if (DeviceMode.lowRam) false else true)
                    placeholder(R.drawable.hero_fallback)
                    error(R.drawable.hero_fallback)
                }
            }

            btnPlay.setOnClickListener { onHeroPlay(film) }
            btnEpisodes.setOnClickListener { onFilmClick(film) }

            // TV focus dynamics for buttons
            setupFocusAnimation(btnPlay)
            setupFocusAnimation(btnEpisodes)
        }

        private fun setupFocusAnimation(view: View) {
            view.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.06f else 1f).scaleY(if (hasFocus) 1.06f else 1f)
                    .setDuration(130).start()
                v.elevation = if (hasFocus) 8f else 0f
            }
        }
    }

    private class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val count: TextView = v.findViewById(R.id.rowCount)
        val carousel: RecyclerView = v.findViewById(R.id.rowCarousel)

        fun bind(section: PhimSection, onFilmClick: (NguoncFilm) -> Unit) {
            title.text = section.title
            count.text = "${section.films.size} phim"

            carousel.layoutManager = LinearLayoutManager(itemView.context, LinearLayoutManager.HORIZONTAL, false)
            carousel.adapter = PhimCardAdapter(section.films, onFilmClick)
        }
    }

    private class PhimCardAdapter(
        private val films: List<NguoncFilm>,
        private val onFilmClick: (NguoncFilm) -> Unit
    ) : RecyclerView.Adapter<PhimCardAdapter.CardVH>() {

        class CardVH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.phimPoster)
            val title: TextView = v.findViewById(R.id.phimTitle)
            val meta: TextView = v.findViewById(R.id.phimMeta)
            val qualityBadge: TextView = v.findViewById(R.id.phimQualityBadge)
            val episodeBadge: TextView = v.findViewById(R.id.phimEpisodeBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_phim_card, parent, false)
            return CardVH(v)
        }

        override fun onBindViewHolder(holder: CardVH, position: Int) {
            val film = films[position]
            holder.title.text = film.name
            val epStr = film.currentEpisode.ifBlank {
                if (film.totalEpisodes.isNotBlank()) "Tập ${film.totalEpisodes}" else "Full"
            }
            holder.episodeBadge.text = epStr
            holder.qualityBadge.text = film.quality.ifBlank { "HD" }
            holder.meta.text = "${film.year.ifBlank { "2026" }} · ${film.language}"

            val posterUrl = film.thumbUrl.ifBlank { film.posterUrl }
            holder.poster.load(posterUrl) {
                crossfade(if (DeviceMode.lowRam) false else true)
                placeholder(R.drawable.hero_fallback)
                error(R.drawable.hero_fallback)
            }

            holder.itemView.setOnClickListener {
                onFilmClick(film)
            }

            // TV focus dynamics
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.05f else 1f).scaleY(if (hasFocus) 1.05f else 1f)
                    .setDuration(140).start()
                v.elevation = if (hasFocus) 10f else 0f
            }
        }

        override fun getItemCount(): Int = films.size
    }
}
