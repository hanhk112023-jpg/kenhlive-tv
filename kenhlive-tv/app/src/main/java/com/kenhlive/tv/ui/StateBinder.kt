package com.kenhlive.tv.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.kenhlive.tv.R
import com.kenhlive.tv.UiState

/**
 * Render UiState vào overlay loading/empty/error thống nhất toàn app.
 * Empty: minh hoạ sân trống · Error: icon mất sóng + nút THỬ LẠI (focusable cho TV).
 */
class StateBinder(root: View) {
    private val stateRoot: View = root.findViewById(R.id.stateRoot)
    private val loading: View = root.findViewById(R.id.stateLoading)
    private val loadingText: TextView = root.findViewById(R.id.stateLoadingText)
    private val empty: View = root.findViewById(R.id.stateEmpty)
    private val image: ImageView = root.findViewById(R.id.stateImage)
    private val title: TextView = root.findViewById(R.id.stateTitle)
    private val body: TextView = root.findViewById(R.id.stateBody)
    private val retry: TextView = root.findViewById(R.id.stateRetry)

    fun <T> render(state: UiState<T>, loadingTextRes: Int = R.string.state_loading, onRetry: () -> Unit = {}) {
        when (state) {
            is UiState.Loading -> {
                stateRoot.visibility = View.VISIBLE
                loading.visibility = View.VISIBLE
                empty.visibility = View.GONE
                loadingText.setText(loadingTextRes)
            }
            is UiState.Success<*> -> {
                stateRoot.visibility = View.GONE
                loading.visibility = View.GONE
                empty.visibility = View.GONE
            }
            is UiState.Empty -> {
                stateRoot.visibility = View.VISIBLE
                loading.visibility = View.GONE
                empty.visibility = View.VISIBLE
                image.setImageResource(R.drawable.empty_schedule)
                title.text = state.title
                body.text = state.body
                retry.visibility = View.GONE
            }
            is UiState.Error -> {
                stateRoot.visibility = View.VISIBLE
                loading.visibility = View.GONE
                empty.visibility = View.VISIBLE
                image.setImageResource(R.drawable.ic_signal_off)
                title.text = state.title
                body.text = state.body
                retry.visibility = if (state.retryable) View.VISIBLE else View.GONE
                retry.setOnClickListener { onRetry() }
            }
        }
    }
}
