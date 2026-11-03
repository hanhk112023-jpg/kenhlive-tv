package com.kenhlive.tv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Màn CÀI ĐẶT (phone + TV chung 1 layout, row focusable cho remote).
 * Giá trị lưu SharedPreferences qua EnhanceSettings / prefs "mv".
 */
class SettingsFragment : Fragment() {

    private lateinit var adapter: SettingsAdapter
    private var dialog: AlertDialog? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_settings, container, false)
        adapter = SettingsAdapter()
        val rv = v.findViewById<RecyclerView>(R.id.settingsList)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        rv.clipToPadding = false
        rebuild(focusRow = if (DeviceMode.isTv) 1 else -1)
        return v
    }

    override fun onResume() {
        super.onResume()
        val main = activity as? MainActivity
        val isRail = main?.isRailActive() == true
        rebuild(focusRow = if (DeviceMode.isTv && !isRail) 1 else -1)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            dialog?.dismiss()
            dialog = null
            view?.clearFocus()
            (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        } else {
            val main = activity as? MainActivity
            val isRail = main?.isRailActive() == true
            if (isRail) {
                (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                rebuild(focusRow = -1)
            } else {
                (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                rebuild(focusRow = if (DeviceMode.isTv) 1 else -1)
            }
        }
    }

    fun ensureSettingsFocus(): Boolean {
        if (!isAdded || view == null) return false
        (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val cur = view?.findFocus()
        if (cur != null && cur !== view) return true
        val list = view?.findViewById<RecyclerView>(R.id.settingsList) ?: return false
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (child != null && child.isFocusable && child.requestFocus()) return true
        }
        val vh = list.findViewHolderForAdapterPosition(1)
        if (vh?.itemView?.requestFocus() == true) return true
        list.post {
            val vh2 = list.findViewHolderForAdapterPosition(1)
            if (vh2?.itemView?.requestFocus() != true) {
                for (i in 0 until adapter.itemCount) {
                    val r = list.findViewHolderForAdapterPosition(i)?.itemView
                    if (r != null && r.isFocusable && r.requestFocus()) break
                }
            }
        }
        return true
    }

    private fun vqName(i: Int) = when (i) {
        EnhanceSettings.VQ_HIGH -> getString(R.string.vq_high)
        EnhanceSettings.VQ_STABLE -> getString(R.string.vq_stable)
        else -> getString(R.string.vq_auto)
    }

    private fun aqName(i: Int) = when (i) {
        EnhanceSettings.AQ_BASS -> getString(R.string.aq_bass)
        EnhanceSettings.AQ_DIALOG -> getString(R.string.aq_dialog)
        EnhanceSettings.AQ_NIGHT -> getString(R.string.aq_night)
        EnhanceSettings.AQ_AUTO -> getString(R.string.aq_auto)
        else -> getString(R.string.aq_standard)
    }

    private fun rebuild(focusRow: Int = -1) {
        if (!isAdded) return
        val ctx = requireContext()
        val mvLayout = ctx.getSharedPreferences("mv", android.content.Context.MODE_PRIVATE).getInt("layout", 2)
        adapter.submit(
            listOf(
                SettingsAdapter.Item.Section(getString(R.string.settings_section_playback)),
                SettingsAdapter.Item.Row(
                    R.drawable.ic_quality, getString(R.string.settings_video_quality),
                    value = vqName(EnhanceSettings.videoQuality(ctx))
                ) { pickVideoQuality() },
                SettingsAdapter.Item.Row(
                    R.drawable.ic_audio, getString(R.string.settings_audio_mode),
                    value = aqName(EnhanceSettings.audioMode(ctx))
                ) { pickAudioMode() },

                SettingsAdapter.Item.Section(getString(R.string.settings_section_app)),
                SettingsAdapter.Item.Row(
                    R.drawable.ic_telegram, getString(R.string.settings_feedback),
                    value = getString(R.string.settings_feedback_sub)
                ) {
                    com.kenhlive.tv.ui.FeedbackDialog.show(requireContext())
                },
                SettingsAdapter.Item.Row(
                    R.drawable.ic_trash, getString(R.string.settings_clear_cache), chevron = false
                ) { clearCache() },
                SettingsAdapter.Item.Row(
                    R.drawable.ic_refresh, getString(R.string.settings_check_update), chevron = false
                ) { UpdateManager.checkAndUpdate(requireActivity()) },
                SettingsAdapter.Item.Row(
                    R.drawable.ic_info, getString(R.string.settings_version),
                    value = versionName(), chevron = false
                ) {
                    Toast.makeText(ctx, getString(R.string.settings_about_body), Toast.LENGTH_LONG).show()
                },

                SettingsAdapter.Item.Section(getString(R.string.settings_section_storage)),
                SettingsAdapter.Item.Row(
                    R.drawable.ic_info, getString(R.string.settings_storage_usage),
                    value = storageSummary(ctx), chevron = false
                ) { showStorageDialog() },
                SettingsAdapter.Item.Row(
                    R.drawable.ic_quality, getString(R.string.settings_image_cap),
                    value = StorageManager.imageCapMb(ctx).toString() + " MB"
                ) { pickImageCap() },
                SettingsAdapter.Item.Row(
                    R.drawable.ic_quality, getString(R.string.settings_http_cap),
                    value = StorageManager.httpCapMb(ctx).toString() + " MB"
                ) { pickHttpCap() },
                SettingsAdapter.Item.Row(
                    R.drawable.ic_refresh, getString(R.string.settings_api_health), chevron = false
                ) { checkApiHealth() }
            )
        )

        if (focusRow >= 0 && DeviceMode.isTv) {
            val list = view?.findViewById<RecyclerView>(R.id.settingsList)
            list?.post {
                val vh = list.findViewHolderForAdapterPosition(focusRow)
                if (vh?.itemView?.requestFocus() == true) return@post
                list.scrollToPosition(focusRow)
                list.postDelayed({
                    val v = list.findViewHolderForAdapterPosition(focusRow)?.itemView
                        ?: list.layoutManager?.findViewByPosition(focusRow)
                    if (v?.requestFocus() != true) {
                        for (i in 0 until adapter.itemCount) {
                            val r = list.findViewHolderForAdapterPosition(i)?.itemView
                            if (r != null && r.isFocusable && r.requestFocus()) break
                        }
                    }
                }, 80)
            }
        }
    }

    private fun storageSummary(ctx: android.content.Context): String = try {
        val u = StorageManager.measure(ctx)
        "Đệm " + StorageManager.format(u.totalCacheBytes) + " · APK " + StorageManager.format(u.apkBytes)
    } catch (_: Exception) { "" }

    private fun showStorageDialog() {
        val ctx = requireContext()
        val u = try { StorageManager.measure(ctx) } catch (_: Exception) { null }
        val msg = if (u == null) "Không đo được dung lượng." else buildString {
            append("APK đã cài: ").append(StorageManager.format(u.apkBytes)).append("\n")
            append("Bộ nhớ đệm ảnh: ").append(StorageManager.format(u.imageCacheBytes)).append("\n")
            append("Dữ liệu API (HTTP): ").append(StorageManager.format(u.httpCacheBytes)).append("\n")
            append("Đệm khác (JSON, EPG…): ").append(StorageManager.format(u.otherCacheBytes)).append("\n")
            append("Lịch sử xem (cơ sở dữ liệu): ").append(StorageManager.format(u.historyDbBytes)).append("\n\n")
            append("Xóa bộ nhớ đệm không làm mất lịch sử xem và cài đặt.")
        }
        dialog?.dismiss()
        val dlg = AlertDialog.Builder(ctx, R.style.Theme_KenhLive_Dialog)
            .setTitle(getString(R.string.settings_section_storage))
            .setMessage(msg)
            .setPositiveButton(getString(R.string.settings_storage_clear_all)) { _, _ ->
                StorageManager.clearAllCaches(ctx)
                Toast.makeText(ctx, getString(R.string.settings_clear_cache_done), Toast.LENGTH_SHORT).show()
                rebuild()
            }
            .setNegativeButton("Đóng", null)
            .setOnDismissListener { dialog = null }
            .create()
        dialog = dlg
        dlg.show()
    }

    private fun pickImageCap() {
        val opts = StorageManager.IMAGE_CAP_OPTIONS_MB
        val names = opts.map { it.toString() + " MB" }.toTypedArray()
        val cur = opts.indexOf(StorageManager.imageCapMb(requireContext())).coerceAtLeast(0)
        singleChoice(names, cur) { i ->
            StorageManager.setImageCapMb(requireContext(), opts[i])
            Toast.makeText(requireContext(), getString(R.string.settings_cap_restart), Toast.LENGTH_SHORT).show()
            rebuild()
        }
    }

    private fun pickHttpCap() {
        val opts = StorageManager.HTTP_CAP_OPTIONS_MB
        val names = opts.map { it.toString() + " MB" }.toTypedArray()
        val cur = opts.indexOf(StorageManager.httpCapMb(requireContext())).coerceAtLeast(0)
        singleChoice(names, cur) { i ->
            StorageManager.setHttpCapMb(requireContext(), opts[i])
            Toast.makeText(requireContext(), getString(R.string.settings_cap_restart), Toast.LENGTH_SHORT).show()
            rebuild()
        }
    }

    private fun checkApiHealth() {
        val ctx = requireContext()
        Toast.makeText(ctx, getString(R.string.settings_api_checking), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val results = ApiHealthChecker.checkAll()
            if (!isAdded) return@launch
            val okCount = results.count { it.ok }
            val msg = buildString {
                results.forEach { r ->
                    append(if (r.ok) "✅ " else "❌ ").append(r.name)
                    append(" — ").append(if (r.ok) r.latencyMs.toString() + " ms" else r.detail).append("\n")
                }
            }
            dialog?.dismiss()
            val dlg = AlertDialog.Builder(ctx, R.style.Theme_KenhLive_Dialog)
                .setTitle(getString(R.string.settings_api_health) + " (" + okCount + "/" + results.size + ")")
                .setMessage(msg.trim())
                .setPositiveButton("Đóng", null)
                .setOnDismissListener { dialog = null }
                .create()
            dialog = dlg
            dlg.show()
        }
    }

    private fun pickVideoQuality() {
        val names = arrayOf(getString(R.string.vq_auto), getString(R.string.vq_high), getString(R.string.vq_stable))
        val cur = EnhanceSettings.videoQuality(requireContext())
        singleChoice(names, cur) { i ->
            EnhanceSettings.setVideoQuality(requireContext(), i)
            Toast.makeText(requireContext(), getString(R.string.player_video_changed, names[i]), Toast.LENGTH_SHORT).show()
            rebuild(focusRow = 1)
        }
    }

    private fun pickAudioMode() {
        val names = arrayOf(
            getString(R.string.aq_standard), getString(R.string.aq_bass), getString(R.string.aq_dialog),
            getString(R.string.aq_night), getString(R.string.aq_auto)
        )
        val cur = EnhanceSettings.audioMode(requireContext())
        singleChoice(names, cur) { i ->
            EnhanceSettings.setAudioMode(requireContext(), i)
            Toast.makeText(requireContext(), getString(R.string.player_audio_changed, names[i]), Toast.LENGTH_SHORT).show()
            rebuild(focusRow = 2)
        }
    }

    private fun pickMvLayout(current: Int) {
        val names = arrayOf(getString(R.string.mv_layout_2), getString(R.string.mv_layout_4))
        singleChoice(names, if (current == 4) 1 else 0) { i ->
            if (i == 1 && KenhLiveApp.lowRam) {
                Toast.makeText(requireContext(), getString(R.string.mv_lowram_lock), Toast.LENGTH_SHORT).show()
            } else {
                requireContext().getSharedPreferences("mv", android.content.Context.MODE_PRIVATE)
                    .edit().putInt("layout", if (i == 1) 4 else 2).apply()
            }
            rebuild(focusRow = 4)
        }
    }

    private fun singleChoice(names: Array<String>, checked: Int, onPick: (Int) -> Unit) {
        dialog?.dismiss()
        val dlg = AlertDialog.Builder(requireContext(), R.style.Theme_KenhLive_Dialog)
            .setSingleChoiceItems(names, checked) { d, i ->
                d.dismiss()
                onPick(i)
            }
            .setOnDismissListener { dialog = null }
            .create()
        dialog = dlg
        dlg.show()
        dlg.listView?.post {
            dlg.listView?.setSelection(checked)
            dlg.listView?.requestFocus()
        }
    }

    private fun versionName(): String = try {
        requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    private fun clearCache() {
        val ctx = requireContext()
        coil.Coil.imageLoader(ctx).memoryCache?.clear()
        coil.Coil.imageLoader(ctx).diskCache?.clear()
        Http.get().cache?.evictAll()
        com.kenhlive.tv.phim.NguoncRepository.clearCache()
        Toast.makeText(ctx, getString(R.string.settings_clear_cache_done), Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        dialog?.dismiss()
        dialog = null
        super.onDestroyView()
    }
}
