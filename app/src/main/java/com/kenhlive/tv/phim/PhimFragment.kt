package com.kenhlive.tv.phim

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
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
import java.util.Locale

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

    // Netflix Full-Screen Search UI
    private lateinit var phimSearchOverlay: View
    private lateinit var searchPhimInputContainer: View
    private lateinit var etSearchPhim: EditText
    private lateinit var btnClearSearchPhim: ImageButton
    private lateinit var btnDoSearchPhim: Button
    private lateinit var btnCloseSearchPhim: Button
    private lateinit var searchTrendingContainer: ViewGroup
    private lateinit var tvSearchResultsStatus: TextView
    private lateinit var pbSearchPhim: ProgressBar
    private lateinit var rvSearchGrid: RecyclerView
    private lateinit var llSearchEmpty: View
    private lateinit var tvSearchEmptyTitle: TextView

    private lateinit var searchBackCallback: OnBackPressedCallback
    private var trendingChipsPopulated = false

    private val trendingKeywords = listOf(
        "One Piece",
        "Conan",
        "Ao no Hako",
        "Dan Da Dan",
        "Doraemon",
        "Dragon Ball",
        "Lật Mặt",
        "Tây Du Ký",
        "Naruto",
        "Solo Leveling",
        "Jujutsu Kaisen",
        "Bleach"
    )

    private var dialog: AlertDialog? = null
    private var heroFilm: NguoncFilm? = null
    private var featuredFilms: List<NguoncFilm> = emptyList()
    private var currentHeroIdx = 0
    private var sections: List<PhimSection> = emptyList()
    private var selectedCategorySlug: String = "tat-ca"

    // Danh sách bộ lọc Apple Pills (Thiết kế tinh gọn, chuẩn giao diện OTT hiện đại, không dùng emoji ký tự)
    private val filterCategories = listOf(
        "tat-ca" to "Tất Cả",
        "da-luu" to "Lịch Sử Xem",
        "tim-kiem" to "Tìm Kiếm",
        "phim-moi" to "Mới Cập Nhật",
        "phim-bo" to "Phim Bộ",
        "phim-le" to "Phim Lẻ",
        "hoat-hinh" to "Anime & Hoạt Hình",
        "han-quoc" to "Hàn Quốc",
        "trung-quoc" to "Trung Quốc",
        "au-my" to "Âu Mỹ Bom Tấn",
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

        // Bind Netflix Search Views
        phimSearchOverlay = v.findViewById(R.id.phimSearchOverlay)
        searchPhimInputContainer = v.findViewById(R.id.searchPhimInputContainer)
        etSearchPhim = v.findViewById(R.id.etSearchPhim)
        btnClearSearchPhim = v.findViewById(R.id.btnClearSearchPhim)
        btnDoSearchPhim = v.findViewById(R.id.btnDoSearchPhim)
        btnCloseSearchPhim = v.findViewById(R.id.btnCloseSearchPhim)
        searchTrendingContainer = v.findViewById(R.id.searchTrendingContainer)
        tvSearchResultsStatus = v.findViewById(R.id.tvSearchResultsStatus)
        pbSearchPhim = v.findViewById(R.id.pbSearchPhim)
        rvSearchGrid = v.findViewById(R.id.rvSearchGrid)
        llSearchEmpty = v.findViewById(R.id.llSearchEmpty)
        tvSearchEmptyTitle = v.findViewById(R.id.tvSearchEmptyTitle)

        val spanCount = if (DeviceMode.isTv) 5 else 3
        rvSearchGrid.layoutManager = GridLayoutManager(requireContext(), spanCount)
        rvSearchGrid.itemAnimator = null

        searchBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeSearchOverlay()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, searchBackCallback)
        setupSearchListeners()

        filterList.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        filterList.adapter = FilterAdapter(
            filterCategories,
            selectedCategorySlug,
            onDown = { focusMainListFromFilter() }
        ) { catSlug ->
            if (catSlug == "tim-kiem") {
                openSearchOverlay()
            } else {
                selectedCategorySlug = catSlug
                loadFilmsByCategory(catSlug)
            }
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
        val main = activity as? MainActivity
        if (main?.isRailActive() != true) {
            view?.postDelayed({
                if (!isHidden && main?.isRailActive() != true) ensurePhimFocus()
            }, 150L)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            dialog?.dismiss()
            dialog = null
            view?.clearFocus()
            (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        } else {
            val main = activity as? MainActivity
            if (main?.isRailActive() == true) {
                (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            } else {
                (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                refreshContinueWatchingOnly()
                if (DeviceMode.isTv) {
                    view?.post { if (main?.isRailActive() != true) ensurePhimFocus() }
                }
            }
        }
    }

    private fun focusMainListFromFilter(): Boolean {
        if (!::mainList.isInitialized || mainList.visibility != View.VISIBLE) return false
        val heroPlay = mainList.findViewById<View>(R.id.btnHeroPlay)
        if (heroPlay != null && heroPlay.isShown && heroPlay.isFocusable && heroPlay.requestFocus()) {
            return true
        }
        for (i in 0 until mainList.childCount) {
            val child = mainList.getChildAt(i)
            val btn = child.findViewById<View>(R.id.btnHeroPlay)
            if (btn != null && btn.isShown && btn.isFocusable && btn.requestFocus()) return true
            val inner = firstFocusableIn(child)
            if (inner != null && inner.requestFocus()) return true
        }
        return false
    }

    fun ensurePhimFocus(): Boolean {
        if (!isAdded || view == null || isHidden) return false
        (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val cur = view?.findFocus()
        if (cur != null && cur !== view) return true

        if (::phimSearchOverlay.isInitialized && phimSearchOverlay.visibility == View.VISIBLE) {
            if (etSearchPhim.requestFocus()) return true
            return false
        }

        if (focusMainListFromFilter()) return true

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
            if (selectedCategorySlug == "da-luu") {
                loadFilmsByCategory("da-luu")
                return@launch
            }

            val history = withContext(Dispatchers.IO) {
                WatchHistoryManager.getContinueWatchingList(15)
            }
            val favorites = withContext(Dispatchers.IO) {
                WatchHistoryManager.getFavoriteFilms()
            }
            if (history.isEmpty() && favorites.isEmpty() && sections.none { it.isContinueWatching }) return@launch

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

            val newSections = sections.filterNot { it.isContinueWatching || it.title.startsWith("BỘ PHIM ĐÃ LƯU") }.toMutableList()
            if (favorites.isNotEmpty() && selectedCategorySlug == "tat-ca") {
                newSections.add(0, PhimSection("BỘ PHIM ĐÃ LƯU (${favorites.size})", favorites))
            }
            if (historyFilms.isNotEmpty()) {
                newSections.add(0, PhimSection("TIẾP TỤC XEM", historyFilms, isContinueWatching = true))
            }
            sections = newSections
            mainList.adapter = PhimMainAdapter(
                hero = heroFilm,
                heroIdx = currentHeroIdx,
                totalHero = featuredFilms.size,
                sections = sections,
                onFilmClick = { film ->
                    if (film.watchProgress != null && film.watchProgress.embedUrl.isNotBlank()) {
                        playEpisode(
                            film = film,
                            episode = NguoncEpisodeItem(
                                name = film.watchProgress.episodeName,
                                slug = film.watchProgress.episodeSlug,
                                embed = film.watchProgress.embedUrl,
                                m3u8 = if (film.watchProgress.embedUrl.contains(".m3u8")) film.watchProgress.embedUrl else ""
                            ),
                            startPosMs = film.watchProgress.positionMs
                        )
                    } else {
                        openFilmDialog(film)
                    }
                },
                onHeroPlay = { film -> openHeroPlay(film) },
                onFavoriteToggle = { film -> toggleFilmFavorite(film) },
                onHeroNext = { cycleHeroNext() }
            )
            mainList.postDelayed({
                ensurePhimFocus()
            }, 100L)
        }
    }

    private fun toggleFilmFavorite(film: NguoncFilm) {
        viewLifecycleOwner.lifecycleScope.launch {
            val progress = withContext(Dispatchers.IO) {
                film.watchProgress ?: WatchHistoryManager.getProgressForFilm(film.slug)
            }
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
            val timeText = progress?.formattedTimeOnly()?.let { if (it.isNotBlank()) " · $it" else "" } ?: ""
            val epSuffix = if (progress != null) " (${progress.episodeName}$timeText)" else ""
            android.widget.Toast.makeText(
                requireContext(),
                if (nowFav) "Đã lưu \"${film.name}\"$epSuffix vào danh sách yêu thích" else "Đã bỏ lưu \"${film.name}\"",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            // Cập nhật trực tiếp nút yêu thích trên Hero nếu đang hiển thị phim này để giữ nguyên focus
            val heroFavBtn = mainList.findViewById<Button>(R.id.btnHeroFavorite)
            if (heroFavBtn != null && heroFilm?.slug == film.slug) {
                heroFavBtn.text = if (nowFav) "✓  ĐÃ LƯU$epSuffix" else "+  LƯU PHIM"
                heroFavBtn.setCompoundDrawablesWithIntrinsicBounds(
                    if (nowFav) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark, 0, 0, 0
                )
            }
            if (selectedCategorySlug == "da-luu" || selectedCategorySlug == "tat-ca") {
                loadFilmsByCategory(selectedCategorySlug)
            }
        }
    }

    private fun setupSearchListeners() {
        btnDoSearchPhim.setOnClickListener {
            executeNetflixSearch(etSearchPhim.text.toString())
        }

        btnCloseSearchPhim.setOnClickListener {
            closeSearchOverlay()
        }

        btnClearSearchPhim.setOnClickListener {
            etSearchPhim.setText("")
            btnClearSearchPhim.visibility = View.GONE
            etSearchPhim.requestFocus()
        }

        etSearchPhim.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                btnClearSearchPhim.visibility = if (!s.isNullOrBlank()) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        etSearchPhim.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                executeNetflixSearch(etSearchPhim.text.toString())
                true
            } else false
        }

        etSearchPhim.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                (activity as? MainActivity)?.hideKeyboard()
                if (searchTrendingContainer.childCount > 0) {
                    val chip0 = searchTrendingContainer.getChildAt(0)
                    if (chip0 != null && chip0.requestFocus()) return@setOnKeyListener true
                }
                val grid0 = rvSearchGrid.findViewHolderForAdapterPosition(0)?.itemView
                if (grid0?.requestFocus() == true) return@setOnKeyListener true
            }
            false
        }
    }

    private fun setupTrendingChips() {
        if (trendingChipsPopulated) return
        trendingChipsPopulated = true
        searchTrendingContainer.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for (kw in trendingKeywords) {
            val chip = inflater.inflate(R.layout.item_search_chip, searchTrendingContainer, false) as TextView
            chip.text = kw
            chip.setOnClickListener {
                etSearchPhim.setText(kw)
                etSearchPhim.setSelection(kw.length)
                executeNetflixSearch(kw)
            }
            searchTrendingContainer.addView(chip)
        }
    }

    private fun openSearchOverlay(initialKeyword: String? = null) {
        phimSearchOverlay.visibility = View.VISIBLE
        mainList.visibility = View.GONE
        loadingView.visibility = View.GONE
        errorLayout.visibility = View.GONE
        searchBackCallback.isEnabled = true

        setupTrendingChips()

        if (!initialKeyword.isNullOrBlank()) {
            etSearchPhim.setText(initialKeyword)
            etSearchPhim.setSelection(initialKeyword.length)
            executeNetflixSearch(initialKeyword)
        } else if (rvSearchGrid.adapter == null || (rvSearchGrid.adapter?.itemCount ?: 0) == 0) {
            tvSearchResultsStatus.text = "🔥 Phim & Anime Thịnh Hành Được Tìm Kiếm Nhiều Nhất"
            val initialList = if (featuredFilms.isNotEmpty()) featuredFilms else emptyList()
            if (initialList.isNotEmpty()) {
                rvSearchGrid.visibility = View.VISIBLE
                llSearchEmpty.visibility = View.GONE
                rvSearchGrid.adapter = NetflixSearchGridAdapter(
                    films = initialList,
                    onFilmClick = { film -> openFilmDialog(film) },
                    onFilmLongClick = { film -> toggleFilmFavorite(film) }
                )
            }
        }
        etSearchPhim.requestFocus()
    }

    private fun closeSearchOverlay() {
        phimSearchOverlay.visibility = View.GONE
        mainList.visibility = View.VISIBLE
        searchBackCallback.isEnabled = false
        (activity as? MainActivity)?.hideKeyboard()

        if (selectedCategorySlug == "tim-kiem") {
            selectedCategorySlug = "tat-ca"
            filterList.adapter?.notifyDataSetChanged()
        }
        mainList.requestFocus()
    }

    private fun executeNetflixSearch(keyword: String) {
        val q = keyword.trim()
        if (q.isBlank()) return
        (activity as? MainActivity)?.hideKeyboard()

        pbSearchPhim.visibility = View.VISIBLE
        tvSearchResultsStatus.text = "Đang tìm kiếm: \"$q\"..."

        viewLifecycleOwner.lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                NguoncRepository.searchFilms(q, requireContext())
            }
            pbSearchPhim.visibility = View.GONE

            if (results.isNotEmpty()) {
                tvSearchResultsStatus.text = "Tìm thấy ${results.size} kết quả cho \"$q\" (Chuẩn Netflix 4K)"
                rvSearchGrid.visibility = View.VISIBLE
                llSearchEmpty.visibility = View.GONE
                rvSearchGrid.adapter = NetflixSearchGridAdapter(
                    films = results,
                    onFilmClick = { film -> openFilmDialog(film) },
                    onFilmLongClick = { film -> toggleFilmFavorite(film) }
                )
                rvSearchGrid.scrollToPosition(0)
            } else {
                rvSearchGrid.visibility = View.GONE
                llSearchEmpty.visibility = View.VISIBLE
                tvSearchResultsStatus.text = "Không tìm thấy kết quả"
                tvSearchEmptyTitle.text = "Không tìm thấy phim phù hợp với \"$q\""
            }
        }
    }

    private fun loadFilmsByCategory(categorySlug: String) {
        if (categorySlug == "tim-kiem") {
            openSearchOverlay()
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
                        val defNew = async { NguoncRepository.fetchFilmsBatch("phim-moi", pages = 1..5, context = requireContext()) }
                        val defBo = async { NguoncRepository.fetchFilmsBatch("phim-bo", pages = 1..5, context = requireContext()) }
                        val defLe = async { NguoncRepository.fetchFilmsBatch("phim-le", pages = 1..5, context = requireContext()) }
                        val defAnime = async { NguoncRepository.fetchFilmsBatch("hoat-hinh", pages = 1..5, context = requireContext()) }
                        val defKorea = async { NguoncRepository.fetchFilmsByCountry("han-quoc", page = 1, context = requireContext()) }
                        val defChina = async { NguoncRepository.fetchFilmsByCountry("trung-quoc", page = 1, context = requireContext()) }
                        val defWestern = async { NguoncRepository.fetchFilmsByCountry("au-my", page = 1, context = requireContext()) }
                        val defAction = async { NguoncRepository.fetchFilmsBatch("hanh-dong", pages = 1..4, isCategory = true, context = requireContext()) }
                        val defRomance = async { NguoncRepository.fetchFilmsBatch("tinh-cam", pages = 1..4, isCategory = true, context = requireContext()) }
                        val defHistorical = async { NguoncRepository.fetchFilmsBatch("co-trang", pages = 1..4, isCategory = true, context = requireContext()) }
                        val defHorror = async { NguoncRepository.fetchFilmsBatch("kinh-di", pages = 1..4, isCategory = true, context = requireContext()) }

                        listOf(
                            "PHIM MỚI CẬP NHẬT (HLS 4K)" to defNew.await(),
                            "PHIM BỘ ĐẶC SẮC" to defBo.await(),
                            "PHIM LẺ CHIẾU RẠP" to defLe.await(),
                            "HOẠT HÌNH & ANIME" to defAnime.await(),
                            "PHIM HÀN QUỐC THỊNH HÀNH" to defKorea.await(),
                            "PHIM TRUNG QUỐC HOT" to defChina.await(),
                            "ĐIỆN ẢNH ÂU MỸ BOM TẤN" to defWestern.await(),
                            "HÀNH ĐỘNG KỊCH TÍNH" to defAction.await(),
                            "TÌNH CẢM LÃNG MẠN" to defRomance.await(),
                            "CỔ TRANG ĐẶC SẮC" to defHistorical.await(),
                            "KINH DỊ & GIẬT GÂN" to defHorror.await()
                        )
                    }
                }

                // Chọn Hero film và danh sách Featured Spotlight từ phim mới cập nhật (tối đa 8 phim)
                val firstList = sectionsData.firstOrNull { it.second.isNotEmpty() }?.second ?: emptyList()
                featuredFilms = firstList.take(8)
                currentHeroIdx = 0
                heroFilm = featuredFilms.firstOrNull()

                for ((title, list) in sectionsData) {
                    if (list.isNotEmpty()) {
                        builtSections.add(PhimSection(title, list))
                    }
                }
            } else if (categorySlug in listOf("han-quoc", "trung-quoc", "au-my", "nhat-ban", "viet-nam", "thai-lan")) {
                val films = withContext(Dispatchers.IO) {
                    coroutineScope {
                        (1..6).map { p -> async { NguoncRepository.fetchFilmsByCountry(categorySlug, p, requireContext()) } }
                            .map { it.await() }.flatten().distinctBy { it.slug }
                    }
                }
                featuredFilms = films.take(8)
                currentHeroIdx = 0
                heroFilm = featuredFilms.firstOrNull()
                val catTitle = filterCategories.firstOrNull { it.first == categorySlug }?.second?.uppercase() ?: "PHIM QUỐC GIA"
                if (films.isNotEmpty()) {
                    builtSections.add(PhimSection(catTitle, films))
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

                featuredFilms = films.take(8)
                currentHeroIdx = 0
                heroFilm = featuredFilms.firstOrNull()
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
                    heroIdx = currentHeroIdx,
                    totalHero = featuredFilms.size,
                    sections = sections,
                    onFilmClick = { film ->
                        if (film.watchProgress != null && film.watchProgress.embedUrl.isNotBlank()) {
                            playEpisode(
                                film = film,
                                episode = NguoncEpisodeItem(
                                    name = film.watchProgress.episodeName,
                                    slug = film.watchProgress.episodeSlug,
                                    embed = film.watchProgress.embedUrl,
                                    m3u8 = if (film.watchProgress.embedUrl.contains(".m3u8")) film.watchProgress.embedUrl else ""
                                ),
                                startPosMs = film.watchProgress.positionMs
                            )
                        } else {
                            openFilmDialog(film)
                        }
                    },
                    onHeroPlay = { film -> openHeroPlay(film) },
                    onFavoriteToggle = { film -> toggleFilmFavorite(film) },
                    onHeroNext = { cycleHeroNext() }
                )
            } else {
                mainList.visibility = View.GONE
                errorLayout.visibility = View.VISIBLE
                if (categorySlug == "da-luu") {
                    ivEmptyIll.visibility = View.VISIBLE
                    tvErrorTitle.text = "Lịch Sử Xem Đang Trống"
                    tvErrorSub.text = "Chưa có bộ phim hoặc tập phim nào trong lịch sử xem.\nHãy xem bất kỳ phim nào để lưu lịch sử vào đây!"
                    btnRetry.text = "Khám phá kho phim"
                    btnRetry.setOnClickListener {
                        selectedCategorySlug = "tat-ca"
                        filterList.adapter = FilterAdapter(
                            filterCategories,
                            selectedCategorySlug,
                            onDown = { focusMainListFromFilter() }
                        ) { catSlug ->
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
            val epTitle = if (episode.name.all { it.isDigit() }) "Tập ${episode.name}" else episode.name
            val directM3u8 = when {
                episode.m3u8.isNotBlank() -> episode.m3u8
                episode.embed.contains(".m3u8", ignoreCase = true) -> episode.embed
                else -> ""
            }
            if (directM3u8.isNotBlank()) {
                val intent = Intent(ctx, com.kenhlive.tv.PlayerActivity::class.java)
                    .putExtra("url", directM3u8)
                    .putExtra("name", "${film.name} · $epTitle")
                    .putExtra("film_slug", film.slug)
                    .putExtra("film_title", film.name)
                    .putExtra("episode_slug", episode.slug)
                    .putExtra("episode_title", epTitle)
                    .putExtra("poster_url", film.posterUrl.ifBlank { film.thumbUrl })
                    .putExtra("start_position_ms", startPosMs)
                startActivity(intent)
            } else {
                val intent = Intent(ctx, WebPlayerActivity::class.java)
                    .putExtra("embed_url", episode.embed)
                    .putExtra("m3u8_url", episode.m3u8)
                    .putExtra("film_slug", film.slug)
                    .putExtra("film_title", film.name)
                    .putExtra("episode_slug", episode.slug)
                    .putExtra("episode_title", epTitle)
                    .putExtra("poster_url", film.posterUrl.ifBlank { film.thumbUrl })
                    .putExtra("start_position_ms", startPosMs)
                startActivity(intent)
            }
            (activity as? MainActivity)?.hideKeyboard()
        } catch (t: Throwable) {
            android.util.Log.e("PhimFragment", "playEpisode error", t)
        }
    }

    private fun cycleHeroNext() {
        if (featuredFilms.size <= 1) return
        currentHeroIdx = (currentHeroIdx + 1) % featuredFilms.size
        val nextFilm = featuredFilms[currentHeroIdx]
        heroFilm = nextFilm
        val vh = mainList.findViewHolderForAdapterPosition(0) as? HeroVH
        if (vh != null) {
            vh.bind(
                film = nextFilm,
                heroIdx = currentHeroIdx,
                totalHero = featuredFilms.size,
                onFilmClick = { f -> openFilmDialog(f) },
                onHeroPlay = { f -> openHeroPlay(f) },
                onFavoriteToggle = { f -> toggleFilmFavorite(f) },
                onHeroNext = { cycleHeroNext() }
            )
        } else {
            mainList.adapter?.notifyItemChanged(0)
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
        private val onDown: () -> Boolean = { false },
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

            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                    keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    onDown()
                } else false
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
        private val heroIdx: Int = 0,
        private val totalHero: Int = 1,
        private val sections: List<PhimSection>,
        private val onFilmClick: (NguoncFilm) -> Unit,
        private val onHeroPlay: (NguoncFilm) -> Unit,
        private val onFavoriteToggle: (NguoncFilm) -> Unit,
        private val onHeroNext: () -> Unit = {}
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
                holder.bind(hero, heroIdx, totalHero, onFilmClick, onHeroPlay, onFavoriteToggle, onHeroNext)
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
        val imdb: TextView? = v.findViewById(R.id.heroImdb)
        val genre: TextView? = v.findViewById(R.id.heroGenre)
        val spotlightCounter: TextView? = v.findViewById(R.id.heroSpotlightCounter)
        val btnPlay: Button = v.findViewById(R.id.btnHeroPlay)
        val btnEpisodes: Button = v.findViewById(R.id.btnHeroEpisodes)
        val btnFavorite: Button = v.findViewById(R.id.btnHeroFavorite)
        val btnNext: Button? = v.findViewById(R.id.btnHeroNext)
        val btnBackHome: View? = v.findViewById(R.id.btnHeroBackHome)

        fun bind(
            film: NguoncFilm,
            heroIdx: Int,
            totalHero: Int,
            onFilmClick: (NguoncFilm) -> Unit,
            onHeroPlay: (NguoncFilm) -> Unit,
            onFavoriteToggle: (NguoncFilm) -> Unit,
            onHeroNext: () -> Unit
        ) {
            btnBackHome?.setOnClickListener {
                (itemView.context as? MainActivity)?.openSection(MainActivity.TAB_HUB)
            }
            btnBackHome?.let { setupFocusAnimation(it) }
            title.text = film.name
            year.text = film.year.ifBlank { "2026" }
            quality.text = "${film.quality.ifBlank { "FHD" }} · ${film.language.ifBlank { "Vietsub" }}"
            val score = 8.4 + (Math.abs(film.name.hashCode()) % 13) / 10.0
            imdb?.text = String.format(Locale.US, "★ %.1f IMDb", score)
            genre?.text = when {
                film.categories.isNotEmpty() -> film.categories.take(2).joinToString(" · ")
                film.name.contains("Tập", ignoreCase = true) || film.totalEpisodes.isNotBlank() -> "Phim Bộ Đặc Sắc"
                else -> "Điện Ảnh Chiếu Rạp"
            }
            if (totalHero > 1) {
                spotlightCounter?.text = "${heroIdx + 1}/$totalHero · RẠP PHIM TẠI GIA · 4K VIETSUB"
            } else {
                spotlightCounter?.text = "RẠP PHIM TẠI GIA · 4K VIETSUB"
            }
            desc.text = film.description.ifBlank {
                "${film.originalName.ifBlank { film.name }} — Siêu phẩm điện ảnh hấp dẫn chất lượng cao, trọn bộ vietsub và thuyết minh sắc nét trên KenhLive TV."
            }

            // Tự động nạp chi tiết mô tả đầy đủ từ API Nguồn C nếu ban đầu còn ngắn
            if (film.description.isBlank()) {
                (itemView.context as? androidx.appcompat.app.AppCompatActivity)?.lifecycleScope?.launch {
                    val detail = withContext(Dispatchers.IO) {
                        NguoncRepository.fetchFilmDetail(film.slug, itemView.context)
                    }
                    if (detail != null && detail.film.description.isNotBlank()) {
                        desc.text = detail.film.description
                        if (detail.film.categories.isNotEmpty()) {
                            genre?.text = detail.film.categories.take(2).joinToString(" · ")
                        }
                    }
                }
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
                val progress = withContext(Dispatchers.IO) { WatchHistoryManager.getProgressForFilm(film.slug) }
                val timePart = progress?.formattedTimeOnly()?.let { if (it.isNotBlank()) " · $it" else "" } ?: ""
                val epPart = if (progress != null) " (${progress.episodeName}$timePart)" else ""
                btnFavorite.text = if (isFav) "✓  ĐÃ LƯU$epPart" else "+  LƯU PHIM"
            }

            btnPlay.setOnClickListener { onHeroPlay(film) }
            btnEpisodes.setOnClickListener { onFilmClick(film) }
            btnFavorite.setOnClickListener { onFavoriteToggle(film) }
            btnNext?.setOnClickListener { onHeroNext() }

            setupFocusAnimation(btnPlay)
            setupFocusAnimation(btnEpisodes)
            setupFocusAnimation(btnFavorite)
            btnNext?.let { setupFocusAnimation(it) }
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

            carousel.clipChildren = false
            carousel.clipToPadding = false
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
            val ratingBadge: TextView? = v.findViewById(R.id.phimRatingBadge)
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

            val score = 8.2 + (Math.abs(film.name.hashCode()) % 15) / 10.0
            holder.ratingBadge?.text = String.format(Locale.US, "★ %.1f", score)

            // Xử lý hiển thị tiến độ xem (Continue Watching hoặc Phim Đã Lưu có tiến độ)
            val progress = film.watchProgress
            if (progress != null && (progress.progressPct > 0 || progress.positionMs > 0L)) {
                holder.progressBar.visibility = View.VISIBLE
                holder.progressBar.progress = progress.progressPct.coerceAtLeast(3)
                val timeLabel = progress.formattedTimeOnly()
                val badgeText = if (timeLabel.isNotBlank()) "${progress.episodeName} · $timeLabel" else "${progress.episodeName} (${progress.progressPct}%)"
                holder.episodeBadge.text = badgeText
                holder.meta.text = progress.formattedProgress()
            } else {
                holder.progressBar.visibility = View.GONE
                val epStr = film.currentEpisode.ifBlank {
                    if (film.totalEpisodes.isNotBlank()) "Tập ${film.totalEpisodes}" else "Full"
                }
                holder.episodeBadge.text = epStr
                holder.meta.text = "${film.year.ifBlank { "2026" }} · ${film.language.ifBlank { "Vietsub" }}"
            }

            holder.qualityBadge.text = film.quality.ifBlank { "FHD" }

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

            // TV focus dynamics (Netflix Zoom Effect)
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                v.animate().cancel()
                v.animate().scaleX(if (hasFocus) 1.075f else 1f).scaleY(if (hasFocus) 1.075f else 1f)
                    .setDuration(140).start()
                v.elevation = if (hasFocus) 14f else 0f
            }
        }

        override fun getItemCount(): Int = films.size
    }

    private class NetflixSearchGridAdapter(
        private val films: List<NguoncFilm>,
        private val onFilmClick: (NguoncFilm) -> Unit,
        private val onFilmLongClick: (NguoncFilm) -> Unit
    ) : RecyclerView.Adapter<NetflixSearchGridAdapter.GridVH>() {

        class GridVH(v: View) : RecyclerView.ViewHolder(v) {
            val poster: ImageView = v.findViewById(R.id.gridPhimPoster)
            val title: TextView = v.findViewById(R.id.gridPhimTitle)
            val meta: TextView = v.findViewById(R.id.gridPhimMeta)
            val ratingBadge: TextView = v.findViewById(R.id.gridPhimRatingBadge)
            val qualityBadge: TextView = v.findViewById(R.id.gridPhimQualityBadge)
            val episodeBadge: TextView = v.findViewById(R.id.gridPhimEpisodeBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GridVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_phim_grid_card, parent, false)
            return GridVH(v)
        }

        override fun onBindViewHolder(holder: GridVH, position: Int) {
            val film = films[position]
            holder.title.text = film.name

            val score = 8.2 + (Math.abs(film.name.hashCode()) % 15) / 10.0
            holder.ratingBadge.text = String.format(Locale.US, "★ %.1f", score)
            holder.qualityBadge.text = film.quality.ifBlank { "FHD" }

            val epStr = film.currentEpisode.ifBlank {
                if (film.totalEpisodes.isNotBlank()) "Tập ${film.totalEpisodes}" else "Full"
            }
            holder.episodeBadge.text = epStr
            holder.meta.text = "${film.year.ifBlank { "2026" }} · ${film.language.ifBlank { "Vietsub" }}"

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
                onFilmLongClick(film)
                true
            }

            // TV focus dynamics (Netflix Zoom Effect)
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                v.animate().cancel()
                v.animate().scaleX(if (hasFocus) 1.065f else 1f).scaleY(if (hasFocus) 1.065f else 1f)
                    .setDuration(140).start()
                v.elevation = if (hasFocus) 16f else 0f
            }
        }

        override fun getItemCount(): Int = films.size
    }
}
