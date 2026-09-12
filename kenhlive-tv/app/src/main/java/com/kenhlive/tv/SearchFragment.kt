package com.kenhlive.tv

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kenhlive.tv.ui.RoomPickerDialog
import com.kenhlive.tv.ui.StateBinder
import com.kenhlive.tv.viewmodel.SearchViewModel
import kotlinx.coroutines.launch

/**
 * TÌM KIẾM: lọc trận/BLV/giải đang live theo từ khoá không dấu + chip giải hot.
 * TV: chip focusable để remote chọn nhanh không cần bàn phím.
 */
class SearchFragment : Fragment() {

    private val vm: SearchViewModel by viewModels()
    private lateinit var input: EditText
    private lateinit var resultList: RecyclerView
    private lateinit var resultCount: TextView
    private lateinit var chipContainer: LinearLayout
    private lateinit var chipRow: HorizontalScrollView
    private lateinit var state: StateBinder
    private lateinit var searchAdapter: SearchResultAdapter
    private var dialog: AlertDialog? = null
    private var syncing = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_search, container, false)
        input = v.findViewById(R.id.searchInput)
        resultList = v.findViewById(R.id.resultList)
        resultCount = v.findViewById(R.id.resultCount)
        chipContainer = v.findViewById(R.id.chipContainer)
        chipRow = v.findViewById(R.id.chipRow)
        state = StateBinder(v)

        searchAdapter = SearchResultAdapter { g -> openGroup(g) }
        resultList.layoutManager = LinearLayoutManager(requireContext())
        resultList.itemAnimator = null
        resultList.clipChildren = false
        resultList.clipToPadding = false
        resultList.adapter = searchAdapter

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!syncing) vm.setQuery(s?.toString().orEmpty())
            }
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                vm.setQuery(input.text.toString()); true
            } else false
        }
        v.findViewById<ImageButton>(R.id.clearBtn).setOnClickListener {
            input.setText("")
            vm.setQuery("")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.source.collect { st ->
                state.render(st, R.string.state_loading) { vm.load(force = true) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.result.collect { r ->
                searchAdapter.submitList(r.groups)
                val q = r.query
                resultCount.text = if (q.isEmpty())
                    getString(R.string.search_count_all, r.groups.size)
                else getString(R.string.search_count_result, r.groups.size, q)
                if (r.chips.isNotEmpty() && chipContainer.childCount == 0) buildChips(r.chips)
            }
        }
        vm.load()
        return v
    }

    private fun buildChips(leagues: List<String>) {
        chipContainer.removeAllViews()
        if (leagues.isEmpty()) { chipRow.visibility = View.GONE; return }
        val inf = LayoutInflater.from(requireContext())
        leagues.forEach { lg ->
            val chip = inf.inflate(R.layout.item_search_chip, chipContainer, false) as TextView
            chip.text = lg
            chip.setOnClickListener {
                syncing = true
                input.setText(lg)
                syncing = false
                vm.setQuery(lg)
                (activity as? MainActivity)?.hideKeyboard()
            }
            chipContainer.addView(chip)
        }
    }

    private fun openGroup(g: LiveMatchGroup) {
        if (g.count == 1) { openRoom(g.top); return }
        dialog?.dismiss()
        dialog = RoomPickerDialog.show(requireContext(), g, onPickRoom = { r ->
            openRoom(r)
            (activity as? MainActivity)?.hideKeyboard()
        })
    }

    private fun openRoom(room: LiveRoom) {
        viewLifecycleOwner.lifecycleScope.launch {
            val url = SocoliveRepository.fetchStream(room.roomNum)
            if (url == null) {
                Toast.makeText(requireContext(), R.string.stream_not_ready, Toast.LENGTH_SHORT).show()
                return@launch
            }
            startActivity(
                Intent(requireContext(), PlayerActivity::class.java)
                    .putExtra("url", url)
                    .putExtra("name", "${room.matchTitle} · ${room.blvName}")
            )
        }
    }

    override fun onDestroyView() {
        dialog?.dismiss()
        dialog = null
        super.onDestroyView()
    }
}
