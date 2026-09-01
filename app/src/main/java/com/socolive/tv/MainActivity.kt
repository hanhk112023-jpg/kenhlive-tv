package com.socolive.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var channels = mutableListOf<Channel>()
    private var filtered = mutableListOf<Channel>()
    private lateinit var adapter: ChannelAdapter
    private lateinit var spinner: Spinner
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinner = findViewById(R.id.groupSpinner)
        statusText = findViewById(R.id.statusText)
        adapter = ChannelAdapter { channel -> openChannel(channel) }
        findViewById<RecyclerView>(R.id.recyclerView).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        load()
    }

    private fun load() {
        val url = getString(R.string.default_url)
        scope.launch {
            try {
                statusText.text = "Đang tải playlist..."
                val result = withContext(Dispatchers.IO) { M3uParser.fetch(url) }
                channels = result.toMutableList()
                setupGroups()
                Toast.makeText(this@MainActivity, "Đã tải ${channels.size} kênh", Toast.LENGTH_SHORT).show()
                statusText.visibility = View.GONE
            } catch (e: Exception) {
                statusText.text = "Lỗi tải playlist: ${e.message}"
                Toast.makeText(this@MainActivity, "Lỗi: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupGroups() {
        val groups = listOf("Tất cả") + channels.map { it.group }.distinct().sorted()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, groups)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                applyFilter(groups[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun applyFilter(group: String) {
        filtered = if (group == "Tất cả")
            channels.toMutableList()
        else
            channels.filter { it.group == group }.toMutableList()
        adapter.setData(filtered)
    }

    private fun openChannel(channel: Channel) {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("url", channel.url)
            .putExtra("name", channel.name))
    }
}
