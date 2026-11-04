package com.kenhlive.tv.phim

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Parcelable
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
import kotlinx.coroutines.Job
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
 * - Hỗ trợ kho phim KKPhim (phimapi.com) với đầy đủ thể loại, quốc gia, năm.
 * - HÀNG TIẾP TỤC XEM (Continue Watching): Hiển thị tiến độ xem dở, bấm phát tiếp đúng giây.
 * - BỘ LỌC THỂ LOẠI APPLE PILLS: Tất cả, Phim mới, Phim bộ, Phim lẻ, Anime, Hành động, Tình cảm...
 * - Thiết kế tối giản chuẩn Apple TV & iOS: Thẻ kính mờ bo góc 16dp, gradient phản chiếu, thanh tiến độ mỏng.
 * - Hỗ trợ đa thiết bị: D-pad focus mượt mà trên TV, cử chỉ vuốt chạm nhanh trên Mobile.
 */
/**
 * Tên hiển thị trên THẺ phim: API trả một số phim kèm cả tên gốc sau dấu " / "
 * (vd "Từng Bước Bên Nhau / Tên Gốc") — trên thẻ hẹp chỉ lấy tên chính cho gọn,
 * tránh bị cắt thành "... / Tì...". Hero và hộp thoại vẫn dùng tên đầy đủ.
 */
internal fun cardTitle(name: String): String =
    name.substringBefore(" / ").trim().ifBlank { name }

class PhimFragment : Fragment() {

    private var filterAdapter: FilterAdapter? = null
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

    // ===== Trạng thái CHẾ ĐỘ LƯỚI DỌC (chọn 1 danh mục cụ thể -> poster trải xuống, phân trang) =====
    private var gridMode = false
    private var gridSlug: String = ""
    private val gridFilms = mutableListOf<NguoncFilm>()
    private val gridSeenSlugs = HashSet<String>()
    private var gridPage = 0
    private var gridLastPageSize = 0
    private var gridLoading = false
    private var gridEndReached = false
    // Tăng mỗi lần đổi filter/chế độ: kết quả tải trang về trễ của filter cũ bị bỏ qua
    private var gridGeneration = 0

    // Vị trí cuộn ngang của từng rail (khóa ổn định theo rail), dùng chung cho mọi
    // PhimMainAdapter của fragment để không mất vị trí khi bind lại / thay adapter (04-H3)
    private val railScrollStates = HashMap<String, Parcelable>()

    // Tìm kiếm trong hub: job + generation để hủy / bỏ qua kết quả của từ khóa cũ (05-H1)
    private var searchJob: Job? = null
    private var searchGeneration = 0

    // Ngưỡng tiền tải: còn ~10 poster chưa hiển thị thì bắt đầu tải trang tiếp
    private val gridPrefetchItems = 10
    // Map filter -> API phân trang, bám đúng dispatch rail hiện tại trong loadFilmsByCategory
    private val gridCountrySlugs = setOf("han-quoc", "trung-quoc", "au-my", "nhat-ban", "viet-nam", "thai-lan")
    private val gridTypeSlugs = setOf("phim-bo", "phim-le", "hoat-hinh", "tv-shows")

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

        // Lưới kết quả tìm kiếm: TV 5 cột, phone dọc 3 cột, phone ngang 5 cột
        val spanCount = DeviceMode.getGridSpanCount(requireContext(), defaultTv = 5, phonePortrait = 3, phoneLandscape = 5)
        rvSearchGrid.layoutManager = GridLayoutManager(requireContext(), spanCount)
        rvSearchGrid.itemAnimator = null

        searchBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeSearchOverlay()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, searchBackCallback)
        setupSearchListeners()

        // Danh mục không còn là thanh cố định trên cùng: adapter này được gắn vào một hàng
        // trong danh sách chính, ngay dưới hero (xem PhimMainAdapter.TYPE_FILTERS)
        filterAdapter = newFilterAdapter()

        mainList.layoutManager = LinearLayoutManager(requireContext())
        mainList.itemAnimator = null

        // Chế độ lưới: cuộn gần cuối danh sách -> tự tải trang tiếp theo của danh mục
        mainList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!gridMode || gridLoading || gridEndReached) return
                val glm = rv.layoutManager as? GridLayoutManager ?: return
                val total = rv.adapter?.itemCount ?: 0
                if (total <= 0) return
                if (glm.findLastVisibleItemPosition() >= total - 1 - gridPrefetchItems) {
                    loadNextGridPage()
                }
            }
        })

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
            if (!isHidden) ensurePhimFocus()
        }, 150L)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            dialog?.dismiss()
            dialog = null
            view?.clearFocus()
            (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        } else {
            (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            refreshContinueWatchingOnly()
            if (DeviceMode.isTv) {
                view?.post { ensurePhimFocus() }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Activity xử lý configChanges nên không recreate: tự cập nhật số cột lưới tìm kiếm khi xoay máy
        if (::rvSearchGrid.isInitialized) {
            val lm = rvSearchGrid.layoutManager as? GridLayoutManager
            val target = DeviceMode.getGridSpanCount(requireContext(), defaultTv = 5, phonePortrait = 3, phoneLandscape = 5)
            if (lm != null && lm.spanCount != target) lm.spanCount = target
        }
        // Lưới danh mục (nếu đang ở chế độ lưới) cũng đổi số cột khi xoay máy
        if (gridMode && ::mainList.isInitialized) {
            val glm = mainList.layoutManager as? GridLayoutManager
            val target = DeviceMode.getGridSpanCount(requireContext(), defaultTv = 5, phonePortrait = 3, phoneLandscape = 5)
            if (glm != null && glm.spanCount != target) glm.spanCount = target
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

    private fun newFilterAdapter(): FilterAdapter = FilterAdapter(
        filterCategories,
        selectedCategorySlug,
        // Hàng danh mục giờ nằm trong luồng cuộn: DPAD_DOWN để focus tự di chuyển xuống hàng phim bên dưới
        onDown = { false }
    ) { catSlug ->
        if (catSlug == "tim-kiem") {
            openSearchOverlay()
        } else {
            selectedCategorySlug = catSlug
            loadFilmsByCategory(catSlug)
        }
    }

    fun ensurePhimFocus(): Boolean {
        // Điện thoại dùng cảm ứng: không ép focus (tránh nhảy cuộn / cướp focus khi quay lại tab)
        if (DeviceMode.isPhone) return false
        if (!isAdded || view == null || isHidden) return false
        (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val cur = view?.findFocus()
        if (cur != null && cur !== view) return true

        if (::phimSearchOverlay.isInitialized && phimSearchOverlay.visibility == View.VISIBLE) {
            if (etSearchPhim.requestFocus()) return true
            return false
        }

        if (focusMainListFromFilter()) return true

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
        // Chế độ lưới có adapter/dữ liệu riêng: không dựng lại hub rail ở đây
        if (gridMode) return
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
                    // Lịch sử không lưu năm/chất lượng/ngôn ngữ: để trống tường minh để
                    // thẻ không hiện mặc định bịa ("HD"/"Vietsub") của data class
                    quality = "",
                    language = "",
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
            // Sửa 2026-11-04 (focus): trước đây hàm này LUÔN thay mainList.adapter
            // ngay cả khi dữ liệu tiếp tục xem / đã lưu không đổi — ViewHolder của
            // thẻ đang focus bị hủy và tạo lại, focus + hiệu ứng phóng to rơi rụng
            // (đây là nguyên nhân chính của "đóng dialog xong thẻ thu nhỏ nhưng
            // vẫn viền cam"). Không đổi thì giữ nguyên adapter, focus không động vào.
            val sectionsUnchanged = sections.size == newSections.size &&
                sections.zip(newSections).all { (old, new) ->
                    old.title == new.title && old.films.map { it.slug } == new.films.map { it.slug }
                }
            if (sectionsUnchanged) return@launch
            sections = newSections
            // Giữ vị trí cuộn dọc của danh sách chính qua lần thay adapter này (04-H3):
            // trước đây mỗi lần quay lại tab / đóng dialog là danh sách nhảy về hero.
            val mainLmState = (mainList.layoutManager as? LinearLayoutManager)?.onSaveInstanceState()
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
                onFilmLongClick = { film -> showFilmOptionDialog(film) },
                onHeroNext = { cycleHeroNext() },
                filterAdapter = filterAdapter,
                onUp = { false },
                railScrollStates = railScrollStates
            )
            if (mainLmState != null) {
                (mainList.layoutManager as? LinearLayoutManager)?.onRestoreInstanceState(mainLmState)
            }
            // Sau khi thay adapter, KHÔNG gọi ensurePhimFocus() nữa: hàm đó nhảy
            // về nút hero khi chưa thấy focus, cướp mất thẻ vừa mở dialog. Nếu
            // đang chờ khôi phục poster thì khôi phục đúng thẻ đó, còn không
            // thì giữ nguyên focus hiện tại.
            mainList.postDelayed({
                if (pendingPosterRestore != null) restorePosterFocusDelayed()
            }, 100L)
        }
    }

    private fun showFilmOptionDialog(film: NguoncFilm) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val isFav = withContext(Dispatchers.IO) { WatchHistoryManager.isFavorite(film.slug) }
            val progress = withContext(Dispatchers.IO) {
                film.watchProgress ?: WatchHistoryManager.getProgressForFilm(film.slug)
            }
            val hasProg = progress != null || withContext(Dispatchers.IO) { WatchHistoryManager.hasProgress(film.slug) }

            val options = mutableListOf<String>()
            val actions = mutableListOf<() -> Unit>()

            // 1. Mở danh sách tập phim
            options.add("Xem danh sách tập")
            actions.add { openFilmDialog(film) }

            // 2. Lưu / Bỏ lưu yêu thích
            val favText = if (isFav) "Bỏ lưu khỏi Phim Yêu Thích" else "Lưu vào Phim Yêu Thích"
            options.add(favText)
            actions.add { toggleFilmFavorite(film) }

            // 3. Xóa khỏi lịch sử / tiếp tục xem — chỉ khi thật sự có tiến độ để xoá
            // (trước đây hiện cả với phim chỉ-đã-lưu: bấm xoá báo thành công nhưng
            // không có gì thay đổi vì deleteProgress giữ mục yêu thích). (v0.1.4)
            if (hasProg) {
                options.add("Xóa khỏi Lịch Sử & Tiến Độ Xem")
                actions.add {
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            WatchHistoryManager.deleteProgress(film.slug)
                        }
                        android.widget.Toast.makeText(
                            ctx,
                            "Đã xóa \"${film.name}\" khỏi lịch sử xem",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        loadFilmsByCategory(selectedCategorySlug)
                    }
                }
            }

            android.app.AlertDialog.Builder(ctx, R.style.Theme_KenhLive_Dialog)
                .setTitle(film.name)
                .setItems(options.toTypedArray()) { d, which ->
                    d.dismiss()
                    if (which in actions.indices) {
                        actions[which].invoke()
                    }
                }
                .setNegativeButton("Đóng", null)
                .show()
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
                    onFilmLongClick = { film -> showFilmOptionDialog(film) }
                )
            }
        }
        etSearchPhim.requestFocus()
    }

    private fun closeSearchOverlay() {
        // Đóng overlay: hủy tìm kiếm đang chạy dở để kết quả về trễ không ghi vào UI nữa
        searchJob?.cancel()
        searchGeneration++
        phimSearchOverlay.visibility = View.GONE
        mainList.visibility = View.VISIBLE
        searchBackCallback.isEnabled = false
        (activity as? MainActivity)?.hideKeyboard()

        if (selectedCategorySlug == "tim-kiem") {
            selectedCategorySlug = "tat-ca"
            filterAdapter?.notifyDataSetChanged()
        }
        mainList.requestFocus()
    }

    private fun executeNetflixSearch(keyword: String) {
        val q = keyword.trim()
        if (q.isBlank()) return
        (activity as? MainActivity)?.hideKeyboard()

        pbSearchPhim.visibility = View.VISIBLE
        tvSearchResultsStatus.text = "Đang tìm kiếm: \"$q\"..."

        // Chống race (05-H1): hủy job của từ khóa trước + tăng generation; kết quả về
        // trễ của từ khóa cũ bị bỏ qua, không ghi đè kết quả/trạng thái của từ khóa mới.
        searchJob?.cancel()
        val gen = ++searchGeneration
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                NguoncRepository.searchFilms(q, requireContext())
            }
            if (gen != searchGeneration) return@launch
            pbSearchPhim.visibility = View.GONE

            if (results.isNotEmpty()) {
                tvSearchResultsStatus.text = "Tìm thấy ${results.size} kết quả cho \"$q\""
                rvSearchGrid.visibility = View.VISIBLE
                llSearchEmpty.visibility = View.GONE
                rvSearchGrid.adapter = NetflixSearchGridAdapter(
                    films = results,
                    onFilmClick = { film -> openFilmDialog(film) },
                    onFilmLongClick = { film -> showFilmOptionDialog(film) }
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

        // Danh mục duyệt phim cụ thể (loại / thể loại / quốc gia) -> LƯỚI DỌC phân trang.
        // "Tất Cả" giữ hub rail; "Lịch Sử Xem" / "Tìm Kiếm" / "Mới Cập Nhật" giữ hành vi hiện tại.
        if (isGridCategory(categorySlug)) {
            loadGridCategory(categorySlug)
            return
        }
        if (gridMode) exitGridMode()

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
                        // Như ở refreshContinueWatchingOnly: lịch sử không có
                        // chất lượng/ngôn ngữ thật -> để trống, không hiện mặc định bịa
                        quality = "",
                        language = "",
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
                            quality = "",
                            language = "",
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
                            quality = "",
                            language = "",
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
                            quality = "",
                            language = "",
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
                            "Phim Mới Cập Nhật" to defNew.await(),
                            "Phim Bộ Hot Đang Chiếu" to defBo.await(),
                            "Điện Ảnh Chiếu Rạp Đặc Sắc" to defLe.await(),
                            "Anime & Hoạt Hình Tuyển Chọn" to defAnime.await(),
                            "Phim Truyền Hình Hàn Quốc" to defKorea.await(),
                            "Phim Cổ Trang & Hoa Ngữ" to defChina.await(),
                            "Điện Ảnh Âu Mỹ & Hollywood" to defWestern.await(),
                            "Phim Hành Động Kịch Tính" to defAction.await(),
                            "Phim Tình Cảm & Lãng Mạn" to defRomance.await(),
                            "Cổ Trang & Tiên Hiệp" to defHistorical.await(),
                            "Kinh Dị & Giật Gân" to defHorror.await()
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
                    onFilmLongClick = { film -> showFilmOptionDialog(film) },
                    onHeroNext = { cycleHeroNext() },
                    filterAdapter = filterAdapter,
                    onUp = { false },
                    railScrollStates = railScrollStates
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
                        filterAdapter = newFilterAdapter()
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

    // ===== CHẾ ĐỘ LƯỚI DỌC CHO 1 DANH MỤC CỤ THỂ =====

    /**
     * Filter có vào chế độ lưới không: mọi danh mục duyệt phim cụ thể (loại / thể loại / quốc gia).
     * "tat-ca" (hub rail), "da-luu", "tim-kiem" và "phim-moi" giữ nguyên hành vi hiện tại.
     */
    private fun isGridCategory(slug: String): Boolean =
        slug !in setOf("tat-ca", "da-luu", "tim-kiem", "phim-moi")

    /**
     * Map filter -> API phân trang, bám đúng dispatch hiện tại của các rail trong
     * loadFilmsByCategory: quốc gia kiểm tra trước; nhóm loại đi qua fetchFilmsByType
     * (batch isCategory=false); còn lại là thể loại qua fetchFilmsByCategory (isCategory=true).
     */
    /**
     * Nạp 1 trang lưới qua biến thể Strict của repository: lỗi mạng/cầu dao được NÉM ra
     * (không nuốt thành list rỗng) để nơi gọi phân biệt "lỗi tải tạm thời" với
     * "trang cuối thật" (trả list rỗng). Xem [loadNextGridPage] (05-H2).
     */
    private suspend fun fetchGridPage(slug: String, page: Int): List<NguoncFilm> {
        return when {
            slug in gridCountrySlugs -> NguoncRepository.fetchFilmsByCountryStrict(slug, page)
            slug in gridTypeSlugs -> NguoncRepository.fetchFilmsByTypeStrict(slug, page)
            else -> NguoncRepository.fetchFilmsByCategoryStrict(slug, page)
        }
    }

    /** Hành vi bấm 1 poster ở hub: có tiến độ xem dở thì phát tiếp, không thì mở hộp thoại phim. */
    private fun onHubFilmClick(film: NguoncFilm) {
        val progress = film.watchProgress
        if (progress != null && progress.embedUrl.isNotBlank()) {
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
        } else {
            openFilmDialog(film)
        }
    }

    private fun applyGridLayoutManager() {
        val span = DeviceMode.getGridSpanCount(requireContext(), defaultTv = 5, phonePortrait = 3, phoneLandscape = 5)
        val glm = GridLayoutManager(requireContext(), span)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val ad = mainList.adapter as? PhimMainAdapter ?: return glm.spanCount
                // Poster chiếm 1 cột; HERO / FILTERS / ROW / LOADING chiếm trọn hàng
                return if (ad.isPosterPosition(position)) 1 else glm.spanCount
            }
        }
        mainList.layoutManager = glm
        mainList.itemAnimator = null
        // Máy yếu: giữ cache view vừa phải cho lưới, không preload quá đà
        mainList.setItemViewCacheSize(10)
    }

    /** Thoát chế độ lưới về hub rail: khôi phục LinearLayoutManager, hủy các trang đang tải dở. */
    private fun exitGridMode() {
        gridMode = false
        gridGeneration++
        gridLoading = false
        // Không clear gridFilms tại đây: adapter lưới cũ còn gắn tới khi adapter rail mới
        // được set (bất đồng bộ) — clear ngay sẽ làm itemCount của nó tụt giữa chừng.
        // Danh sách sẽ được làm mới ở lần vào lưới tiếp theo trong loadGridCategory.
        mainList.layoutManager = LinearLayoutManager(requireContext())
        mainList.setItemViewCacheSize(2)
        mainList.scrollToPosition(0)
    }

    private fun setGridFooterVisible(visible: Boolean) {
        val ad = mainList.adapter as? PhimMainAdapter ?: return
        if (ad.gridFooterVisible == visible) return
        ad.gridFooterVisible = visible
        val pos = ad.posterStartIndex() + gridFilms.size
        if (visible) ad.notifyItemInserted(pos) else ad.notifyItemRemoved(pos)
    }

    /** Vào chế độ lưới cho [slug]: bỏ hero, chỉ còn hàng FILTERS + poster phân trang từ trang 1. */
    private fun loadGridCategory(slug: String) {
        gridMode = true
        gridSlug = slug
        val gen = ++gridGeneration
        gridFilms.clear()
        gridSeenSlugs.clear()
        gridPage = 0
        gridLastPageSize = 0
        gridEndReached = false
        gridLoading = true

        errorLayout.visibility = View.GONE
        loadingView.visibility = View.GONE
        mainList.visibility = View.VISIBLE
        applyGridLayoutManager()
        val adapter = PhimMainAdapter(
            hero = null,
            sections = emptyList(),
            onFilmClick = { film -> onHubFilmClick(film) },
            onHeroPlay = { film -> openHeroPlay(film) },
            onFavoriteToggle = { film -> toggleFilmFavorite(film) },
            onFilmLongClick = { film -> showFilmOptionDialog(film) },
            onHeroNext = { cycleHeroNext() },
            filterAdapter = filterAdapter,
            onUp = { false },
            gridFilms = gridFilms,
            gridMode = true,
            gridFooter = true
        )
        mainList.adapter = adapter
        mainList.scrollToPosition(0)

        viewLifecycleOwner.lifecycleScope.launch {
            // Trang 1: phân biệt lỗi tải (ném ngoại lệ) với danh mục rỗng thật
            var loadFailed = false
            val films = try {
                withContext(Dispatchers.IO) { fetchGridPage(slug, 1) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                loadFailed = true
                emptyList()
            }
            if (gen != gridGeneration || !gridMode || slug != gridSlug) return@launch
            gridLoading = false
            if (films.isEmpty()) {
                mainList.visibility = View.GONE
                errorLayout.visibility = View.VISIBLE
                ivEmptyIll.visibility = View.GONE
                if (loadFailed) {
                    tvErrorTitle.text = "Không thể tải kho phim"
                    tvErrorSub.text = "Kiểm tra kết nối mạng hoặc thử lại sau"
                } else {
                    tvErrorTitle.text = "Danh mục chưa có phim"
                    tvErrorSub.text = "Danh mục này hiện chưa có nội dung, thử danh mục khác nhé"
                }
                btnRetry.text = "Thử lại"
                btnRetry.setOnClickListener { loadFilmsByCategory(selectedCategorySlug) }
                return@launch
            }
            val fresh = films.filter { gridSeenSlugs.add(it.slug) }
            gridFilms.addAll(fresh)
            gridPage = 1
            gridLastPageSize = films.size
            setGridFooterVisible(false)
            if (fresh.isNotEmpty()) {
                adapter.notifyItemRangeInserted(adapter.posterStartIndex(), fresh.size)
            }
        }
    }

    /**
     * Tải trang tiếp theo và NỐI vào lưới. Điều kiện dừng (bám quy ước repository,
     * trang đầy = limit 36 của KKPhim): trang trả về rỗng, hoặc ít hơn trang trước,
     * hoặc toàn bộ là phim trùng đã có.
     */
    private fun loadNextGridPage() {
        if (!gridMode || gridLoading || gridEndReached) return
        val slug = gridSlug
        val gen = gridGeneration
        val adapter = mainList.adapter as? PhimMainAdapter ?: return
        gridLoading = true
        setGridFooterVisible(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val films = try {
                withContext(Dispatchers.IO) { fetchGridPage(slug, gridPage + 1) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Lỗi tải TẠM THỜI không phải trang cuối thật (05-H2): KHÔNG đặt
                // gridEndReached — giữ nguyên trạng thái để lần cuộn tới thử lại được;
                // tắt footer + báo nhẹ. Kết quả của filter cũ (gen lệch) thì bỏ qua.
                if (gen != gridGeneration || !gridMode || slug != gridSlug) return@launch
                gridLoading = false
                setGridFooterVisible(false)
                if (isAdded) {
                    android.widget.Toast.makeText(
                        requireContext(),
                        "Không tải thêm được phim — cuộn xuống để thử lại",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }
            if (gen != gridGeneration || !gridMode || slug != gridSlug) return@launch
            gridLoading = false
            setGridFooterVisible(false)
            if (films.isEmpty()) {
                gridEndReached = true
                return@launch
            }
            if (gridLastPageSize > 0 && films.size < gridLastPageSize) gridEndReached = true
            gridLastPageSize = films.size
            gridPage += 1
            val fresh = films.filter { gridSeenSlugs.add(it.slug) }
            if (fresh.isEmpty()) {
                gridEndReached = true
                return@launch
            }
            val start = adapter.posterStartIndex() + gridFilms.size
            gridFilms.addAll(fresh)
            adapter.notifyItemRangeInserted(start, fresh.size)
        }
    }

    fun refresh() {
        if (!isAdded) return
        loadFilmsByCategory(selectedCategorySlug)
    }

    // Focus restore sau khi đóng EpisodePicker: poster đang mở dialog (vị trí section/card).
    // Trước đây onDismiss tải lại TOÀN BỘ danh mục + thay adapter mới -> focus rơi về hero.
    private data class PosterFocusTarget(val slug: String, val sectionPos: Int, val cardIdx: Int)
    private var pendingPosterRestore: PosterFocusTarget? = null

    /** Chụp vị trí poster đang focus (chỉ khi focus hiện nằm trong một carousel của mainList). */
    private fun capturePosterFocus(film: NguoncFilm): PosterFocusTarget? {
        if (!DeviceMode.isTv || !::mainList.isInitialized) return null
        var v: View? = view?.findFocus() ?: return null
        var cardIdx = -1
        var sectionPos = -1
        while (v != null) {
            val parent = v.parent as? View ?: break
            if (parent is RecyclerView && parent !== mainList && cardIdx < 0) {
                cardIdx = parent.getChildAdapterPosition(v)
            }
            if (parent === mainList) {
                sectionPos = mainList.getChildAdapterPosition(v)
                break
            }
            v = parent
        }
        if (sectionPos < 0 || cardIdx < 0) return null
        return PosterFocusTarget(film.slug, sectionPos, cardIdx)
    }

    /** Thử đưa focus về poster đã chụp; kết thúc khi đạt đích hoặc hết lượt thử (~5s).
     *  Sửa 2026-11-04 (focus): bản cũ coi "focus đang ở BẤT KỲ poster nào trong
     *  carousel" là đã khôi phục, nên nếu sau khi đóng dialog focus rơi sang thẻ
     *  khác / pill / hero thì vẫn dừng retry, thẻ gốc không bao giờ nhận lại focus
     *  thật. Giờ chỉ chấp nhận đúng thẻ đã chụp (cùng sectionPos + cardIdx).
     *  Ngoài ra, nếu thẻ đích đã có focus nhưng hiệu ứng phóng to bị mất (do
     *  adapter bị thay sau khi đóng dialog, listener focus gắn lại sau khi view
     *  đã focus nên không chạy), ép chạy lại listener bằng clearFocus +
     *  requestFocus để scale 1.075 + viền cam quay lại cùng focus thật. */
    private fun tryRestorePosterFocus(attempt: Int) {
        val target = pendingPosterRestore ?: return
        if (!isAdded || !::mainList.isInitialized) { pendingPosterRestore = null; return }
        val rowVh = mainList.findViewHolderForAdapterPosition(target.sectionPos) as? RowVH
        val carousel = rowVh?.carousel
        val card = carousel?.let {
            it.findViewHolderForAdapterPosition(target.cardIdx)?.itemView
                ?: it.layoutManager?.findViewByPosition(target.cardIdx)
        }
        if (card != null) {
            if (card.hasFocus()) {
                // Đã đúng thẻ nhưng có thể mất hiệu ứng: chạy lại focus listener.
                card.clearFocus()
                card.post {
                    if (pendingPosterRestore != null) {
                        if (card.requestFocus()) {
                            pendingPosterRestore = null
                        } else {
                            mainList.postDelayed({ tryRestorePosterFocus(0) }, 200)
                        }
                    }
                }
                return
            }
            if (card.requestFocus()) { pendingPosterRestore = null; return }
        }
        carousel?.scrollToPosition(target.cardIdx)
        mainList.scrollToPosition(target.sectionPos)
        if (attempt >= 24) { pendingPosterRestore = null; return }
        mainList.postDelayed({ tryRestorePosterFocus(attempt + 1) }, 200)
    }

    private fun restorePosterFocusDelayed() {
        if (pendingPosterRestore == null || !::mainList.isInitialized) return
        mainList.postDelayed({ tryRestorePosterFocus(0) }, 250)
    }

    private fun openFilmDialog(film: NguoncFilm) {
        val ctx = context ?: return
        pendingPosterRestore = capturePosterFocus(film)
        try {
            dialog?.dismiss()
            dialog = EpisodePickerDialog.show(
                context = ctx,
                scope = viewLifecycleOwner.lifecycleScope,
                film = film,
                onDismissCallback = {
                    // Chỉ cập nhật DỮ LIỆU (tiếp tục xem / đã lưu), KHÔNG tải lại toàn bộ danh mục
                    // như trước -> adapter không bị thay -> focus không rơi về hero;
                    // sau đó khôi phục focus về đúng poster vừa mở dialog.
                    if (isAdded) {
                        refreshContinueWatchingOnly()
                        restorePosterFocusDelayed()
                    }
                },
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
        try {
            android.widget.Toast.makeText(ctx, "Đang mở: ${film.name}...", android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {}
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
                openFilmDialog(film)
            }
        }
    }

    private fun playEpisode(film: NguoncFilm, episode: NguoncEpisodeItem, startPosMs: Long = 0L) {
        val ctx = context ?: return
        try {
            val epTitle = if (episode.name.all { it.isDigit() }) "Tập ${episode.name}" else episode.name
            // Ưu tiên native: m3u8 sẵn có → link tách từ embed bọc (?url=<m3u8>, giống iOS)
            // → embed tự nó là link HLS. Chỉ khi không có đường nào mới mở trình xem web.
            val unwrapped = unwrapDirectM3u8(episode.embed)
            val directM3u8 = when {
                episode.m3u8.isNotBlank() -> episode.m3u8
                unwrapped.isNotBlank() -> unwrapped
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
        // Adapter giữ trạng thái hero (04-H2): updateHero + notifyItemChanged(0) bind lại
        // đúng phim mới dù HeroVH còn hiện hay đã bị recycle. Cách cũ rebind tay VH /
        // notify trên adapter vẫn giữ phim đầu -> hero nhảy ngược về phim 1 sau recycle.
        val adapter = mainList.adapter as? PhimMainAdapter
        if (adapter != null) {
            adapter.updateHero(nextFilm, currentHeroIdx, featuredFilms.size)
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

            // Sửa 2026-11-04 (selected vs focused): bản cũ gán cho pill CHƯA chọn
            // một drawable tĩnh (sl_apple_pill, kính mờ) nên khi focus pill đó chỉ
            // phóng to 1.08 mà không đổi màu/viền — từ xa gần như không thấy focus.
            // Giờ pill chưa chọn dùng selector: chưa focus = kính mờ, focus = kính
            // sáng + viền trắng 2dp. Pill ĐÃ chọn khi chưa focus dùng cam trầm
            // (#CC7A00 trong bg_apple_pill_focused), khi focus mới lên cam sáng
            // #FF9F0A + viền trắng 2.5dp, nên chỉ có đúng một điểm sáng nhất là focus.
            if (isSelected) {
                holder.tv.setBackgroundResource(R.drawable.sl_apple_pill_selected)
                holder.tv.setTextColor(0xFF000000.toInt())
            } else {
                holder.tv.setBackgroundResource(R.drawable.sl_apple_pill_unselected)
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

            // TV focus animation (cancel để không dồn animation khi bấm nhanh)
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                v.animate().cancel()
                v.animate().scaleX(if (hasFocus) 1.08f else 1f).scaleY(if (hasFocus) 1.08f else 1f)
                    .setDuration(120).start()
            }
        }

        override fun getItemCount(): Int = categories.size
    }

    // ===== ADAPTER CHO VERTICAL RECYCLERVIEW =====

    /** Hàng danh mục trong luồng cuộn (dưới hero): chứa RecyclerView ngang gắn FilterAdapter dùng chung. */
    private class FilterRowVH(v: View) : RecyclerView.ViewHolder(v) {
        private val rv: RecyclerView = v.findViewById(R.id.rvFilterRow)

        init {
            rv.layoutManager = LinearLayoutManager(v.context, LinearLayoutManager.HORIZONTAL, false)
            rv.itemAnimator = null
        }

        fun bind(adapter: FilterAdapter?) {
            if (adapter != null && rv.adapter !== adapter) rv.adapter = adapter
        }
    }

    private class PhimMainAdapter(
        private val hero: NguoncFilm?,
        private val heroIdx: Int = 0,
        private val totalHero: Int = 1,
        private val sections: List<PhimSection>,
        private val onFilmClick: (NguoncFilm) -> Unit,
        private val onHeroPlay: (NguoncFilm) -> Unit,
        private val onFavoriteToggle: (NguoncFilm) -> Unit,
        // Nhấn-giữ trên thẻ/poster phim: mở dialog tùy chọn (gồm Xóa Lịch Sử).
        // Tách riêng khỏi onFavoriteToggle (nút Lưu của Hero) — trước đây dùng chung
        // một callback nên ở đường nạp chính, nhấn-giữ thẻ chỉ lặng lẽ lưu/bỏ lưu
        // yêu thích, dialog không bao giờ mở -> người dùng không xoá được lịch sử.
        private val onFilmLongClick: (NguoncFilm) -> Unit,
        private val onHeroNext: () -> Unit = {},
        private val onUp: () -> Boolean = { false },
        private val filterAdapter: FilterAdapter? = null,
        // Chế độ lưới dọc: danh sách poster dùng chung tham chiếu với PhimFragment để nối trang
        private val gridFilms: List<NguoncFilm> = emptyList(),
        private val gridMode: Boolean = false,
        gridFooter: Boolean = false,
        // Vị trí cuộn ngang của từng rail, dùng chung với PhimFragment (sống qua các lần
        // thay adapter) để khôi phục khi rail được bind lại (04-H3)
        private val railScrollStates: MutableMap<String, Parcelable> = mutableMapOf()
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_HERO = 0
            private const val TYPE_ROW = 1
            private const val TYPE_FILTERS = 2
            private const val TYPE_POSTER = 3
            private const val TYPE_LOADING = 4
        }

        /** Hàng "đang tải thêm" ở cuối lưới (chỉ có ý nghĩa khi gridMode = true). */
        var gridFooterVisible: Boolean = gridFooter

        // Trạng thái spotlight hero: sau khi khởi tạo, ADAPTER là nguồn sự thật duy nhất.
        // Fragment đổi phim qua updateHero() thay vì rebind tay như trước — cách cũ làm
        // hero nhảy ngược về phim đầu của adapter mỗi khi HeroVH bị recycle (04-H2).
        private var currentHero: NguoncFilm? = hero
        private var currentHeroIdx: Int = heroIdx
        private var currentTotalHero: Int = totalHero

        /** Đổi phim spotlight: bind lại vị trí 0 bằng phim mới, đúng kể cả khi HeroVH đã recycle. */
        fun updateHero(film: NguoncFilm, idx: Int, total: Int) {
            if (gridMode) return
            currentHero = film
            currentHeroIdx = idx
            currentTotalHero = total
            notifyItemChanged(0)
        }

        /** Khóa ổn định cho vị trí cuộn của 1 rail: tiêu đề bỏ hậu tố đếm "(N)" hay thay đổi theo số lượng. */
        private fun railKey(section: PhimSection): String =
            (if (section.isContinueWatching) "cw:" else "row:") + section.title.substringBefore(" (")

        private val headerCount: Int
            get() = (if (currentHero != null) 1 else 0) + (if (filterAdapter != null) 1 else 0)

        // FPT/Netflix standard: Shared RecycledViewPool across all carousel rows cuts 60% view memory
        private val sharedRowPool = RecyclerView.RecycledViewPool().apply {
            setMaxRecycledViews(0, 20)
        }

        override fun getItemViewType(position: Int): Int = when {
            position == 0 && currentHero != null -> TYPE_HERO
            position == (if (currentHero != null) 1 else 0) && filterAdapter != null -> TYPE_FILTERS
            gridMode && gridFooterVisible && position == headerCount + gridFilms.size -> TYPE_LOADING
            gridMode && position >= headerCount -> TYPE_POSTER
            else -> TYPE_ROW
        }

        /** Vị trí này có phải 1 poster trong lưới không (dùng cho spanSizeLookup của GridLayoutManager). */
        fun isPosterPosition(position: Int): Boolean =
            gridMode && position >= headerCount && position < headerCount + gridFilms.size

        /** Chỉ số adapter của poster đầu tiên trong lưới. */
        fun posterStartIndex(): Int = headerCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HERO -> {
                    val v = inf.inflate(R.layout.view_phim_hero, parent, false)
                    HeroVH(v)
                }
                TYPE_FILTERS -> {
                    val v = inf.inflate(R.layout.item_phim_filter_row, parent, false)
                    FilterRowVH(v)
                }
                TYPE_POSTER -> {
                    val v = inf.inflate(R.layout.item_phim_grid_card, parent, false)
                    GridPosterVH(v)
                }
                TYPE_LOADING -> {
                    // Hàng spinner "đang tải thêm" dựng bằng code để khỏi thêm file layout
                    val container = FrameLayout(parent.context).apply {
                        layoutParams = RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            (64 * parent.context.resources.displayMetrics.density).toInt()
                        )
                        addView(
                            ProgressBar(parent.context),
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.Gravity.CENTER
                            )
                        )
                    }
                    LoadingVH(container)
                }
                else -> {
                    val v = inf.inflate(R.layout.item_phim_row, parent, false)
                    RowVH(v, sharedRowPool)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val heroNow = currentHero
            if (holder is HeroVH && heroNow != null) {
                holder.bind(heroNow, currentHeroIdx, currentTotalHero, onFilmClick, onHeroPlay, onFavoriteToggle, onHeroNext, onUp)
            } else if (holder is FilterRowVH) {
                holder.bind(filterAdapter)
            } else if (holder is GridPosterVH) {
                val gridIdx = position - headerCount
                if (gridIdx in gridFilms.indices) {
                    holder.bind(gridFilms[gridIdx], onFilmClick, onFilmLongClick)
                }
            } else if (holder is RowVH) {
                val sectionIdx = position - headerCount
                if (sectionIdx in sections.indices) {
                    val section = sections[sectionIdx]
                    holder.bind(section, onFilmClick, onFavoriteToggle, onFilmLongClick, railKey(section), railScrollStates)
                }
            }
        }

        override fun getItemCount(): Int =
            if (gridMode) headerCount + gridFilms.size + (if (gridFooterVisible) 1 else 0)
            else headerCount + sections.size
    }

    private class HeroVH(v: View) : RecyclerView.ViewHolder(v) {
        val backdrop: ImageView = v.findViewById(R.id.heroBackdrop)
        val title: TextView = v.findViewById(R.id.heroTitle)
        val year: TextView = v.findViewById(R.id.heroYear)
        val quality: TextView = v.findViewById(R.id.heroQuality)
        val desc: TextView = v.findViewById(R.id.heroDesc)
        val genre: TextView? = v.findViewById(R.id.heroGenre)
        val spotlightCounter: TextView? = v.findViewById(R.id.heroSpotlightCounter)
        val brandTag: TextView? = v.findViewById(R.id.heroBrandTag)
        val episodeCount: TextView? = v.findViewById(R.id.heroEpisodeCount)
        val trendingTag: View? = v.findViewById(R.id.heroTrendingTag)
        val trendingTitle: TextView? = v.findViewById(R.id.heroTrendingTitle)
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
            onHeroNext: () -> Unit,
            onUp: () -> Boolean = { false }
        ) {
            // Chuỗi focus dọc trong hero: hàng nút CTA --UP--> chip "VỀ TRANG CHỦ" --UP--> thanh tab.
            // Trước đây CTA bấm UP là nhảy thẳng lên thanh tab (onUp), bỏ qua chip Về trang chủ nằm
            // ngay phía trên -> chip không bao giờ tới được bằng remote ("ko chọn được").
            val upListener = View.OnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    val chip = btnBackHome
                    if (chip != null && chip.visibility == View.VISIBLE) {
                        chip.requestFocus()
                        return@OnKeyListener true
                    }
                    if (onUp()) return@OnKeyListener true
                }
                false
            }
            btnPlay.setOnKeyListener(upListener)
            btnEpisodes.setOnKeyListener(upListener)
            btnFavorite.setOnKeyListener(upListener)
            btnNext?.setOnKeyListener(upListener)
            btnBackHome?.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        if (onUp()) return@setOnKeyListener true
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        btnPlay.requestFocus()
                        return@setOnKeyListener true
                    }
                }
                false
            }

            btnBackHome?.setOnClickListener {
                val act = itemView.context as? MainActivity
                // Phone không có Cổng Hub trong điều hướng: "Trang chủ" của phone là tab Thể thao (0)
                if (DeviceMode.isPhone) act?.openSection(0) else act?.openSection(MainActivity.TAB_HUB)
            }

            // Phone: hàng CTA 4 nút (tổng ~527dp) tràn màn hình dọc (~328dp khả dụng) khiến nút
            // cuối nằm ngoài màn hình, không chạm tới -> ẩn nút "Phim khác", thu gọn padding 3 nút còn lại.
            if (DeviceMode.isPhone) {
                btnNext?.visibility = View.GONE
                val d = itemView.resources.displayMetrics.density
                btnPlay.setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
                btnEpisodes.setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                btnFavorite.setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                btnEpisodes.text = "CHỌN TẬP"
            }
            btnBackHome?.let { setupFocusAnimation(it) }
            title.text = film.name
            // Không bịa metadata: thiếu năm/chất lượng/ngôn ngữ thì ẨN thành phần đó
            // thay vì hiện "2026"/"FHD"/"Vietsub" như dữ kiện thật (04-M2)
            year.text = film.year
            year.visibility = if (film.year.isBlank()) View.GONE else View.VISIBLE
            val qualityLine = listOf(film.quality, film.language)
                .filter { it.isNotBlank() }.joinToString(" · ")
            quality.text = qualityLine
            quality.visibility = if (qualityLine.isBlank()) View.GONE else View.VISIBLE

            val epLabel = when {
                film.currentEpisode.isNotBlank() && film.totalEpisodes.isNotBlank() && film.currentEpisode != film.totalEpisodes ->
                    "${film.currentEpisode} / ${film.totalEpisodes} Tập"
                film.currentEpisode.isNotBlank() -> film.currentEpisode
                film.totalEpisodes.isNotBlank() -> "${film.totalEpisodes} Tập"
                film.time.isNotBlank() -> film.time
                else -> ""
            }
            episodeCount?.text = epLabel
            episodeCount?.visibility = if (epLabel.isBlank()) View.GONE else View.VISIBLE

            val categoriesText = when {
                film.categories.isNotEmpty() -> film.categories.take(2).joinToString(" · ")
                film.name.contains("Tập", ignoreCase = true) || film.totalEpisodes.isNotBlank() -> "Phim Bộ Đặc Sắc"
                else -> "Điện Ảnh Chiếu Rạp"
            }
            genre?.text = categoriesText

            brandTag?.text = when {
                film.categories.any { it.contains("hoạt hình", true) || it.contains("anime", true) } -> "A N I M E   T U Y Ể N   C H Ọ N"
                film.totalEpisodes.isNotBlank() || film.currentEpisode.contains("Tập", true) -> "P H I M   B Ộ   Đ Ặ C   S Ắ C"
                film.categories.any { it.contains("chiếu rạp", true) } -> "C H I Ế U   R Ạ P   Đ Ặ C   S Ắ C"
                else -> "Đ I Ệ N   Ả N H   Đ Ặ C   S Ắ C"
            }

            if (heroIdx == 0) {
                trendingTag?.visibility = View.VISIBLE
                trendingTitle?.text = "Tác phẩm điện ảnh nổi bật hôm nay"
            } else {
                trendingTag?.visibility = View.GONE
            }

            if (totalHero > 1) {
                spotlightCounter?.text = "${heroIdx + 1}/$totalHero"
                spotlightCounter?.visibility = View.VISIBLE
            } else {
                spotlightCounter?.visibility = View.GONE
            }
            // Sửa 2026-11-04 (hero nhảy nội dung): bản cũ khi chưa có mô tả thật
            // lại hiện một câu marketing mặc định + ảnh rạp chiếu, rồi 1-2 giây
            // sau mới giật sang mô tả cốt truyện thật và ảnh phim (video 00:54-00:59).
            // Giờ không bịa mô tả nữa: chưa có thì ẩn dòng mô tả, có mô tả thật
            // mới hiện, nên hero không đổi chiều cao / đổi chữ sau khi tải.
            // Backdrop cũng vậy: không có ảnh thật thì giữ nền đen của hero thay
            // vì ảnh rạp chiếu mặc định gây cảm giác "giật" sang ảnh phim.
            if (film.description.isBlank()) {
                desc.visibility = View.GONE
            } else {
                desc.visibility = View.VISIBLE
                desc.text = film.description
            }

            // Tự động nạp chi tiết mô tả đầy đủ từ API KKPhim nếu ban đầu còn ngắn
            if (film.description.isBlank()) {
                (itemView.context as? androidx.appcompat.app.AppCompatActivity)?.lifecycleScope?.launch {
                    val detail = withContext(Dispatchers.IO) {
                        NguoncRepository.fetchFilmDetail(film.slug, itemView.context)
                    }
                    if (detail != null && detail.film.description.isNotBlank()) {
                        desc.text = detail.film.description
                        desc.visibility = View.VISIBLE
                        if (detail.film.categories.isNotEmpty()) {
                            genre?.text = detail.film.categories.take(2).joinToString(" · ")
                        }
                    }
                }
            }

            // Backdrop NGANG 16:9: ưu tiên thumbUrl (ảnh ngang của KKPhim); posterUrl là
            // ảnh DỌC 2:3, dùng cho backdrop sẽ bị crop ~62% và mờ (04-H1)
            val imgUrl = film.thumbUrl.ifBlank { film.posterUrl }
            if (imgUrl.isNotBlank()) {
                backdrop.load(imgUrl) {
                    crossfade(false)
                    // Không dùng ảnh rạp chiếu làm placeholder nữa: ảnh đó hiện 1-2s
                    // rồi mới thay bằng ảnh phim thật, tạo cảm giác hero "giật".
                    // Chưa tải xong thì backdrop để trống trên nền đen của hero.
                    error(R.drawable.hero_fallback_phim)
                }
            } else {
                backdrop.setImageDrawable(null)
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
            btnFavorite.setOnLongClickListener {
                onFavoriteToggle(film)
                true
            }
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

    private class RowVH(v: View, private val sharedPool: RecyclerView.RecycledViewPool) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val count: TextView = v.findViewById(R.id.rowCount)
        val carousel: RecyclerView = v.findViewById(R.id.rowCarousel)

        // Khóa rail mà VH này đang hiển thị; dùng để lưu vị trí cuộn trước khi bị
        // tái dùng cho rail khác / trước khi adapter bị thay (04-H3)
        private var boundRailKey: String? = null

        init {
            carousel.clipChildren = false
            carousel.clipToPadding = false
            val lm = LinearLayoutManager(v.context, LinearLayoutManager.HORIZONTAL, false)
            lm.initialPrefetchItemCount = 4
            carousel.layoutManager = lm
            carousel.setRecycledViewPool(sharedPool)
            carousel.setItemViewCacheSize(3)
        }

        fun bind(
            section: PhimSection,
            onFilmClick: (NguoncFilm) -> Unit,
            onFavoriteToggle: (NguoncFilm) -> Unit,
            onFilmLongClick: (NguoncFilm) -> Unit,
            railKey: String,
            scrollStates: MutableMap<String, Parcelable>
        ) {
            val lm = carousel.layoutManager as? LinearLayoutManager
            // VH có thể đang hiển thị một rail khác (bị recycle) hoặc cùng rail sắp được
            // bind lại sau khi thay adapter: chụp vị trí cuộn hiện tại của carousel
            // TRƯỚC khi gán adapter mới (gán adapter sẽ reset cuộn về 0) (04-H3).
            boundRailKey?.let { prevKey ->
                lm?.onSaveInstanceState()?.let { scrollStates[prevKey] = it }
            }
            boundRailKey = railKey
            title.text = section.title
            count.text = "${section.films.size} phim"
            carousel.adapter = PhimCardAdapter(section.films, onFilmClick, onFilmLongClick)
            val saved = scrollStates[railKey]
            if (saved != null) {
                lm?.onRestoreInstanceState(saved)
            } else {
                carousel.scrollToPosition(0)
            }
        }
    }

    private class PhimCardAdapter(
        private val films: List<NguoncFilm>,
        private val onFilmClick: (NguoncFilm) -> Unit,
        private val onFilmLongClick: (NguoncFilm) -> Unit
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
            holder.title.text = cardTitle(film.name)

            // Loại bỏ rating giả tạo từ hashCode - giữ card sạch sẽ chuẩn điện ảnh
            holder.ratingBadge?.visibility = View.GONE

            // Xử lý hiển thị tiến độ xem (Continue Watching hoặc Phim Đã Lưu có tiến độ)
            val progress = film.watchProgress
            if (progress != null && (progress.progressPct > 0 || progress.positionMs > 0L)) {
                holder.progressBar.visibility = View.VISIBLE
                holder.progressBar.progress = progress.progressPct.coerceAtLeast(3)
                val timeLabel = progress.formattedTimeOnly()
                val badgeText = if (timeLabel.isNotBlank()) "${progress.episodeName} · $timeLabel" else "${progress.episodeName} (${progress.progressPct}%)"
                holder.episodeBadge.visibility = View.VISIBLE
                holder.episodeBadge.text = badgeText
                holder.meta.text = progress.formattedProgress()
            } else {
                holder.progressBar.visibility = View.GONE
                // Không bịa "Full" khi không rõ số tập: thiếu cả hai thì ẩn badge tập
                val epStr = when {
                    film.currentEpisode.isNotBlank() -> film.currentEpisode
                    film.totalEpisodes.isNotBlank() -> "Tập ${film.totalEpisodes}"
                    else -> ""
                }
                if (epStr.isBlank()) {
                    holder.episodeBadge.visibility = View.GONE
                } else {
                    holder.episodeBadge.visibility = View.VISIBLE
                    holder.episodeBadge.text = epStr
                }
                // Meta chỉ gồm dữ kiện thật: thiếu năm/ngôn ngữ thì bỏ phần đó (không "2026"/"Vietsub" giả)
                holder.meta.text = listOf(film.year, film.language)
                    .filter { it.isNotBlank() }.joinToString(" · ")
            }

            // Badge chất lượng: không có dữ kiện thật thì ẩn, không hiện "FHD" giả
            if (film.quality.isBlank()) {
                holder.qualityBadge.visibility = View.GONE
            } else {
                holder.qualityBadge.visibility = View.VISIBLE
                holder.qualityBadge.text = film.quality
            }

            // Thẻ DỌC 2:3: ưu tiên posterUrl (ảnh dọc của KKPhim); thumbUrl là ảnh ngang (04-H1)
            val posterUrl = film.posterUrl.ifBlank { film.thumbUrl }
            holder.poster.load(posterUrl) {
                crossfade(false)
                placeholder(R.drawable.thumb_round_bg)
                error(R.drawable.thumb_round_bg)
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
                v.animate().scaleX(if (hasFocus) 1.075f else 1f).scaleY(if (hasFocus) 1.075f else 1f)
                    .setDuration(140).start()
                v.elevation = if (hasFocus) 14f else 0f
            }
        }

        override fun getItemCount(): Int = films.size
    }

    /**
     * Thẻ poster trong CHẾ ĐỘ LƯỚI dọc: inflate item_phim_grid_card, logic hiển thị bám
     * đúng thẻ rail (PhimCardAdapter): poster thumb, tên, năm · ngôn ngữ, badge tập,
     * badge chất lượng; badge rating bị ẩn (không bịa điểm như adapter tìm kiếm cũ).
     */
    private class GridPosterVH(v: View) : RecyclerView.ViewHolder(v) {
        val poster: ImageView = v.findViewById(R.id.gridPhimPoster)
        val title: TextView = v.findViewById(R.id.gridPhimTitle)
        val meta: TextView = v.findViewById(R.id.gridPhimMeta)
        val ratingBadge: TextView = v.findViewById(R.id.gridPhimRatingBadge)
        val qualityBadge: TextView = v.findViewById(R.id.gridPhimQualityBadge)
        val episodeBadge: TextView = v.findViewById(R.id.gridPhimEpisodeBadge)

        fun bind(
            film: NguoncFilm,
            onFilmClick: (NguoncFilm) -> Unit,
            onFilmLongClick: (NguoncFilm) -> Unit
        ) {
            title.text = cardTitle(film.name)

            // Giống thẻ rail: loại bỏ rating giả tạo -> ẩn badge điểm
            ratingBadge.visibility = View.GONE

            val progress = film.watchProgress
            if (progress != null && (progress.progressPct > 0 || progress.positionMs > 0L)) {
                val timeLabel = progress.formattedTimeOnly()
                episodeBadge.visibility = View.VISIBLE
                episodeBadge.text = if (timeLabel.isNotBlank()) {
                    "${progress.episodeName} · $timeLabel"
                } else {
                    "${progress.episodeName} (${progress.progressPct}%)"
                }
                meta.text = progress.formattedProgress()
            } else {
                // Không bịa "Full"/"2026"/"Vietsub": thiếu dữ kiện thì ẩn/bỏ phần tương ứng
                val epStr = when {
                    film.currentEpisode.isNotBlank() -> film.currentEpisode
                    film.totalEpisodes.isNotBlank() -> "Tập ${film.totalEpisodes}"
                    else -> ""
                }
                if (epStr.isBlank()) {
                    episodeBadge.visibility = View.GONE
                } else {
                    episodeBadge.visibility = View.VISIBLE
                    episodeBadge.text = epStr
                }
                meta.text = listOf(film.year, film.language)
                    .filter { it.isNotBlank() }.joinToString(" · ")
            }

            if (film.quality.isBlank()) {
                qualityBadge.visibility = View.GONE
            } else {
                qualityBadge.visibility = View.VISIBLE
                qualityBadge.text = film.quality
            }

            // Thẻ DỌC 2:3: ưu tiên posterUrl (ảnh dọc); thumbUrl là ảnh ngang (04-H1)
            val posterUrl = film.posterUrl.ifBlank { film.thumbUrl }
            poster.load(posterUrl) {
                crossfade(false)
                placeholder(R.drawable.thumb_round_bg)
                error(R.drawable.thumb_round_bg)
            }

            itemView.setOnClickListener { onFilmClick(film) }
            itemView.setOnLongClickListener {
                onFilmLongClick(film)
                true
            }

            // TV focus dynamics (Netflix Zoom Effect) — như thẻ lưới tìm kiếm
            itemView.setOnFocusChangeListener { v, hasFocus ->
                v.animate().cancel()
                v.animate().scaleX(if (hasFocus) 1.065f else 1f).scaleY(if (hasFocus) 1.065f else 1f)
                    .setDuration(140).start()
                v.elevation = if (hasFocus) 16f else 0f
            }
        }
    }

    /** ViewHolder rỗng cho hàng spinner tải thêm ở cuối lưới. */
    private class LoadingVH(v: View) : RecyclerView.ViewHolder(v)

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
            holder.title.text = cardTitle(film.name)

            // KHÔNG bịa điểm rating từ hashCode tên phim (05-H3): không có dữ liệu
            // đánh giá thật -> ẩn badge, nhất quán với GridPosterVH và thẻ rail
            holder.ratingBadge.visibility = View.GONE

            if (film.quality.isBlank()) {
                holder.qualityBadge.visibility = View.GONE
            } else {
                holder.qualityBadge.visibility = View.VISIBLE
                holder.qualityBadge.text = film.quality
            }

            val epStr = when {
                film.currentEpisode.isNotBlank() -> film.currentEpisode
                film.totalEpisodes.isNotBlank() -> "Tập ${film.totalEpisodes}"
                else -> ""
            }
            if (epStr.isBlank()) {
                holder.episodeBadge.visibility = View.GONE
            } else {
                holder.episodeBadge.visibility = View.VISIBLE
                holder.episodeBadge.text = epStr
            }
            holder.meta.text = listOf(film.year, film.language)
                .filter { it.isNotBlank() }.joinToString(" · ")

            // Thẻ DỌC 2:3: ưu tiên posterUrl (ảnh dọc); thumbUrl là ảnh ngang (04-H1)
            val posterUrl = film.posterUrl.ifBlank { film.thumbUrl }
            holder.poster.load(posterUrl) {
                crossfade(false)
                placeholder(R.drawable.thumb_round_bg)
                error(R.drawable.thumb_round_bg)
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
