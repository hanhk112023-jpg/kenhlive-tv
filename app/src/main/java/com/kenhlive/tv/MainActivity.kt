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
    private val tabTags = arrayOf("tab_live", "tab_schedule", "tab_search", "tab_settings")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        UpdateManager.checkAndUpdate(this)
        UpdateManager.resumePendingInstall(this)
        handleDebugIntent(intent)
    }

    private fun setupNav() {
        val defs = listOf(
            R.id.nav_live to Pair(R.drawable.ic_nav_live, R.string.nav_live),
            R.id.nav_schedule to Pair(R.drawable.ic_nav_schedule, R.string.nav_schedule),
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
        // TV: rail là điểm focus đầu tiên khi mở app
        if (DeviceMode.isTv) navViews.firstOrNull()?.post { navViews.firstOrNull()?.requestFocus() }
    }

    private fun showTab(pos: Int, animate: Boolean = true) {
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
