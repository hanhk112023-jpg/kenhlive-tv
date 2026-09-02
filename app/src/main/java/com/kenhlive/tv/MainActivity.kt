package com.kenhlive.tv

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shell SportzX-style: topbar gradient + bottom nav glass pill nổi (glow border), ViewPager2 2 tab. */
class MainActivity : AppCompatActivity() {
    private lateinit var viewPager: ViewPager2
    private var navLive: LinearLayout? = null
    private var navSchedule: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.setSystemBarsAppearance(0, 0)

        viewPager = findViewById(R.id.viewPager)
        navLive = findViewById(R.id.nav_live)
        navSchedule = findViewById(R.id.nav_schedule)
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> LiveFragment()
                else -> ScheduleFragment()
            }
        }
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(pos: Int) = paintNav(pos)
        })

        val clickLive = View.OnClickListener { viewPager.setCurrentItem(0, true) }
        val clickSched = View.OnClickListener { viewPager.setCurrentItem(1, true) }
        navLive?.setOnClickListener(clickLive)
        navSchedule?.setOnClickListener(clickSched)
        findViewById<View>(R.id.tv_live)?.setOnClickListener(clickLive)
        findViewById<View>(R.id.tv_schedule)?.setOnClickListener(clickSched)

        // deep-link: --ei tab 1 mở thẳng tab Lịch trình (dùng cho CI screenshot)
        if (intent?.getIntExtra("tab", 0) == 1) {
            viewPager.post { viewPager.setCurrentItem(1, false) }
        }

        UpdateManager.checkAndUpdate(this)
        refreshCount()
    }

    private fun paintNav(pos: Int) {
        val live = pos == 0
        navLive?.isSelected = live
        navSchedule?.isSelected = !live
        val active = 0xFFFFFFFF.toInt()
        val idle = 0xFFCFCFCF.toInt()
        findViewById<TextView>(R.id.tv_live)?.setTextColor(if (live) active else idle)
        findViewById<TextView>(R.id.tv_schedule)?.setTextColor(if (!live) active else idle)
    }

    private fun refreshCount() {
        CoroutineScope(Dispatchers.IO).launch {
            val n = try { SocoliveRepository.fetchLiveRooms().size } catch (e: Exception) { -1 }
            withContext(Dispatchers.Main) {
                val tv = findViewById<TextView>(R.id.countText)
                if (n > 0 && tv != null) {
                    tv.visibility = View.VISIBLE
                    tv.text = "● $n phòng đang live"
                }
            }
        }
    }
}
