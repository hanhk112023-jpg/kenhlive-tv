package com.kenhlive.tv.phim

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    private lateinit var ivEmptyIll: ImageView
    private lateinit var tvErrorTitle: TextView
    private lateinit var tvErrorSub: TextView
    private lateinit var btnRetry: Button

    private var dialog: AlertDialog? = null
    private var heroFilm: NguoncFilm? = null
    private var sections: List<PhimSection> = emptyList()
    private var selectedCategorySlug: String = "tat-ca"

    // Danh sách bộ lọc Apple Pills (Thiết kế tinh gọn, chuẩn giao diện OTT hiện đại, không dùng emoji ký tự)
    private val filterCategories = listOf(
        "tat-ca" to "Tất Cả",
        "da-luu" to "Tủ Phim & Tập",
        "tim-kiem" to "Tìm Kiếm",
        "phim-moi" to "Mới Cập Nhật",
        "phim-bo" to "Phim Bộ",
        "phim-le" to "Phim Lẻ",
        "hoat-hinh" to "Hoạt Hình & Anime",
        "hanh-dong" to "Hành Động",
        "tinh-cam" to "Tình Cảm",
        "co-trang" to "Cổ Trang",
        "kinh-di" to "Kinh Dị",
        "hai-huoc" to "Hài Hước",
        "vien-tuong" to "Viễn Tưởng",
        "vo-thuat" to "Võ Thuật"
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_phim, container, false)
        filterList = v.findViewById(R.id.phimFilterList)
        mainList = v.findViewById(R.id.phimMainList)
        loadingView = v.findViewById(R.id.phimLoading)
        errorLayout = v.findViewById(R.id.phimErrorLayout)
        ivEmptyIll = v.findViewById(R.id.ivPhimEmptyIll)
        tvErrorTitle = v.findViewById(R.id.tvPhimErrorTitle)
        tvErrorSub = v.findViewById(R.id.tvPhimErrorSub)
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
        view?.postDelayed({
            ensurePhimFocus()
        }, 150L)
    }

    fun ensurePhimFocus(): Boolean {
        if (!isAdded || view == null) return false
        val act = activity ?: return false
        if (act.currentFocus != null) return true

        if (::mainList.isInitialized && mainList.visibility == View.VISIBLE) {
            val heroPlay = mainList.findViewById<View>(R.id.btnHeroPlay)
            if (heroPlay != null && heroPlay.isFocusable && heroPlay.requestFocus()) {
                return true
            }
            for (i in 0 until mainList.childCount) {
                val child = mainList.getChildAt(i)
                val btn = child.findViewById<View>(R.id.btnHeroPlay)
                if (btn != null && btn.isFocusable && btn.requestFocus()) return true
                val inner = firstFocusableIn(child)
                if (inner != null && inner.requestFocus()) return true
            }
        }

        if (::filterList.isInitialized && filterList.visibility == View.VISIBLE) {
            for (i in 0 until filterList.childCount) {
                val cv = filterList.getChildAt(i)
                if (cv.isFocusable && cv.requestFocus()) return true
                val inner = firstFocusableIn(cv)
                if (inner != null && inner.requestFocus()) return true
            }
        }
        return false
    }

    private fun firstFocusableIn(v: View): View? {
        if (!v.isShown || v.visibility != View.VISIBLE) return null
        if (v.isFocusable) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val f = firstFocusableIn(v.getChildAt(i))
                if (f != null) return f
            }
        }
        return null
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
                newSections.add(0, PhimSection("TIẾP TỤC XEM", historyFilms, isContinueWatching = true))
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
                onHeroPlay = { film -> openHeroPlay(film) },
                onFavoriteToggle = { film -> toggleFilmFavorite(film) }
            )
            mainList.postDelayed({
                ensurePhimFocus()
            }, 100L)
        }
    }

    private fun toggleFilmFavorite(film: NguoncFilm) {
        viewLifecycleOwner.lifecycleScope.launch {
            val nowFav = withContext(Dispatchers.IO) { WatchHistoryManager.toggleFavorite(film) }
            android.widget.Toast.makeText(
                requireContext(),
                if (nowFav) "Đã lưu \"${film.name}\" vào danh sách yêu thích" else "Đã bỏ lưu \"${film.name}\"",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            if (selectedCategorySlug == "da-luu" || selectedCategorySlug == "tat-ca") {
                loadFilmsByCategory(selectedCategorySlug)
            }
        }
    }

    private fun showSearchDialog() {
        val ctx = context ?: return
        val et = EditText(ctx).apply {
            hint = "Nhập tên phim (VD: One Piece, Lật Mặt...)"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(36, 24, 36, 24)
            textSize = 15f
        }
        val container = FrameLayout(ctx).apply {
            setPadding(32, 20, 32, 20)
            addView(et)
        }
        AlertDialog.Builder(ctx, R.style.Theme_KenhLive_Dialog)
            .setTitle("Tìm Kiếm Phim Nguồn C")
            .setView(container)
            .setPositiveButton("Tìm kiếm") { _, _ ->
                val kw = et.text.toString().trim()
                if (kw.isNotBlank()) {
                    performSearch(kw)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
        et.requestFocus()
    }

    private fun performSearch(keyword: String) {
        loadingView.visibility = View.VISIBLE
        mainList.visibility = View.GONE
        errorLayout.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                NguoncRepository.searchFilms(keyword)
            }
            loadingView.visibility = View.GONE

            if (results.isNotEmpty()) {
                heroFilm = results.firstOrNull()
                val searchSection = PhimSection("KẾT QUẢ TÌM KIẾM: \"${keyword.uppercase()}\" (${results.size})", results)
                sections = listOf(searchSection)
                mainList.visibility = View.VISIBLE
                mainList.adapter = PhimMainAdapter(
                    hero = heroFilm,
                    sections = sections,
                    onFilmClick = { film -> openFilmDialog(film) },
                    onHeroPlay = { film -> openHeroPlay(film) },
                    onFavoriteToggle = { film -> toggleFilmFavorite(film) }
                )
            } else {
                mainList.visibility = View.GONE
                errorLayout.visibility = View.VISIBLE
                ivEmptyIll.visibility = View.VISIBLE
                tvErrorTitle.text = "Không tìm thấy phim"
                tvErrorSub.text = "Không có kết quả nào cho từ khóa: \"$keyword\".\nVui lòng thử lại với từ khóa khác."
                btnRetry.text = "Tìm từ khóa khác"
                btnRetry.setOnClickListener { showSearchDialog() }
            }
        }
    }

    private fun loadFilmsByCategory(categorySlug: String) {
        if (categorySlug == "tim-kiem") {
            showSearchDialog()
            return
        }

        loadingView.visibility = View.VISIBLE
        errorLayout.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            // Lấy danh sách lịch sử tiếp tục xem và danh sách phim đã lưu
            val history = withContext(Dispatchers.IO) {
                WatchHistoryManager.getContinueWatchingList(15)
            }
            val favorites = withContext(Dispatchers.IO) {
                WatchHistoryManager.getFavoriteFilms()
            }

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
                builtSections.add(PhimSection("TIẾP TỤC XEM", historyFilms, isContinueWatching = true))
            }

            // 2. Section "Phim Đã Lưu" nếu người dùng đang ở tab Tất Cả
            if (favorites.isNotEmpty() && categorySlug == "tat-ca") {
                builtSections.add(PhimSection("BỘ PHIM ĐÃ LƯU (${favorites.size})", favorites))
            }

            if (categorySlug == "da-luu") {
                // Người dùng bấm vào tab Tủ Phim & Tập (Hiển thị cả Bộ phim đã lưu & Tập xem)
                val completed = withContext(Dispatchers.IO) {
                    WatchHistoryManager.getCompletedFilms(20)
                }

                heroFilm = favorites.firstOrNull { it.posterUrl.isNotBlank() }
                    ?: (if (history.isNotEmpty()) {
                        val h = history.first()
                        NguoncFilm(
                            name = h.filmName,
                            slug = h.slug,
                            posterUrl = h.posterUrl,
                            thumbUrl = h.posterUrl,
                            currentEpisode = h.episodeName,
                            watchProgress = h
                        )
                    } else null)

                if (favorites.isNotEmpty()) {
                    builtSections.add(PhimSection("BỘ PHIM ĐÃ LƯU (${favorites.size})", favorites))
                }
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
                    builtSections.add(PhimSection("TẬP PHIM ĐANG XEM DỞ (${historyFilms.size})", historyFilms, isContinueWatching = true))
                }
                if (completed.isNotEmpty()) {
                    val completedFilms = completed.map { c ->
                        NguoncFilm(
                            name = c.filmName,
                            slug = c.slug,
                            posterUrl = c.posterUrl,
                            thumbUrl = c.posterUrl,
                            currentEpisode = c.episodeName,
                            watchProgress = c
                        )
                    }
                    builtSections.add(PhimSection("TẬP PHIM ĐÃ XEM XONG (${completedFilms.size})", completedFilms))
                }
            } else if (categorySlug == "tat-ca") {
                // NẠP ĐỒNG THỜI TOÀN BỘ CÁC DANH MỤC CÙNG LÚC (Full Parallel Loading)
                val sectionsData = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val defNew = async { NguoncRepository.fetchFilmsBatch("phim-moi", pages = 1..8, context = requireContext()) }
                        val defBo = async { NguoncRepository.fetchFilmsBatch("phim-bo", pages = 1..8, context = requireContext()) }
                        val defLe = async { NguoncRepository.fetchFilmsBatch("phim-le", pages = 1..8, context = requireContext()) }
                        val defAnime = async { NguoncRepository.fetchFilmsBatch("hoat-hinh", pages = 1..8, context = requireContext()) }
                        val defAction = async { NguoncRepository.fetchFilmsBatch("hanh-dong", pages = 1..6, isCategory = true, context = requireContext()) }
                        val defRomance = async { NguoncRepository.fetchFilmsBatch("tinh-cam", pages = 1..6, isCategory = true, context = requireContext()) }
                        val defHistorical = async { NguoncRepository.fetchFilmsBatch("co-trang", pages = 1..6, isCategory = true, context = requireContext()) }
                        val defHorror = async { NguoncRepository.fetchFilmsBatch("kinh-di", pages = 1..6, isCategory = true, context = requireContext()) }

                        listOf(
                            "PHIM MỚI CẬP NHẬT" to defNew.await(),
                            "PHIM BỘ CHỌN LỌC" to defBo.await(),
                            "PHIM LẺ ĐẶC SẮC" to defLe.await(),
                            "HOẠT HÌNH & ANIME" to defAnime.await(),
                            "HÀNH ĐỘNG KỊCH TÍNH" to defAction.await(),
                            "TÌNH CẢM LÃNG MẠN" to defRomance.await(),
                            "CỔ TRANG ĐẶC SẮC" to defHistorical.await(),
                            "KINH DỊ & GIẬT GÂN" to defHorror.await()
                        )
                    }
                }

                // Chọn Hero film từ phim mới cập nhật
                val firstList = sectionsData.firstOrNull { it.second.isNotEmpty() }?.second ?: emptyList()
                heroFilm = firstList.firstOrNull { it.posterUrl.isNotBlank() || it.thumbUrl.isNotBlank() } ?: firstList.firstOrNull()

                for ((title, list) in sectionsData) {
                    if (list.isNotEmpty()) {
                        builtSections.add(PhimSection(title, list))
                    }
                }
            } else {
                // NẠP HÀNG LOẠT 10-15 TRANG CÙNG LÚC CHO DANH MỤC ĐƯỢC CHỌN (100-150 phim)
                val isCat = categorySlug !in listOf("phim-moi", "phim-bo", "phim-le", "hoat-hinh", "tv-shows")
                val films = withContext(Dispatchers.IO) {
                    NguoncRepository.fetchFilmsBatch(
                        typeOrCatSlug = categorySlug,
                        pages = 1..15,
                        isCategory = isCat,
                        context = requireContext()
                    )
                }

                heroFilm = films.firstOrNull { it.posterUrl.isNotBlank() || it.thumbUrl.isNotBlank() } ?: films.firstOrNull()
                val catTitle = filterCategories.firstOrNull { it.first == categorySlug }?.second?.uppercase() ?: "DANH SÁCH PHIM"
                if (films.isNotEmpty()) {
                    builtSections.add(PhimSection(catTitle, films))
                }
            }

            loadingView.visibility = View.GONE

            if (builtSections.isNotEmpty()) {
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
                    onHeroPlay = { film -> openHeroPlay(film) },
                    onFavoriteToggle = { film -> toggleFilmFavorite(film) }
                )
            } else {
                mainList.visibility = View.GONE
                errorLayout.visibility = View.VISIBLE
                if (categorySlug == "da-luu") {
                    ivEmptyIll.visibility = View.VISIBLE
                    tvErrorTitle.text = "Tủ Phim & Tập Đang Trống"
                    tvErrorSub.text = "Chưa có bộ phim hoặc tập phim nào được lưu.\nHãy nhấn 'Lưu Phim' hoặc xem bất kỳ phim nào để lưu vào đây!"
                    btnRetry.text = "Khám phá kho phim"
                    btnRetry.setOnClickListener {
                        selectedCategorySlug = "tat-ca"
                        filterList.adapter = FilterAdapter(filterCategories, selectedCategorySlug) { catSlug ->
                            selectedCategorySlug = catSlug
                            loadFilmsByCategory(catSlug)
                        }
                        loadFilmsByCategory("tat-ca")
                    }
                } else {
                    ivEmptyIll.visibility = View.GONE
                    tvErrorTitle.text = "Không thể tải kho phim"
                    tvErrorSub.text = "Kiểm tra kết nối mạng hoặc thử lại sau"
                    btnRetry.text = "Thử lại"
                    btnRetry.setOnClickListener {
                        loadFilmsByCategory(selectedCategorySlug)
                    }
                }
            }
        }
    }

    private fun openFilmDialog(film: NguoncFilm) {
        val ctx = context ?: return
        try {
            dialog?.dismiss()
            dialog = EpisodePickerDialog.show(
                context = ctx,
                scope = viewLifecycleOwner.lifecycleScope,
                film = film,
                onSelectEpisode = { f, ep, startPos ->
                    playEpisode(f, ep, startPos)
                }
            )
        } catch (t: Throwable) {
            android.util.Log.e("PhimFragment", "openFilmDialog error", t)
        }
    }

    private fun openHeroPlay(film: NguoncFilm) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val progress = withContext(Dispatchers.IO) {
                    WatchHistoryManager.getProgressForFilm(film.slug)
                }
                if (progress != null) {
                    playEpisode(
                        film = film,
                        episode = NguoncEpisodeItem(
                            name = progress.episodeName,
                            slug = progress.episodeSlug,
                            embed = progress.embedUrl,
                            m3u8 = if (progress.embedUrl.contains(".m3u8")) progress.embedUrl else ""
                        ),
                        startPosMs = progress.positionMs
                    )
                    return@launch
                }

                val detail = withContext(Dispatchers.IO) {
                    NguoncRepository.fetchFilmDetail(film.slug, ctx)
                }
                val firstEp = detail?.episodes?.firstOrNull()?.items?.firstOrNull()
                if (firstEp != null) {
                    playEpisode(film, firstEp, 0L)
                } else {
                    openFilmDialog(film)
                }
            } catch (t: Throwable) {
                android.util.Log.e("PhimFragment", "openHeroPlay error", t)
            }
        }
    }

    private fun playEpisode(film: NguoncFilm, episode: NguoncEpisodeItem, startPosMs: Long = 0L) {
        val ctx = context ?: return
        try {
            val intent = Intent(ctx, WebPlayerActivity::class.java)
                .putExtra("embed_url", episode.embed)
                .putExtra("m3u8_url", episode.m3u8)
                .putExtra("film_slug", film.slug)
                .putExtra("film_title", film.name)
                .putExtra("episode_slug", episode.slug)
                .putExtra("episode_title", if (episode.name.all { it.isDigit() }) "Tập ${episode.name}" else episode.name)
                .putExtra("poster_url", film.posterUrl.ifBlank { film.thumbUrl })
                .putExtra("start_position_ms", startPosMs)
            startActivity(intent)
            (activity as? MainActivity)?.hideKeyboard()
        } catch (t: Throwable) {
            android.util.Log.e("PhimFragment", "playEpisode error", t)
        }
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
        private val onHeroPlay: (NguoncFilm) -> Unit,
        private val onFavoriteToggle: (NguoncFilm) -> Unit
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
                holder.bind(hero, onFilmClick, onHeroPlay, onFavoriteToggle)
            } else if (holder is RowVH) {
                val sectionIdx = if (hero != null) position - 1 else position
                if (sectionIdx in sections.indices) {
                    holder.bind(sections[sectionIdx], onFilmClick, onFavoriteToggle)
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
        val btnFavorite: Button = v.findViewById(R.id.btnHeroFavorite)

        fun bind(
            film: NguoncFilm,
            onFilmClick: (NguoncFilm) -> Unit,
            onHeroPlay: (NguoncFilm) -> Unit,
            onFavoriteToggle: (NguoncFilm) -> Unit
        ) {
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

            (itemView.context as? androidx.appcompat.app.AppCompatActivity)?.lifecycleScope?.launch {
                val isFav = withContext(Dispatchers.IO) { WatchHistoryManager.isFavorite(film.slug) }
                btnFavorite.text = if (isFav) "ĐÃ LƯU" else "LƯU PHIM"
                btnFavorite.setCompoundDrawablesWithIntrinsicBounds(
                    if (isFav) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark, 0, 0, 0
                )
            }

            btnPlay.setOnClickListener { onHeroPlay(film) }
            btnEpisodes.setOnClickListener { onFilmClick(film) }
            btnFavorite.setOnClickListener { onFavoriteToggle(film) }

            setupFocusAnimation(btnPlay)
            setupFocusAnimation(btnEpisodes)
            setupFocusAnimation(btnFavorite)
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

        fun bind(
            section: PhimSection,
            onFilmClick: (NguoncFilm) -> Unit,
            onFavoriteToggle: (NguoncFilm) -> Unit
        ) {
            title.text = section.title
            count.text = "${section.films.size} phim"

            carousel.layoutManager = LinearLayoutManager(itemView.context, LinearLayoutManager.HORIZONTAL, false)
            carousel.adapter = PhimCardAdapter(section.films, onFilmClick, onFavoriteToggle)
        }
    }

    private class PhimCardAdapter(
        private val films: List<NguoncFilm>,
        private val onFilmClick: (NguoncFilm) -> Unit,
        private val onFavoriteToggle: (NguoncFilm) -> Unit
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

            holder.itemView.setOnLongClickListener {
                onFavoriteToggle(film)
                true
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
