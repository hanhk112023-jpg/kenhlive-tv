package com.kenhlive.tv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shell v5: topbar premium + ViewPager2 3 tab + low RAM handling */
class MainActivity : AppCompatActivity() {
    private lateinit var viewPager: ViewPager2
    private var navLive: View? = null
    private var navSchedule: View? = null
    private var navSearch: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.setSystemBarsAppearance(0, 0)

        viewPager = findViewById(R.id.viewPager)
        navLive = findViewById(R.id.nav_live)
        navSchedule = findViewById(R.id.nav_schedule)
        navSearch = findViewById(R.id.nav_search)

        // Reduce offscreen limit for low RAM
        val isLowRam = DeviceMode.isLowRamDevice(this)
        viewPager.offscreenPageLimit = if (isLowRam) 1 else 2

        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 3
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> LiveFragment()
                1 -> ScheduleFragment()
                else -> SearchFragment()
            }
        }
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(pos: Int) = paintNav(pos)
        })

        val clickLive = View.OnClickListener { viewPager.setCurrentItem(0, true) }
        val clickSched = View.OnClickListener { viewPager.setCurrentItem(1, true) }
        navLive?.setOnClickListener(clickLive)
        navSchedule?.setOnClickListener(clickSched)
        navSearch?.setOnClickListener { viewPager.setCurrentItem(2, true) }
        findViewById<View>(R.id.tv_live)?.setOnClickListener(clickLive)
        findViewById<View>(R.id.tv_schedule)?.setOnClickListener(clickSched)
        findViewById<View>(R.id.tv_search)?.setOnClickListener { viewPager.setCurrentItem(2, true) }

        val tabX = intent?.getIntExtra("tab", -1) ?: -1
        if (tabX in 0..2) {
            viewPager.post { viewPager.setCurrentItem(tabX, false) }
        }

        UpdateManager.checkAndUpdate(this)
        UpdateManager.resumePendingInstall(this)
        handleDebugIntent(intent)
        refreshCount()

        // Low RAM log
        if (isLowRam) {
            android.util.Log.i("KenhLive", "Low RAM device detected: optimizing")
        }
    }

    private fun handleDebugIntent(i: Intent?) {
        i?.getStringExtra("open")?.let { target ->
            when (target) {
                "mv" -> startActivity(Intent(this, MultiViewActivity::class.java))
                "update" -> UpdateManager.debugForceDialog(this)
                "search" -> viewPager.post { viewPager.setCurrentItem(2, false) }
                "player" -> CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val g = SocoliveRepository.groupRooms(SocoliveRepository.fetchLiveRooms()).firstOrNull()
                        val r = g?.top
                        if (r != null) {
                            val u = SocoliveRepository.fetchStream(r.roomNum)
                            if (u != null) startActivity(
                                Intent(this@MainActivity, PlayerActivity::class.java)
                                    .putExtra("url", u).putExtra("name", "${r.matchTitle} · ${r.blvName}")
                            )
                        }
                    } catch (e: Exception) {}
                }
                else -> {}
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tabX = intent.getIntExtra("tab", -1)
        if (tabX in 0..2) viewPager.post { viewPager.setCurrentItem(tabX, false) }
        handleDebugIntent(intent)
    }

    private fun paintNav(pos: Int) {
        navLive?.isSelected = pos == 0
        navSchedule?.isSelected = pos == 1
        navSearch?.isSelected = pos == 2
        val active = 0xFFFFFFFF.toInt()
        val idle = 0xFF9CA3AF.toInt()
        findViewById<TextView>(R.id.tv_live)?.setTextColor(if (pos == 0) active else idle)
        findViewById<TextView>(R.id.tv_schedule)?.setTextColor(if (pos == 1) active else idle)
        findViewById<TextView>(R.id.tv_search)?.setTextColor(if (pos == 2) active else idle)
    }

    fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private val countHandler = Handler(Looper.getMainLooper())
    private val countTick = object : Runnable {
        override fun run() {
            refreshCount()
            countHandler.postDelayed(this, 3 * 60_000L)
        }
    }

    override fun onResume() {
        super.onResume()
        countHandler.removeCallbacks(countTick)
        countHandler.postDelayed(countTick, 3 * 60_000L)
    }

    override fun onPause() {
        super.onPause()
        countHandler.removeCallbacks(countTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        countHandler.removeCallbacksAndMessages(null)
    }

    private fun refreshCount() {
        CoroutineScope(Dispatchers.IO).launch {
            val n = try {
                SocoliveRepository.fetchLiveRooms().size
            } catch (e: Exception) {
                -1
            }
            withContext(Dispatchers.Main) {
                val tv = findViewById<TextView>(R.id.countText)
                if (n > 0 && tv != null) {
                    tv.visibility = View.VISIBLE
                    tv.text = "$n LIVE"
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            // Clear Coil cache
            try {
                coil.Coil.imageLoader(this).memoryCache?.clear()
            } catch (_: Exception) {}
        }
    }
}
