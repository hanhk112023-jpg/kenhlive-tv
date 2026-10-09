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
    val films: List<NguoncFilm>,
    val isContinueWatching: Boolean = false
)

/**
 * PhimFragment — Trung tâm Điện ảnh (VOD Cinema Hub) KenhLive TV:
 * - Hỗ trợ kho phim Nguồn C hơn 33.400+ đầu phim với đầy đủ thể loại, quốc gia, năm.
 * - HÀNG TIẾP TỤC XEM (Continue Watching): Hiển thị tiến độ xem dở, bấm phát tiếp đúng giây.
 * - BỘ LỌC THỂ LOẠI APPLE PILLS: Tất cả, Phim mới, Phim bộ, Phim lẻ, Anime, Hành động, Tình cảm...
 * - Thiết kế tối giản chuẩn Apple TV & iOS: Thẻ kính mờ bo góc 16dp, gradient phản chiếu, thanh tiến độ mỏng.
 * - Hỗ trợ đa thiết bị: D-pad focus mượt mà trên TV, cử chỉ vuốt chạm nhanh trên Mobile.
 */
class PhimFragment : Fragment() {

    private lateinit var filterList: RecyclerView
    private lateinit var mainList: RecyclerView
    private lateinit var loadingView: ProgressBar
    private lateinit var errorLayout: View
    private lateinit var btnRetry: Button

    private var dialog: AlertDialog? = null
    private var heroFilm: NguoncFilm? = null
    private var sections: List<PhimSection> = emptyList()
    private var selectedCategorySlug: String = "tat-ca"

    // Danh sách bộ lọc Apple Pills
    private val filterCategories = listOf(
        "tat-ca" to "🔥 Tất Cả",
        "phim-moi" to "✨ Mới Nhất",
        "phim-bo" to "🎬 Phim Bộ",
        "phim-le" to "🍿 Phim Lẻ",
        "hoat-hinh" to "⚡ Anime & Cartoon",
        "hanh-dong" to "💥 Hành Động",
        "tinh-cam" to "❤️ Tình Cảm",
        "co-trang" to "👑 Cổ Trang",
        "kinh-di" to "👻 Kinh Dị",
        "hai-huoc" to "😂 Hài Hước",
        "vien-tuong" to "🚀 Viễn Tưởng",
        "vo-thuat" to "🥋 Võ Thuật"
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_phim, container, false)
        filterList = v.findViewById(R.id.phimFilterList)
        mainList = v.findViewById(R.id.phimMainList)
        loadingView = v.findViewById(R.id.phimLoading)
        errorLayout = v.findViewById(R.id.phimErrorLayout)
        btnRetry = v.findViewById(R.id.btnPhimRetry)

        filterList.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        filterList.adapter = FilterAdapter(filterCategories, selectedCategorySlug) { catSlug ->
            selectedCategorySlug = catSlug
            loadFilmsByCategory(catSlug)
        }

        mainList.layoutManager = LinearLayoutManager(requireContext())
        mainList.itemAnimator = null

        btnRetry.setOnClickListener {
            loadFilmsByCategory(selectedCategorySlug)
        }

