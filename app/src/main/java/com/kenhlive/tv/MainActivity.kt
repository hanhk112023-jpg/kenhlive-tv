package com.kenhlive.tv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
    private val tabTags = arrayOf("tab_live", "tab_schedule", "tab_search", "tab_settings")

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

        // deep-link: --ei tab N mở thẳng tab N (0 Live / 1 Lịch / 2 Tìm / 3 Cài đặt — CI + QA)
        val tabX = intent?.getIntExtra("tab", -1) ?: -1
        showTab(if (tabX in 0..3) tabX else 0, animate = false)

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
        }

        UpdateManager.checkAndUpdate(this)
        UpdateManager.resumePendingInstall(this)
        handleDebugIntent(intent)
    }

    private fun setupNav() {
        val defs = listOf(
            R.id.nav_live to Pair(R.drawable.ic_nav_live, R.string.nav_live),
            R.id.nav_schedule to Pair(if (DeviceMode.isTv) R.drawable.ic_sports else R.drawable.ic_nav_schedule, R.string.nav_schedule),
            R.id.nav_search to Pair(R.drawable.ic_nav_search, R.string.nav_search),
            R.id.nav_settings to Pair(R.drawable.ic_nav_settings, R.string.nav_settings)
        )
        navViews.clear()
        defs.forEachIndexed { i, (id, def) ->
            val v = findViewById<View>(id) ?: return@forEachIndexed
            v.findViewById<ImageView>(R.id.navIcon)?.setImageResource(def.first)
            v.findViewById<TextView>(R.id.navLabel)?.setText(def.second)
            v.setOnClickListener { showTab(i) }
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
                setOnClickListener { openMultiView() }
            }
            findViewById<View>(R.id.nav_replay)?.apply {
                findViewById<ImageView>(R.id.navIcon)?.setImageResource(R.drawable.ic_highlights)
                setOnClickListener { showTab(0) }
            }
            findViewById<View>(R.id.nav_fav)?.apply {
                findViewById<ImageView>(R.id.navIcon)?.setImageResource(R.drawable.ic_fav)
                setOnClickListener { showTab(0) }
            }
            findViewById<View>(R.id.nav_cats)?.apply {
                findViewById<ImageView>(R.id.navIcon)?.setImageResource(R.drawable.ic_cats)
                setOnClickListener { showTab(0) }
            }
        }
    }

    private fun focusContentFirst() {
        val c = findViewById<View>(R.id.fragmentContainer) ?: return
        c.findFocus()?.let { if (c === it.rootView || isDescendant(c, it)) return }
        c.post {
            val v = c.findViewWithTag<View>("kl_focus_first") ?: firstFocusableIn(c)
            v?.requestFocus()
        }
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
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val f = firstFocusableIn(root.getChildAt(i))
                if (f != null) return f
            }
        }
        return if (root.isFocusable) root else null
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (DeviceMode.isTv && event.action == android.view.KeyEvent.ACTION_DOWN) {
            val f = currentFocus
            val insideRail = railPanel?.let { isDescendant(it, f) } == true
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (f != null && !insideRail && atLeftEdge(f)) {
                        navViews.getOrNull(current)?.requestFocus() ?: navViews.firstOrNull()?.requestFocus()
                        return true
                    }
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (insideRail) {
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
        val tx = supportFragmentManager.beginTransaction()
        if (animate) tx.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
        tabTags.forEachIndexed { i, tag ->
            var f = supportFragmentManager.findFragmentByTag(tag)
            if (i == pos) {
                if (f == null) {
                    f = when (i) {
                        0 -> LiveFragment()
                        1 -> ScheduleFragment()
                        2 -> SearchFragment()
                        else -> SettingsFragment()
                    }
                    tx.add(R.id.fragmentContainer, f, tag)
                } else tx.show(f)
            } else if (f != null) tx.hide(f)
        }
        tx.commit()
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
                    showTab(0, animate = false)
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(400)
                        supportFragmentManager.fragments.filterIsInstance<LiveFragment>()
                            .firstOrNull { it.isAdded }?.debugForceRefresh()
                    }
                }
                "search" -> showTab(2, animate = false)
                "settings" -> showTab(3, animate = false)
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
        if (tabX in 0..3) showTab(tabX, animate = false)
        handleDebugIntent(intent)
    }
}
