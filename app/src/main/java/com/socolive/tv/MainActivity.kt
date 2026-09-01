package com.socolive.tv

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var channels = mutableListOf<Channel>()
    private var currentGroup = "Tất cả"
    private lateinit var adapter: ChannelAdapter
    private lateinit var chipRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private val chipViews = mutableMapOf<String, TextView>()
    private val isTvMode by lazy { DeviceMode.usePhoneLayout(this).not() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chipRow = findViewById(R.id.chipRow)
        statusText = findViewById(R.id.statusText)
        countText = findViewById(R.id.countText)
        adapter = ChannelAdapter(gridMode = !isTvMode) { channel -> openChannel(channel) }
        findViewById<RecyclerView>(R.id.recyclerView).apply {
            layoutManager = if (isTvMode)
                LinearLayoutManager(this@MainActivity)
            else
                GridLayoutManager(this@MainActivity, 2)
            adapter = this@MainActivity.adapter
        }

        load()
        checkUpdate()
    }

    private fun load() {
        val url = getString(R.string.default_url)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { M3uParser.fetch(url) }
                channels = result.toMutableList()
                buildChips()
                applyFilter(currentGroup)
                statusText.visibility = View.GONE
            } catch (e: Exception) {
                statusText.text = "Lỗi tải playlist: ${e.message}"
                Toast.makeText(this@MainActivity, "Lỗi: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- update checker ----------
    private fun checkUpdate() {
        scope.launch {
            val rel = withContext(Dispatchers.IO) { UpdateChecker.latest() } ?: return@launch
            val current = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
            } catch (e: Exception) { "1.0" }
            if (UpdateChecker.isNewer(rel.version, current)) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Có phiên bản mới v${rel.version}")
                    .setMessage("Bạn đang dùng v$current. Tải bản mới để có trải nghiệm tốt nhất?")
                    .setPositiveButton("Tải về") { _, _ ->
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.apkUrl)))
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Không mở được trình duyệt — mở GitHub Releases", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Để sau", null)
                    .show()
            }
        }
    }

    // ---------- chips ----------
    private fun buildChips() {
        chipRow.removeAllViews()
        chipViews.clear()
        val groups = listOf("Tất cả") + channels.map { it.group }.distinct().sorted()
        groups.forEach { g ->
            val tv = TextView(this).apply {
                text = if (g == "Tất cả") "Tất cả (${channels.size})"
                       else "$g (${channels.count { it.group == g }})"
                textSize = 14f
                setPadding(34, 16, 34, 16)
                setTextColor(Color.WHITE)
                background = getDrawable(R.drawable.chip_bg)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 10.dp() }
                isFocusable = true
                setOnClickListener { applyFilter(g) }
            }
            chipRow.addView(tv)
            chipViews[g] = tv
        }
        selectChip(currentGroup)
    }

    private fun selectChip(g: String) {
        chipViews.forEach { (name, tv) ->
            tv.isSelected = name == g
            tv.setTextColor(if (name == g) Color.parseColor("#4CC9F0") else Color.WHITE)
        }
    }

    private fun applyFilter(group: String) {
        currentGroup = group
        selectChip(group)
        val list = if (group == "Tất cả") channels
                   else channels.filter { it.group == group }
        adapter.submitList(list)
        countText.text = "${list.size} kênh"
    }

    private fun openChannel(channel: Channel) {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("url", channel.url)
            .putExtra("name", channel.name))
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
