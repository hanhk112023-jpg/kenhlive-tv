package com.kenhlive.tv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var viewPager: ViewPager2
    private lateinit var countText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewPager = findViewById(R.id.viewPager)
        countText = findViewById(R.id.countText)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> ChannelsFragment()
                else -> MatchesFragment()
            }
        }
        TabLayoutMediator(tabLayout, viewPager) { tab, pos ->
            tab.text = if (pos == 0) "📺 Kênh" else "📅 Lịch thi đấu"
        }.attach()

        checkUpdate()
    }

    private fun checkUpdate() {
        CoroutineScope(Dispatchers.Main).launch {
            val rel = withContext(Dispatchers.IO) { UpdateChecker.latest() } ?: return@launch
            val current = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
            } catch (e: Exception) { "1.0" }
            if (UpdateChecker.isNewer(rel.version, current)) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Có phiên bản mới v${rel.version}")
                    .setMessage("Bạn đang dùng v$current. Tải bản mới để có trải nghiệm tốt nhất?")
                    .setPositiveButton("Tải về") { _, _ ->
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.apkUrl))) }
                        catch (_: Exception) { }
                    }
                    .setNegativeButton("Để sau", null)
                    .show()
            }
        }
    }
}
