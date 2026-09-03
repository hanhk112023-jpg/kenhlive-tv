package com.kenhlive.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.launch
import java.text.Normalizer

/** Tab TÌM KIẾM: lọc trận/BLV/giải đang live theo từ khóa không dấu, gợi ý nhanh giải hot. */
class SearchFragment : Fragment() {

    private lateinit var input: EditText
    private lateinit var resultList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var resultCount: TextView
    private lateinit var chipContainer: LinearLayout
    private lateinit var chipRow: View
    private lateinit var searchAdapter: SearchResultAdapter

    private var rooms = listOf<LiveRoom>()
    private var groups = listOf<LiveMatchGroup>()
    private var loaded = false
    private val debounce = Handler(Looper.getMainLooper())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_search, container, false)
        input = v.findViewById(R.id.searchInput)
        resultList = v.findViewById(R.id.resultList)
        emptyText = v.findViewById(R.id.emptyText)
        resultCount = v.findViewById(R.id.resultCount)
        chipContainer = v.findViewById(R.id.chipContainer)
        chipRow = v.findViewById(R.id.chipRow)

        searchAdapter = SearchResultAdapter { g -> openGroup(g) }
        resultList.layoutManager = LinearLayoutManager(requireContext())
        resultList.adapter = searchAdapter

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                debounce.removeCallbacksAndMessages(null)
                debounce.postDelayed({ applyQuery(s?.toString().orEmpty()) }, 250)
            }
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                applyQuery(input.text.toString()); true
            } else false
        }
        v.findViewById<TextView>(R.id.clearBtn).setOnClickListener {
            input.setText(""); applyQuery("")
        }
        loadRooms()
        return v
    }

    private fun loadRooms() {
        if (loaded) return
        lifecycleScope.launch {
            try {
                rooms = SocoliveRepository.fetchLiveRooms()
                groups = SocoliveRepository.groupRooms(rooms)
                loaded = true
                buildChips()
                applyQuery(input.text.toString())
            } catch (e: Exception) {
                emptyText.text = "Không tải được danh sách — thử lại"
            }
        }
    }

    /** Chip giải hot: bấm là điền từ khóa luôn (TV keyboard bất tiện). */
    private fun buildChips() {
        chipContainer.removeAllViews()
        val leagues = groups.groupBy { it.league }.entries
            .sortedByDescending { e -> e.value.sumOf { g -> g.totalViewers } }
            .take(8).map { it.key }.filter { it.isNotBlank() }
        if (leagues.isEmpty()) { chipRow.visibility = View.GONE; return }
        val inf = LayoutInflater.from(requireContext())
        leagues.forEach { lg ->
            val chip = inf.inflate(R.layout.item_search_chip, chipContainer, false) as TextView
            chip.text = lg
            chip.setOnClickListener { input.setText(lg); applyQuery(lg) }
            chipContainer.addView(chip)
        }
    }

    private fun applyQuery(qRaw: String) {
        if (!loaded) return
        val q = norm(qRaw.trim())
        val res: List<LiveMatchGroup> = if (q.isEmpty()) {
            emptyText.text = "Gõ để tìm trận đang live"
            emptyText.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
            groups
        } else {
            val r = groups.filter { g ->
                norm(g.matchTitle).contains(q) || norm(g.league).contains(q) ||
                    g.rooms.any { norm(it.blvName).contains(q) }
            }
            emptyText.text = "Không tìm thấy “${qRaw.trim()}”"
            emptyText.visibility = if (r.isEmpty()) View.VISIBLE else View.GONE
            r
        }
        resultCount.text = if (q.isEmpty()) "${groups.size} trận đang live"
                           else "${res.size} kết quả cho “${qRaw.trim()}”"
        searchAdapter.submit(res)
    }

    /** Bỏ dấu tiếng Việt + lowercase để "real" khớp "Real", "chuc" khớp "Chúc". */
    private fun norm(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFD).replace("\\p{Mn}+".toRegex(), "")
        return n.lowercase().replace("đ", "d")
    }

    private fun openGroup(g: LiveMatchGroup) {
        if (g.count == 1) { openRoom(g.top); return }
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_room_picker, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = g.matchTitle
        view.findViewById<TextView>(R.id.dialogLeague).text = "${g.league} · ${g.count} phòng live"
        val list = view.findViewById<LinearLayout>(R.id.roomList)
        val inf = LayoutInflater.from(requireContext())
        val dlg = androidx.appcompat.app.AlertDialog.Builder(requireContext()).setView(view).create()
        g.rooms.forEach { r ->
            val opt = inf.inflate(R.layout.item_room_option, list, false)
            opt.findViewById<TextView>(R.id.roomName).text = r.blvName
            opt.findViewById<TextView>(R.id.roomMeta).text = "👁 ${SocoliveRepository.fmtViewers(r.viewers)}"
            opt.findViewById<ImageView>(R.id.roomAvatar).load(r.avatar) {
                crossfade(80); transformations(CircleCropTransformation())
                placeholder(R.drawable.logo_placeholder); error(R.drawable.logo_placeholder)
            }
            opt.setOnClickListener { openRoom(r); dlg.dismiss(); (activity as? MainActivity)?.hideKeyboard() }
            list.addView(opt)
        }
        dlg.show()
    }

    private fun openRoom(room: LiveRoom) {
        viewLifecycleOwner.lifecycleScope.launch {
            val url = SocoliveRepository.fetchStream(room.roomNum)
            if (url == null) {
                Toast.makeText(context, "Stream chưa sẵn sàng — thử lại", Toast.LENGTH_SHORT).show()
                return@launch
            }
            startActivity(Intent(requireContext(), PlayerActivity::class.java)
                .putExtra("url", url)
                .putExtra("name", "${room.matchTitle} · ${room.blvName}"))
        }
    }
}
