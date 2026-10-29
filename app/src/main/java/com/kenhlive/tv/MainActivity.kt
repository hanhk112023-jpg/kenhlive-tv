package com.kenhlive.tv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.kenhlive.tv.iptv.IptvFragment
import com.kenhlive.tv.phim.PhimFragment
import com.kenhlive.tv.ui.applyTvDensity
import androidx.lifecycle.lifecycleScope
import com.kenhlive.tv.viewmodel.LiveViewModel
import kotlinx.coroutines.launch

/**
 * Shell ứng dụng — 1 codebase, 2 hình hài:
 * - PHONE: top app bar + bottom navigation (5 mục), fragment show/hide giữ state
 * - TV:    navigation rail trái (D-pad), cùng id/fragment → chung logic
 * Layout chọn tự động qua resource qualifier (layout/ vs layout-television/).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val TAB_HUB = 6
    }

    private val vm: LiveViewModel by viewModels()
    private var current = TAB_HUB
    private val navViews = mutableListOf<View>()
    private var railPanel: View? = null
    private var lastBackPressTime: Long = 0L
    private var backToast: android.widget.Toast? = null

    // 0: Live, 1: Schedule, 2: IPTV, 3: Search, 4: Settings, 5: Phim, 6: Home Hub
    private val tabTags = arrayOf("tab_live", "tab_schedule", "tab_iptv", "tab_search", "tab_settings", "tab_phim", "tab_hub")
    private val navIdToTab = mutableMapOf<Int, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceMode.updateMode(this)
        applyTvDensity()
        if (DeviceMode.isTv) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        setContentView(R.layout.activity_main)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(0, 0)
        }

        setupNav()

        // deep-link: --ei tab N mở thẳng tab N (0 Live / 1 Lịch / 2 IPTV / 3 Tìm / 4 Cài đặt / 5 Phim / 6 Hub)
        // Mặc định: TV mở Cổng Chọn 3 Mục (TAB_HUB = 6), Phone mở thẳng Thể thao (0) có thanh điều hướng đáy
        val tabX = intent?.getIntExtra("tab", -1) ?: -1
        val initialTab = if (tabX in 0..6) tabX else if (DeviceMode.isPhone) 0 else TAB_HUB
        showTab(initialTab, animate = false)

        // BACK:
        // - Trên Phone: nếu đang ở tab khác thì BACK đưa về Tab 0 (Thể thao). Nếu ở Tab 0 thì hiện hộp thoại thoát.
        // - Trên TV: đang ở mục con thì ấn 2 lần BACK liên tiếp để thoát ra Menu Chọn (TAB_HUB).
        onBackPressedDispatcher.addCallback(this) {
            if (DeviceMode.isPhone && current != 0) {
                showTab(0)
                return@addCallback
            }
            if (DeviceMode.isTv && current != TAB_HUB) {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 3500L) {
                    backToast?.cancel()
                    lastBackPressTime = 0L
                    showTab(TAB_HUB)
                    return@addCallback
                } else {
                    lastBackPressTime = now
                    backToast?.cancel()
                    backToast = android.widget.Toast.makeText(
                        this@MainActivity,
                        "Nhấn BACK lần nữa để quay lại Menu Chọn",
                        android.widget.Toast.LENGTH_SHORT
                    )
                    backToast?.show()
                    return@addCallback
                }
            }
            val d = AlertDialog.Builder(this@MainActivity, R.style.Theme_KenhLive_Dialog)
                .setTitle(R.string.dialog_exit_title)
                .setPositiveButton(R.string.dialog_exit_yes) { _, _ -> finishAffinity() }
                .setNegativeButton(R.string.dialog_exit_no, null)
                .create()
            d.setOnShowListener {
                val btnStay = d.getButton(AlertDialog.BUTTON_NEGATIVE)
                val btnExit = d.getButton(AlertDialog.BUTTON_POSITIVE)
                btnStay?.isFocusable = true
                btnExit?.isFocusable = true
                (btnStay?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    lp.rightMargin = (16 * resources.displayMetrics.density).toInt()
                    btnStay.layoutParams = lp
                }
                btnStay?.requestFocus()
            }
            d.show()
        }

        lifecycleScope.launch {
            vm.state.collect { st ->
                val tv = findViewById<TextView>(R.id.countText) ?: return@collect
                if (st is UiState.Success && st.data.isNotEmpty()) {
                    val rooms = st.data.sumOf { it.count }
                    tv.visibility = View.VISIBLE
                    tv.text = getString(R.string.live_rooms_count, rooms)
                }
            }
        }
        vm.load()

        if (DeviceMode.isTv) {
            railPanel = findViewById(R.id.railPanel)
            railPanel?.post {
                // Mặc định ban đầu ẩn sidebar về phía bên trái
                hideRail(immediate = true)
            }
        }

        UpdateManager.checkAndUpdate(this)
        UpdateManager.resumePendingInstall(this)
        handleDebugIntent(intent)
    }

    private fun setupNav() {
        val defs = listOf(
            Triple(R.id.nav_live, 0, Pair(R.drawable.ic_nav_live, R.string.nav_live)),
            Triple(R.id.nav_phim, 5, Pair(R.drawable.ic_nav_phim, R.string.nav_phim)),
            Triple(R.id.nav_schedule, 1, Pair(R.drawable.ic_nav_schedule, R.string.nav_schedule)),
            Triple(R.id.nav_search, 3, Pair(R.drawable.ic_nav_search, R.string.nav_search)),
            Triple(R.id.nav_settings, 4, Pair(R.drawable.ic_nav_settings, R.string.nav_settings))
        )

        navViews.clear()
        navIdToTab.clear()

        defs.forEach { (id, tabIdx, def) ->
            navIdToTab[id] = tabIdx
            val v = findViewById<View>(id) ?: return@forEach
            val iv = v.findViewById<ImageView>(R.id.navIcon)
            iv?.setImageResource(def.first)
            v.findViewById<TextView>(R.id.navLabel)?.setText(def.second)
            v.setOnClickListener {
                showTab(tabIdx)
                if (DeviceMode.isTv) {
                    hideRail()
                    focusContentFirst()
                }
            }
            if (DeviceMode.isTv) {
                v.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start()
                        iv?.setColorFilter(android.graphics.Color.WHITE)
                        if (current != tabIdx) {
                            showTab(tabIdx, animate = false, keepRailOpen = true)
                        }
                    } else {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        iv?.clearColorFilter()
                    }
                }
            }
            navViews.add(v)
        }

        findViewById<View>(R.id.btnTopSettings)?.setOnClickListener {
            showTab(4)
        }

        if (DeviceMode.isTv) {
            findViewById<View>(R.id.nav_profile)?.setOnClickListener {
                com.kenhlive.tv.ui.FeedbackDialog.show(this)
            }
        }
    }

    private fun focusContentFirst() {
        val activeTag = tabTags.getOrNull(current) ?: return
        val activeFragment = supportFragmentManager.findFragmentByTag(activeTag)
        val activeView = activeFragment?.view ?: return

        (activeView as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

        val cur = activeView.findFocus()
        if (cur != null && cur.isShown && isDescendant(activeView, cur)) return

        // 1. Nếu fragment hiện tại hỗ trợ khôi phục focus chuyên biệt:
        if (activeFragment is HomeHubFragment && activeFragment.ensureHubFocus()) return
        if (activeFragment is ScheduleFragment && activeFragment.focusFirstMatch()) return
        if (activeFragment is com.kenhlive.tv.iptv.IptvFragment && activeFragment.ensureIptvFocus()) return
        if (activeFragment is com.kenhlive.tv.phim.PhimFragment && activeFragment.ensurePhimFocus()) return
        if (activeFragment is SearchFragment && activeFragment.ensureSearchFocus()) return
        if (activeFragment is SettingsFragment && activeFragment.ensureSettingsFocus()) return

        // 2. Tìm view con của activeView đang hiển thị
        val rv = findFirstRecyclerView(activeView)
        if (rv != null) {
            val ad = rv.adapter as? SportAdapter
            if (ad != null && ad.focusRailFirst()) return

            for (ci in 0 until rv.childCount) {
                val cv = rv.getChildAt(ci)
                if (cv.isFocusable && cv.requestFocus()) return
                val inner = firstFocusableIn(cv)
                if (inner != null && inner.requestFocus()) return
            }

            for (pos in 0..10) {
                val vh = rv.findViewHolderForAdapterPosition(pos) ?: continue
                val inner = firstFocusableIn(vh.itemView) ?: (if (vh.itemView.isFocusable) vh.itemView else null)
                if (inner?.requestFocus() == true) return
            }

            val firstF = firstFocusableIn(rv) ?: firstFocusableIn(activeView)
            if (firstF != null && firstF.requestFocus()) return

            rv.post {
                val ad2 = rv.adapter as? SportAdapter
                if (ad2 != null && ad2.focusRailFirst()) return@post
                for (ci in 0 until rv.childCount) {
                    val cv = rv.getChildAt(ci)
                    if (cv.isFocusable && cv.requestFocus()) return@post
                    val inner = firstFocusableIn(cv)
                    if (inner != null && inner.requestFocus()) return@post
                }
                for (pos in 0..10) {
                    val vh = rv.findViewHolderForAdapterPosition(pos) ?: continue
                    val inner = firstFocusableIn(vh.itemView) ?: (if (vh.itemView.isFocusable) vh.itemView else null)
                    if (inner?.requestFocus() == true) return@post
                }
                val fallback = firstFocusableIn(rv) ?: firstFocusableIn(activeView)
                fallback?.requestFocus()
            }
            return
        }

        val target = firstFocusableIn(activeView)
        if (target != null && target.requestFocus()) return
        activeView.post { firstFocusableIn(activeView)?.requestFocus() }
    }

    private fun findFirstRecyclerView(root: View): RecyclerView? {
        if (root is RecyclerView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val r = findFirstRecyclerView(root.getChildAt(i))
                if (r != null) return r
            }
        }
        return null
    }

    /** Kiểm tra xem focus hiện tại đã sát mép trái màn hình chưa để chuyển sang rail. */
    private fun atLeftEdge(f: View): Boolean {
        val nxt = f.focusSearch(View.FOCUS_LEFT) ?: return true
        if (nxt === f) return true
        val a = IntArray(2); f.getLocationOnScreen(a)
        val b = IntArray(2); nxt.getLocationOnScreen(b)
        return b[0] >= a[0] - 4
    }

    private fun isDescendant(root: View, v: View?): Boolean {
        var p: View? = v?.parent as? View
        while (p != null) { if (p === root) return true; p = p.parent as? View }
        return false
    }

    private fun firstFocusableIn(root: View): View? {
        if (root.visibility != View.VISIBLE) return null
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val f = firstFocusableIn(root.getChildAt(i))
                if (f != null) return f
            }
        }
        return if (root.isFocusable) root else null
    }

    override fun onResume() {
        super.onResume()
        DeviceMode.updateMode(this)
        if (DeviceMode.isTv) {
            window.decorView.postDelayed({
                if (currentFocus == null) {
                    focusContentFirst()
                }
            }, 150L)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    fun isRailActive(): Boolean {
        val r = railPanel ?: return false
        return r.visibility == View.VISIBLE && (r.hasFocus() || isDescendant(r, currentFocus))
    }

    private fun showRail() {
        val r = railPanel ?: return
        r.visibility = View.VISIBLE
        (r as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        findViewById<ViewGroup>(R.id.fragmentContainer)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        r.animate().translationX(0f).alpha(1f).setDuration(180).start()
    }

    private fun hideRail(immediate: Boolean = false) {
        val r = railPanel ?: return
        val offset = -r.width.toFloat().coerceAtLeast(160f)
        (r as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        findViewById<ViewGroup>(R.id.fragmentContainer)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (immediate) {
            r.translationX = offset
            r.alpha = 0f
            r.visibility = View.GONE
        } else {
            r.animate().translationX(offset).alpha(0f).setDuration(220).withEndAction {
                r.visibility = View.GONE
            }.start()
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (DeviceMode.isTv && event.action == android.view.KeyEvent.ACTION_DOWN) {
            var f = currentFocus
            if (f == null) {
                focusContentFirst()
                f = currentFocus
                if (f == null) {
                    val decorFirst = firstFocusableIn(window.decorView)
                    decorFirst?.requestFocus()
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    fun openSection(tabIdx: Int) {
        showTab(tabIdx, animate = true)
    }

    fun showTab(pos: Int, animate: Boolean = true, keepRailOpen: Boolean = false) {
        hideKeyboard()
        current = pos
        lastBackPressTime = 0L
        backToast?.cancel()
        com.kenhlive.tv.ui.RoomPickerDialog.dismissActive()
        hideRail(immediate = true)
        if (DeviceMode.isPhone) {
            findViewById<View>(R.id.topBar)?.visibility = View.VISIBLE
            findViewById<View>(R.id.bottomNav)?.visibility = View.VISIBLE
        } else {
            findViewById<View>(R.id.topBar)?.visibility = View.GONE
            findViewById<View>(R.id.bottomNav)?.visibility = View.GONE
        }
        navViews.forEach { v ->
            val target = navIdToTab[v.id] ?: -1
            v.isSelected = (target == pos)
        }
        if (DeviceMode.isTv && keepRailOpen && pos != TAB_HUB) {
            // Khi chuyển tab: đồng bộ tiêu điểm trên rail vào đúng icon tab đang chọn
            val activeNav = navViews.firstOrNull { navIdToTab[it.id] == pos }
            if (railPanel?.let { isDescendant(it, currentFocus) } == true) {
                activeNav?.requestFocus()
            }
        }
        val tx = supportFragmentManager.beginTransaction()
        if (animate) tx.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
        tabTags.forEachIndexed { i, tag ->
            var f = supportFragmentManager.findFragmentByTag(tag)
            if (i == pos) {
                if (f == null) {
                    f = when (i) {
                        0 -> LiveFragment()
                        1 -> ScheduleFragment()
                        2 -> IptvFragment()
                        3 -> SearchFragment()
                        4 -> SettingsFragment()
                        5 -> PhimFragment()
                        6 -> HomeHubFragment()
                        else -> HomeHubFragment()
                    }
                    tx.add(R.id.fragmentContainer, f, tag)
                } else {
                    tx.show(f)
                    (f.view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                }
            } else if (f != null) {
                tx.hide(f)
                f.view?.clearFocus()
                (f.view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                f.view?.isFocusable = false
            }
        }
        tx.commitAllowingStateLoss()
        try { supportFragmentManager.executePendingTransactions() } catch (_: Throwable) {}

        tabTags.forEachIndexed { i, tag ->
            val f = supportFragmentManager.findFragmentByTag(tag) ?: return@forEachIndexed
            if (i == pos) {
                if (keepRailOpen) {
                    (f.view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                } else {
                    (f.view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                }
            } else {
                f.view?.clearFocus()
                (f.view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                f.view?.isFocusable = false
            }
        }

        val fc = findViewById<ViewGroup>(R.id.fragmentContainer)
        if (DeviceMode.isTv && keepRailOpen) {
            fc?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            val activeNav = navViews.firstOrNull { navIdToTab[it.id] == pos }
            activeNav?.requestFocus()
        } else if (DeviceMode.isTv && !keepRailOpen) {
            fc?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            fc?.post {
                hideRail()
                focusContentFirst()
            }
            fc?.postDelayed({
                if (currentFocus == null) {
                    focusContentFirst()
                }
            }, 120)
            fc?.postDelayed({
                if (currentFocus == null) {
                    focusContentFirst()
                }
            }, 300)
        }
    }

    fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager ?: return
        val token = currentFocus?.windowToken ?: window?.decorView?.windowToken
        if (token != null) {
            imm.hideSoftInputFromWindow(token, 0)
        }
    }

    // ===== debug hooks (CI screenshot / QA) =====
    private fun handleDebugIntent(i: Intent?) {
        i?.getStringExtra("open")?.let { target ->
            when (target) {
                "mv" -> showTab(TAB_HUB, animate = false)
                "pip" -> lifecycleScope.launch { openPlayer(pip = true) }
                "update" -> UpdateManager.debugForceDialog(this)
                "refresh" -> {
                    lifecycleScope.launch {
                        supportFragmentManager.fragments.filterIsInstance<LiveFragment>()
                            .firstOrNull { it.isAdded }?.debugForceRefresh()
                    }
                }
                "iptv" -> showTab(2, animate = false)
                "search" -> showTab(3, animate = false)
                "settings" -> showTab(4, animate = false)
                "phim" -> showTab(5, animate = false)
                "hub" -> showTab(TAB_HUB, animate = false)
                "player" -> lifecycleScope.launch { openPlayer(pip = false) }
                else -> {}
            }
        }
    }

    private suspend fun openPlayer(pip: Boolean) {
        try {
            val g = SocoliveRepository.groupRooms(SocoliveRepository.fetchLiveRooms()).firstOrNull()
            val r = g?.top
            if (r != null) {
                val u = SocoliveRepository.fetchStream(r.roomNum)
                if (u != null) {
                    startActivity(
                        Intent(this@MainActivity, PlayerActivity::class.java)
                            .putExtra("url", u)
                            .putExtra("name", "${r.matchTitle} · ${r.blvName}")
                            .putExtra("pip", pip)
                    )
                }
            }
        } catch (_: Exception) { }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tabX = intent.getIntExtra("tab", -1)
        if (tabX in 0..6) showTab(tabX, animate = false)
        handleDebugIntent(intent)
    }
}
