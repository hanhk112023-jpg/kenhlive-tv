package com.kenhlive.tv

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
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
import com.kenhlive.tv.phim.EpisodePickerDialog
import com.kenhlive.tv.phim.NguoncFilm
import com.kenhlive.tv.phim.WebPlayerActivity
import com.kenhlive.tv.ui.RoomPickerDialog
import com.kenhlive.tv.ui.StateBinder
import com.kenhlive.tv.viewmodel.SearchViewModel
import kotlinx.coroutines.launch

/**
 * TÌM KIẾM ĐA NGUỒN:
 * 1. Trực tiếp Thể thao / BLV Socolive
 * 2. Kho phim Nguồn C (phim.nguonc.com) với hơn 33.400+ đầu phim & fallback 240 phim offline
 * TV: chip focusable để remote D-pad chọn nhanh không cần gõ bàn phím.
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
    private var lastFocusedPosition: Int = -1

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_search, container, false)
        input = v.findViewById(R.id.searchInput)
        resultList = v.findViewById(R.id.resultList)
        resultCount = v.findViewById(R.id.resultCount)
        chipContainer = v.findViewById(R.id.chipContainer)
        chipRow = v.findViewById(R.id.chipRow)
        state = StateBinder(v)

        searchAdapter = SearchResultAdapter(
            onMatchClick = { g -> openGroup(g) },
            onFilmClick = { film -> openFilm(film) }
        )
        searchAdapter.onItemFocused = { pos ->
            lastFocusedPosition = pos
        }
        searchAdapter.onUpFromFirst = {
            val chip0 = if (chipRow.visibility == View.VISIBLE && chipContainer.childCount > 0) {
                chipContainer.getChildAt(0)
            } else null
            if (chip0?.requestFocus() != true) {
                input.requestFocus()
            }
        }

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
        input.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                (activity as? MainActivity)?.hideKeyboard()
                if (chipRow.visibility == View.VISIBLE && chipContainer.childCount > 0) {
                    val chip0 = chipContainer.getChildAt(0)
                    if (chip0 != null && chip0.requestFocus()) return@setOnKeyListener true
                }
                if (focusFirstResult()) return@setOnKeyListener true
            }
            false
        }
        v.findViewById<ImageButton>(R.id.clearBtn).setOnClickListener {
            input.setText("")
            vm.setQuery("")
            input.requestFocus()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.source.collect { st ->
                state.render(st, R.string.state_loading) { vm.load(force = true) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.result.collect { r ->
                searchAdapter.submitList(r.items)
                val q = r.query
                resultCount.text = if (q.isEmpty())
                    getString(R.string.search_count_all, r.items.size)
                else getString(R.string.search_count_result, r.items.size, q)
                if (r.chips.isNotEmpty() && chipContainer.childCount == 0) buildChips(r.chips)
            }
        }
        vm.load()
        return v
    }

    override fun onResume() {
        super.onResume()
        if (DeviceMode.isTv) focusAppropriate()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && DeviceMode.isTv) {
            focusAppropriate()
        }
    }

    private fun focusAppropriate() {
        if (lastFocusedPosition >= 0 && searchAdapter.itemCount > 0) {
            resultList.post {
                val vh = resultList.findViewHolderForAdapterPosition(lastFocusedPosition)
                if (vh != null) {
                    vh.itemView.requestFocus()
                } else {
                    resultList.scrollToPosition(lastFocusedPosition)
                    resultList.postDelayed({
                        resultList.findViewHolderForAdapterPosition(lastFocusedPosition)?.itemView?.requestFocus()
                    }, 120)
                }
            }
        } else {
            input.post { input.requestFocus() }
        }
    }

    private fun focusFirstResult(): Boolean {
        if (searchAdapter.itemCount <= 0) return false
        val vh = resultList.findViewHolderForAdapterPosition(0)
        if (vh?.itemView?.requestFocus() == true) return true
        val lm = resultList.layoutManager as? LinearLayoutManager
        val v = lm?.findViewByPosition(0) ?: resultList.getChildAt(0)
        if (v?.requestFocus() == true) return true
        resultList.scrollToPosition(0)
        resultList.post {
            val t = resultList.findViewHolderForAdapterPosition(0)?.itemView
                ?: resultList.layoutManager?.findViewByPosition(0)
                ?: resultList.getChildAt(0)
            if (t?.requestFocus() != true) {
                resultList.postDelayed({
                    val t2 = resultList.findViewHolderForAdapterPosition(0)?.itemView
                        ?: resultList.layoutManager?.findViewByPosition(0)
                        ?: resultList.getChildAt(0)
                    t2?.requestFocus()
                }, 50)
            }
        }
        return true
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
            chip.setOnKeyListener { _, keyCode, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (focusFirstResult()) return@setOnKeyListener true
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        input.requestFocus()
                        return@setOnKeyListener true
                    }
                }
                false
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

    private fun openFilm(film: NguoncFilm) {
        dialog?.dismiss()
        dialog = EpisodePickerDialog.show(
            context = requireContext(),
            scope = viewLifecycleOwner.lifecycleScope,
            film = film,
            onSelectEpisode = { f, ep, startPos ->
                val intent = Intent(requireContext(), WebPlayerActivity::class.java)
                    .putExtra("embed_url", ep.embed)
                    .putExtra("m3u8_url", ep.m3u8)
                    .putExtra("film_slug", f.slug)
                    .putExtra("film_title", f.name)
                    .putExtra("episode_slug", ep.slug)
                    .putExtra("episode_title", if (ep.name.all { it.isDigit() }) "Tập ${ep.name}" else ep.name)
                    .putExtra("poster_url", f.posterUrl.ifBlank { f.thumbUrl })
                    .putExtra("start_position_ms", startPos)
                startActivity(intent)
                (activity as? MainActivity)?.hideKeyboard()
            }
        )
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
