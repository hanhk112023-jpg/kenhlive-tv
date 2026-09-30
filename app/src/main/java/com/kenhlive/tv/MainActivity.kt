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
import com.kenhlive.tv.ui.applyTvDensity
import androidx.lifecycle.lifecycleScope
import com.kenhlive.tv.viewmodel.LiveViewModel
import kotlinx.coroutines.launch

/**
 * Shell ứng dụng — 1 codebase, 2 hình hài:
 * - PHONE: top app bar + bottom navigation (4 mục), fragment show/hide giữ state
 * - TV:    navigation rail trái (D-pad), cùng id/fragment → chung logic
 * Layout chọn tự động qua resource qualifier (layout/ vs layout-television/).
 */
class MainActivity : AppCompatActivity() {

    private val vm: LiveViewModel by viewModels()
    private var current = 0
    private val navViews = mutableListOf<View>()
    private var railPanel: View? = null
    private val tabTags = arrayOf("tab_live", "tab_schedule", "tab_iptv", "tab_search", "tab_settings")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTvDensity()
        setContentView(R.layout.activity_main)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(0, 0)
        }

        setupNav()

        // deep-link: --ei tab N mở thẳng tab N (0 Live / 1 Lịch / 2 IPTV / 3 Tìm / 4 Cài đặt — CI + QA)
        val tabX = intent?.getIntExtra("tab", -1) ?: -1
        showTab(if (tabX in 0..4) tabX else 0, animate = false)

        // BACK: tab khác → về Live trước; tab Live → dialog xác nhận thoát (UX TV)
        onBackPressedDispatcher.addCallback(this) {
            if (current != 0) { showTab(0); return@addCallback }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.dialog_exit_title)
                .setPositiveButton(R.string.dialog_exit_yes) { _, _ -> finishAffinity() }
                .setNegativeButton(R.string.dialog_exit_no, null)
                .show()
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
            R.id.nav_live to Pair(R.drawable.ic_nav_live, R.string.nav_live),
            R.id.nav_schedule to Pair(if (DeviceMode.isTv) R.drawable.ic_sports else R.drawable.ic_nav_schedule, R.string.nav_schedule),
            R.id.nav_iptv to Pair(R.drawable.ic_nav_tv, R.string.nav_iptv),
            R.id.nav_search to Pair(R.drawable.ic_nav_search, R.string.nav_search),
            R.id.nav_settings to Pair(R.drawable.ic_nav_settings, R.string.nav_settings)
        )
        navViews.clear()
        defs.forEachIndexed { i, (id, def) ->
            val v = findViewById<View>(id) ?: return@forEachIndexed
            v.findViewById<ImageView>(R.id.navIcon)?.setImageResource(def.first)
            v.findViewById<TextView>(R.id.navLabel)?.setText(def.second)
            v.setOnClickListener { showTab(i) }
            if (DeviceMode.isTv) {
                v.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus && current != i) {
                        showTab(i, animate = false)
                    }
                }
            }
            navViews.add(v)
        }

        if (DeviceMode.isTv) {
            findViewById<View>(R.id.nav_profile)?.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(R.string.app_name)
                    .setMessage(R.string.settings_about_body)
                    .setPositiveButton(R.string.dialog_close, null)
                    .show()
            }
            findViewById<View>(R.id.nav_multiview)?.apply {
                findViewById<ImageView>(R.id.navIcon)?.setImageResource(R.drawable.ic_multiview)
                setOnClickListener { startActivity(Intent(this@MainActivity, MultiViewActivity::class.java)) }
            }
        }
    }

    private fun focusContentFirst() {
        val c = findViewById<ViewGroup>(R.id.fragmentContainer) ?: return
        val cur = c.findFocus()
        if (cur != null && (c === cur.rootView || isDescendant(c, cur))) return

        // Tìm view con của fragmentContainer đang hiển thị (VISIBLE & isShown)
        for (i in 0 until c.childCount) {
            val child = c.getChildAt(i)
            if (child.visibility == View.VISIBLE) {
                // Nếu fragment chứa RecyclerView: ưu tiên focus vào hàng trận đấu đầu tiên
                val rv = findFirstRecyclerView(child)
                if (rv != null) {
                    val ad = rv.adapter as? SportAdapter
                    if (ad != null && ad.focusRailFirst()) return
                    val vh = rv.findViewHolderForAdapterPosition(0)
                    if (vh != null) {
                        val inner = firstFocusableIn(vh.itemView) ?: vh.itemView
                        if (inner.requestFocus()) return
                    }
                    rv.post {
                        val ad2 = rv.adapter as? SportAdapter
                        if (ad2 != null && ad2.focusRailFirst()) return@post
                        firstFocusableIn(child)?.requestFocus()
                    }
                    return
                }

                val target = firstFocusableIn(child)
                if (target != null && target.requestFocus()) return

                child.post { firstFocusableIn(child)?.requestFocus() }
                return
            }
        }
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
            window.decorView.post {
                if (currentFocus == null) {
                    focusContentFirst()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun showRail() {
        val r = railPanel ?: return
        r.animate().translationX(0f).alpha(1f).setDuration(180).start()
    }

    private fun hideRail(immediate: Boolean = false) {
        val r = railPanel ?: return
        val offset = -r.width.toFloat().coerceAtLeast(160f)
        if (immediate) {
            r.translationX = offset
            r.alpha = 0f
        } else {
            r.animate().translationX(offset).alpha(0f).setDuration(220).start()
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (DeviceMode.isTv && event.action == android.view.KeyEvent.ACTION_DOWN) {
            var f = currentFocus
            if (f == null) {
                focusContentFirst()
                f = currentFocus
                if (f != null) return true
            }
            val insideRail = railPanel?.let { isDescendant(it, f) } == true
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (f != null && !insideRail && atLeftEdge(f)) {
                        showRail()
                        navViews.getOrNull(current)?.requestFocus() ?: navViews.firstOrNull()?.requestFocus()
                        return true
                    }
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (insideRail) {
                        // Kích hoạt tab nếu người dùng đang đứng ở item nav tương ứng
                        val focusedNavIdx = navViews.indexOfFirst { it === f || isDescendant(it, f) }
                        if (focusedNavIdx >= 0 && focusedNavIdx != current) {
                            showTab(focusedNavIdx, animate = false)
                        }
                        hideRail()
                        focusContentFirst()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    fun showTab(pos: Int, animate: Boolean = true) {
        current = pos
        navViews.forEachIndexed { i, v -> v.isSelected = i == pos }
        if (DeviceMode.isTv && pos in 0 until navViews.size) {
            // Khi chuyển tab: đồng bộ tiêu điểm trên rail vào đúng icon tab đang chọn
            navViews.getOrNull(pos)?.let { selNav ->
                if (railPanel?.let { isDescendant(it, currentFocus) } == true) {
                    selNav.requestFocus()
                }
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
                        else -> SettingsFragment()
                    }
                    tx.add(R.id.fragmentContainer, f, tag)
                } else tx.show(f)
            } else if (f != null) tx.hide(f)
        }
        tx.commit()
        if (DeviceMode.isTv) {
            findViewById<View>(R.id.fragmentContainer)?.post {
                hideRail()
                focusContentFirst()
            }
        }
    }

    fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    // ===== debug hooks (CI screenshot / QA) =====
    private fun handleDebugIntent(i: Intent?) {
        i?.getStringExtra("open")?.let { target ->
            when (target) {
                "mv" -> startActivity(
                    Intent(this, MultiViewActivity::class.java)
                        .putExtra("mv_layout", i.getIntExtra("mv_layout", 0))
                )
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
        if (tabX in 0..4) showTab(tabX, animate = false)
        handleDebugIntent(intent)
    }
}
