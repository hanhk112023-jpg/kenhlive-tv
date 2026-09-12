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
        v.findViewById<RecyclerView>(R.id.settingsList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@SettingsFragment.adapter
            clipToPadding = false
        }
        rebuild()
        return v
    }

    override fun onResume() {
        super.onResume()
        rebuild()
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

    private fun rebuild() {
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

                SettingsAdapter.Item.Section(getString(R.string.settings_section_view)),
                SettingsAdapter.Item.Row(
                    R.drawable.ic_multiview, getString(R.string.settings_mv_layout),
                    value = if (mvLayout == 4) getString(R.string.mv_layout_4) else getString(R.string.mv_layout_2)
                ) { pickMvLayout(mvLayout) },

                SettingsAdapter.Item.Section(getString(R.string.settings_section_app)),
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
                }
            )
        )
    }

    private fun pickVideoQuality() {
        val names = arrayOf(getString(R.string.vq_auto), getString(R.string.vq_high), getString(R.string.vq_stable))
        singleChoice(names, EnhanceSettings.videoQuality(requireContext())) { i ->
            EnhanceSettings.setVideoQuality(requireContext(), i)
            Toast.makeText(requireContext(), getString(R.string.player_video_changed, names[i]), Toast.LENGTH_SHORT).show()
            rebuild()
        }
    }

    private fun pickAudioMode() {
        val names = arrayOf(
            getString(R.string.aq_standard), getString(R.string.aq_bass), getString(R.string.aq_dialog),
            getString(R.string.aq_night), getString(R.string.aq_auto)
        )
        singleChoice(names, EnhanceSettings.audioMode(requireContext())) { i ->
            EnhanceSettings.setAudioMode(requireContext(), i)
            Toast.makeText(requireContext(), getString(R.string.player_audio_changed, names[i]), Toast.LENGTH_SHORT).show()
            rebuild()
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
            rebuild()
        }
    }

    private fun singleChoice(names: Array<String>, checked: Int, onPick: (Int) -> Unit) {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(requireContext())
            .setSingleChoiceItems(names, checked) { d, i -> d.dismiss(); onPick(i) }
            .setOnDismissListener { dialog = null }
            .show()
    }

    private fun versionName(): String = try {
        requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    private fun clearCache() {
        val ctx = requireContext()
        coil.imageLoader(ctx).memoryCache?.clear()
        coil.imageLoader(ctx).diskCache?.clear()
        Http.get().cache?.evictAll()
        Toast.makeText(ctx, getString(R.string.settings_clear_cache_done), Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        dialog?.dismiss()
        dialog = null
        super.onDestroyView()
    }
}