        loadFilmsByCategory(selectedCategorySlug)
        return v
    }

    override fun onResume() {
        super.onResume()
        // Cập nhật lại hàng tiếp tục xem khi quay lại từ player
        refreshContinueWatchingOnly()
    }

    private fun refreshContinueWatchingOnly() {
        viewLifecycleOwner.lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) {
                WatchHistoryManager.getContinueWatchingList(15)
            }
            if (history.isEmpty() && sections.none { it.isContinueWatching }) return@launch

            val historyFilms = history.map { h ->
                NguoncFilm(
                    name = h.filmName,
                    slug = h.slug,
                    posterUrl = h.posterUrl,
                    thumbUrl = h.posterUrl,
                    currentEpisode = h.episodeName,
                    watchProgress = h
                )
            }

            val newSections = sections.filterNot { it.isContinueWatching }.toMutableList()
            if (historyFilms.isNotEmpty()) {
                newSections.add(0, PhimSection("⏱️  TIẾP TỤC XEM", historyFilms, isContinueWatching = true))
            }
            sections = newSections
            mainList.adapter = PhimMainAdapter(
                hero = heroFilm,
                sections = sections,
                onFilmClick = { film ->
                    if (film.watchProgress != null) {
                        playEpisode(
                            film = film,
                            episode = NguoncEpisodeItem(
                                name = film.watchProgress.episodeName,
                                slug = film.watchProgress.episodeSlug,
                                embed = film.watchProgress.embedUrl
                            ),
                            startPosMs = film.watchProgress.positionMs
                        )
                    } else {
                        openFilmDialog(film)
                    }
                },
                onHeroPlay = { film -> openHeroPlay(film) }
            )
        }
    }

    private fun loadFilmsByCategory(categorySlug: String) {
        loadingView.visibility = View.VISIBLE
        errorLayout.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val films = withContext(Dispatchers.IO) {
                try {
                    when (categorySlug) {
                        "tat-ca", "phim-moi" -> {
                            val p1 = NguoncRepository.fetchNewFilms(requireContext(), page = 1)
                            val p2 = if (p1.isNotEmpty()) {
                                NguoncRepository.fetchNewFilms(requireContext(), page = 2)
                            } else emptyList()
                            val combined = (p1 + p2).distinctBy { it.slug }
                            if (combined.isNotEmpty()) combined
                            else NguoncRepository.loadFallbackFilms(requireContext()).map { it.film }
                        }
                        "phim-bo", "phim-le", "hoat-hinh" -> {
                            val res = NguoncRepository.fetchFilmsByType(categorySlug, 1, requireContext())
                            if (res.isNotEmpty()) res
                            else NguoncRepository.loadFallbackFilms(requireContext()).map { it.film }
                        }
                        else -> {
                            val res = NguoncRepository.fetchFilmsByCategory(categorySlug, 1, requireContext())
                            if (res.isNotEmpty()) res
                            else NguoncRepository.loadFallbackFilms(requireContext()).map { it.film }
                        }
                    }
                } catch (_: Exception) {
                    NguoncRepository.loadFallbackFilms(requireContext()).map { it.film }
                }
            }

            // Lấy danh sách lịch sử tiếp tục xem
            val history = withContext(Dispatchers.IO) {
                WatchHistoryManager.getContinueWatchingList(15)
            }

            loadingView.visibility = View.GONE

            if (films.isNotEmpty()) {
                heroFilm = films.firstOrNull { it.posterUrl.isNotBlank() || it.thumbUrl.isNotBlank() } ?: films.firstOrNull()

                val builtSections = mutableListOf<PhimSection>()

                // 1. Section "Tiếp tục xem" nếu có
                if (history.isNotEmpty()) {
                    val historyFilms = history.map { h ->
                        NguoncFilm(
                            name = h.filmName,
                            slug = h.slug,
                            posterUrl = h.posterUrl,
                            thumbUrl = h.posterUrl,
                            currentEpisode = h.episodeName,
                            watchProgress = h
                        )
                    }
                    builtSections.add(PhimSection("⏱️  TIẾP TỤC XEM", historyFilms, isContinueWatching = true))
                }

                // 2. Sections nội dung theo danh mục
                if (categorySlug == "tat-ca" || categorySlug == "phim-moi") {
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

                    if (sMoi.isNotEmpty()) builtSections.add(PhimSection("🔥  PHIM MỚI CẬP NHẬT", sMoi))
                    if (sBo.isNotEmpty()) builtSections.add(PhimSection("🎬  PHIM BỘ CHỌN LỌC", sBo))
                    if (sLe.isNotEmpty()) builtSections.add(PhimSection("🍿  PHIM LẺ ĐẶC SẮC", sLe))
                    if (sAnime.isNotEmpty()) builtSections.add(PhimSection("⚡  HOẠT HÌNH & ANIME", sAnime))
                } else {
                    val catTitle = filterCategories.firstOrNull { it.first == categorySlug }?.second ?: "DANH SÁCH PHIM"
                    builtSections.add(PhimSection("🎥  $catTitle", films))
                }

                sections = builtSections
                mainList.visibility = View.VISIBLE
                mainList.adapter = PhimMainAdapter(
                    hero = heroFilm,
                    sections = sections,
                    onFilmClick = { film ->
                        if (film.watchProgress != null) {
                            playEpisode(
                                film = film,
                                episode = NguoncEpisodeItem(
                                    name = film.watchProgress.episodeName,
                                    slug = film.watchProgress.episodeSlug,
                                    embed = film.watchProgress.embedUrl
                                ),
                                startPosMs = film.watchProgress.positionMs
                            )
                        } else {
                            openFilmDialog(film)
                        }
                    },
                    onHeroPlay = { film -> openHeroPlay(film) }
                )
            } else {
                mainList.visibility = View.GONE
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
            onSelectEpisode = { f, ep, startPos ->
                playEpisode(f, ep, startPos)
            }
        )
    }

    private fun openHeroPlay(film: NguoncFilm) {
        viewLifecycleOwner.lifecycleScope.launch {
            val progress = withContext(Dispatchers.IO) {
                WatchHistoryManager.getProgressForFilm(film.slug)
            }
            if (progress != null) {
                playEpisode(
                    film = film,
                    episode = NguoncEpisodeItem(
                        name = progress.episodeName,
                        slug = progress.episodeSlug,
                        embed = progress.embedUrl
                    ),
                    startPosMs = progress.positionMs
                )
                return@launch
            }

            val detail = withContext(Dispatchers.IO) {
                NguoncRepository.fetchFilmDetail(film.slug, requireContext())
            }
            val firstEp = detail?.episodes?.firstOrNull()?.items?.firstOrNull()
            if (firstEp != null) {
                playEpisode(film, firstEp, 0L)
            } else {
                openFilmDialog(film)
            }
        }
    }

    private fun playEpisode(film: NguoncFilm, episode: NguoncEpisodeItem, startPosMs: Long = 0L) {
        val intent = Intent(requireContext(), WebPlayerActivity::class.java)
            .putExtra("embed_url", episode.embed)
            .putExtra("film_slug", film.slug)
            .putExtra("film_title", film.name)
            .putExtra("episode_slug", episode.slug)
            .putExtra("episode_title", if (episode.name.all { it.isDigit() }) "Tập ${episode.name}" else episode.name)
            .putExtra("poster_url", film.posterUrl.ifBlank { film.thumbUrl })
            .putExtra("start_position_ms", startPosMs)
        startActivity(intent)
        (activity as? MainActivity)?.hideKeyboard()
    }

    override fun onDestroyView() {
        dialog?.dismiss()
        dialog = null
        super.onDestroyView()
    }

    // ===== ADAPTER CHO APPLE FILTER PILLS =====

    private class FilterAdapter(
        private val categories: List<Pair<String, String>>,
        private var selectedSlug: String,
        private val onSelect: (String) -> Unit
    ) : RecyclerView.Adapter<FilterAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvFilterTitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_phim_filter_pill, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (slug, title) = categories[position]
            holder.tv.text = title
            val isSelected = slug == selectedSlug

            if (isSelected) {
                holder.tv.setBackgroundResource(R.drawable.bg_apple_pill_focused)
                holder.tv.setTextColor(0xFF000000.toInt())
            } else {
                holder.tv.setBackgroundResource(R.drawable.sl_apple_pill)
                holder.tv.setTextColor(0xFFFFFFFF.toInt())
            }

            holder.itemView.setOnClickListener {
                val oldSelected = selectedSlug
                selectedSlug = slug
                notifyDataSetChanged()
                onSelect(slug)
            }

            // TV focus animation
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.08f else 1f).scaleY(if (hasFocus) 1.08f else 1f)
                    .setDuration(120).start()
            }
        }

        override fun getItemCount(): Int = categories.size
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
            val progressBar: ProgressBar = v.findViewById(R.id.phimProgressBar)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_phim_card, parent, false)
            return CardVH(v)
        }

        override fun onBindViewHolder(holder: CardVH, position: Int) {
            val film = films[position]
            holder.title.text = film.name

            // Xử lý hiển thị tiến độ xem (Continue Watching)
            val progress = film.watchProgress
            if (progress != null && progress.progressPct > 0) {
                holder.progressBar.visibility = View.VISIBLE
                holder.progressBar.progress = progress.progressPct
                holder.episodeBadge.text = "${progress.episodeName} (${progress.progressPct}%)"
                holder.meta.text = progress.formattedProgress()
            } else {
                holder.progressBar.visibility = View.GONE
                val epStr = film.currentEpisode.ifBlank {
                    if (film.totalEpisodes.isNotBlank()) "Tập ${film.totalEpisodes}" else "Full"
                }
                holder.episodeBadge.text = epStr
                holder.meta.text = "${film.year.ifBlank { "2026" }} · ${film.language}"
            }

            holder.qualityBadge.text = film.quality.ifBlank { "HD" }

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
