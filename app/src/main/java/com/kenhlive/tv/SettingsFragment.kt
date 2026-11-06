package com.kenhlive.tv

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Màn CÀI ĐẶT (phone + TV chung 1 layout, row focusable cho remote).
 * Giá trị lưu SharedPreferences qua EnhanceSettings / prefs "mv".
 */
class SettingsFragment : Fragment() {

    private lateinit var adapter: SettingsAdapter
    private var dialog: AlertDialog? = null

    /** Hàng cài đặt đang được focus gần nhất (vị trí adapter) — khôi phục sau rebuild/quay lại tab.
     * Trước đây mọi đường đều hard-code về hàng 1. Mặc định 1 = hàng đầu tiên focusable (Chất lượng hình ảnh). */
    private var lastFocusedRow: Int = 1

    // H4: dung lượng đo ở luồng nền; null = chưa đo xong (hàng hiển thị "Đang tính…")
    private var storageSummaryText: String? = null
    private var storageMeasureJob: Job? = null
    private var storageMeasuredAt: Long = 0L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_settings, container, false)
        adapter = SettingsAdapter()
        val rv = v.findViewById<RecyclerView>(R.id.settingsList)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        rv.clipToPadding = false
        if (DeviceMode.isTv) {
            // Theo dõi liên tục hàng đang focus (rebuild chỉ chụp được tại thời điểm gọi,
            // không đủ: người dùng di chuyển focus giữa các lần rebuild mà không kích hoạt rebuild nào)
            rv.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
                if (newFocus != null) {
                    // Crash run toàn app lần 5: ClassCastException
                    // LinearLayout$LayoutParams -> RecyclerView$LayoutParams tại
                    // dòng getChildAdapterPosition này, khi focus di chuyển qua
                    // một view không phải item trực tiếp của RecyclerView
                    // (vd view con sâu trong hàng, hoặc view của hierarchy khác
                    // trong lúc điều hướng focus). Vì vậy chỉ lấy vị trí qua
                    // item trực tiếp (đi ngược parent tới khi gặp rv), và bọc
                    // try-catch — lỗi ghi nhớ hàng focus không bao giờ được
                    // làm crash cả app nữa, giống bản vá ScheduleAdapter.
                    try {
                        var cur: View? = newFocus
                        while (cur != null && cur.parent !== rv) {
                            cur = cur.parent as? View
                        }
                        if (cur != null) {
                            val pos = rv.getChildAdapterPosition(cur)
                            if (pos != RecyclerView.NO_POSITION) lastFocusedRow = pos
                        }
                    } catch (_: Exception) {
                        // Bỏ qua, giữ nguyên lastFocusedRow cũ.
                    }
                }
            }
        }
        rebuild(focusRow = if (DeviceMode.isTv) lastFocusedRow else -1)
        return v
    }

    override fun onResume() {
        super.onResume()
        rebuild(focusRow = if (DeviceMode.isTv) lastFocusedRow else -1)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            dialog?.dismiss()
            dialog = null
            view?.clearFocus()
            (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        } else {
            (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            rebuild(focusRow = if (DeviceMode.isTv) lastFocusedRow else -1)
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
        // Ưu tiên hàng người dùng đang thao tác gần nhất, không còn hard-code hàng 1
        val vh = list.findViewHolderForAdapterPosition(lastFocusedRow)
        if (vh?.itemView?.requestFocus() == true) return true
        list.post {
            val vh2 = list.findViewHolderForAdapterPosition(lastFocusedRow)
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
        // M3: đã bỏ đọc prefs "mv"/layout ở đây — biến mvLayout cũ không bao giờ
        // được dùng (tuỳ chọn bố cục MultiView trong Cài đặt là code chết).
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

                SettingsAdapter.Item.Section("TRÌNH PHÁT"),
                SettingsAdapter.Item.Row(
                    R.drawable.ic_play, "Bước tua khi xem phim",
                    value = PlayerPrefs.seekStepSec(ctx).toString() + " giây"
                ) { pickSeekStep() },

                SettingsAdapter.Item.Section("TRUYỀN HÌNH & IPTV"),
                SettingsAdapter.Item.Row(
                    R.drawable.ic_nav_tv, "Danh sách kênh M3U tùy chỉnh",
                    value = com.kenhlive.tv.iptv.IptvRepository.getCustomM3uUrl(ctx).ifBlank { "Mặc định (tinyurl.com/vmt47)" }
                ) {
                    showCustomM3uDialog(ctx)
                },

                SettingsAdapter.Item.Section(getString(R.string.settings_section_storage)),
                SettingsAdapter.Item.Row(
                    R.drawable.ic_info, getString(R.string.settings_storage_usage),
                    value = storageSummaryText ?: "Đang tính…", chevron = false
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

        // H4: đo dung lượng ở luồng nền (hàm tự quyết định có cần đo lại không)
        requestStorageSummary()

        if (focusRow >= 0 && DeviceMode.isTv) {
            val list = view?.findViewById<RecyclerView>(R.id.settingsList)
            list?.post {
                // Focus đã sống sót qua lần submit này (kể cả khi người dùng vừa di chuyển
                // sang hàng khác trong lúc rebuild chạy nền) -> không kéo giật về hàng cũ.
                if (list.findFocus() != null) return@post
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

    /**
     * H4 — Đo dung lượng trên Dispatchers.IO (trước đây measure() duyệt đệ quy
     * cacheDir + DB ngay trên main thread mỗi lần mở/quay lại Cài đặt -> giật).
     * Xong thì cập nhật biến tóm tắt và rebuild (không đổi focus) để hàng hiển thị số thật.
     */
    private fun requestStorageSummary() {
        if (storageMeasureJob?.isActive == true) return
        // Số liệu còn tươi (< 30s) thì dùng lại, khỏi đo liên tục mỗi lần rebuild
        if (storageSummaryText != null &&
            System.currentTimeMillis() - storageMeasuredAt < 30_000L) return
        val appCtx = context?.applicationContext ?: return
        storageMeasureJob = lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { storageSummary(appCtx) }
            if (!isAdded) return@launch
            storageSummaryText = text
            storageMeasuredAt = System.currentTimeMillis()
            rebuild()
        }
    }

    private fun showStorageDialog() {
        val ctx = requireContext()
        val appCtx = ctx.applicationContext
        dialog?.dismiss()
        val dlg = AlertDialog.Builder(ctx, R.style.Theme_KenhLive_Dialog)
            .setTitle(getString(R.string.settings_section_storage))
            .setMessage("Đang tính…")
            .setPositiveButton(getString(R.string.settings_storage_clear_all)) { _, _ ->
                // H4: xoá cache ở luồng nền, xong mới báo + đo lại trên Main
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { StorageManager.clearAllCaches(appCtx) }
                    if (!isAdded) return@launch
                    Toast.makeText(ctx, getString(R.string.settings_clear_cache_done), Toast.LENGTH_SHORT).show()
                    storageSummaryText = null
                    rebuild()
                }
            }
            .setNegativeButton("Đóng", null)
            .setOnDismissListener { dialog = null }
            .create()
        dialog = dlg
        dlg.show()
        // H4: đo chi tiết ở luồng nền rồi điền vào dialog đang mở
        lifecycleScope.launch {
            val u = withContext(Dispatchers.IO) {
                try { StorageManager.measure(appCtx) } catch (_: Exception) { null }
            }
            if (!isAdded || !dlg.isShowing) return@launch
            val msg = if (u == null) "Không đo được dung lượng." else buildString {
                append("APK đã cài: ").append(StorageManager.format(u.apkBytes)).append("\n")
                append("Bộ nhớ đệm ảnh: ").append(StorageManager.format(u.imageCacheBytes)).append("\n")
                append("Dữ liệu API (HTTP): ").append(StorageManager.format(u.httpCacheBytes)).append("\n")
                append("Đệm khác (JSON, EPG…): ").append(StorageManager.format(u.otherCacheBytes)).append("\n")
                append("Lịch sử xem (cơ sở dữ liệu): ").append(StorageManager.format(u.historyDbBytes)).append("\n\n")
                append("Xóa bộ nhớ đệm không làm mất lịch sử xem và cài đặt.")
            }
            dlg.setMessage(msg)
        }
    }

    /** Bước tua dùng cho D-pad ◄/►, chạm đúp và thanh điều khiển trong cả 2 trình phát. */
    private fun pickSeekStep() {
        val opts = PlayerPrefs.SEEK_STEP_OPTIONS_SEC
        val names = opts.map { "$it giây" }.toTypedArray()
        val cur = opts.indexOf(PlayerPrefs.seekStepSec(requireContext())).coerceAtLeast(0)
        singleChoice(names, cur) { i ->
            PlayerPrefs.setSeekStepSec(requireContext(), opts[i])
            Toast.makeText(requireContext(), "Đã đặt bước tua: ${opts[i]} giây", Toast.LENGTH_SHORT).show()
            rebuild(focusRow = if (DeviceMode.isTv) lastFocusedRow else -1)
        }
    }

    private fun pickImageCap() {
        val opts = StorageManager.IMAGE_CAP_OPTIONS_MB
        val names = opts.map { it.toString() + " MB" }.toTypedArray()
        val cur = opts.indexOf(StorageManager.imageCapMb(requireContext())).coerceAtLeast(0)
        singleChoice(names, cur) { i ->
            StorageManager.setImageCapMb(requireContext(), opts[i])
            Toast.makeText(requireContext(), getString(R.string.settings_cap_restart), Toast.LENGTH_SHORT).show()
            rebuild(focusRow = if (DeviceMode.isTv) lastFocusedRow else -1)
        }
    }

    private fun pickHttpCap() {
        val opts = StorageManager.HTTP_CAP_OPTIONS_MB
        val names = opts.map { it.toString() + " MB" }.toTypedArray()
        val cur = opts.indexOf(StorageManager.httpCapMb(requireContext())).coerceAtLeast(0)
        singleChoice(names, cur) { i ->
            StorageManager.setHttpCapMb(requireContext(), opts[i])
            Toast.makeText(requireContext(), getString(R.string.settings_cap_restart), Toast.LENGTH_SHORT).show()
            rebuild(focusRow = if (DeviceMode.isTv) lastFocusedRow else -1)
        }
    }

    /**
     * M2 — Màn "Trạng thái nguồn dữ liệu" hiển thị dữ liệu CẦU DAO THẬT từ
     * [ApiHealth.snapshot] (trước đây snapshot() là code chết, màn này chỉ chạy
     * probe tuần tự nên nguồn đang mở mạch luôn bị báo ❌ kể cả khi đã hồi phục,
     * và không hề cho biết host nào đang bị ngắt mạch).
     * Hiển thị ngay lập tức; nút "Kiểm tra ngay" mới chạy probe chủ động.
     *
     * Lưu ý: thời gian nghỉ còn lại là ƯỚC TÍNH từ thời điểm lỗi gần nhất +
     * 120s (khớp ApiHealth.COOLDOWN_MS — hằng số đang private, xem báo cáo audit:
     * đề xuất ApiHealth bổ sung hàm circuitRemainingMs(host) để lấy số chính xác).
     */
    private fun checkApiHealth() {
        val ctx = requireContext()
        val entries = ApiHealth.snapshot().associateBy { it.host }
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        var openCount = 0
        val msg = buildString {
            ApiHealthChecker.SOURCES.forEach { src ->
                val host = ApiHealth.hostOf(src.url)
                val e = entries[host]
                val circuitOpen = e != null && ApiHealth.isCircuitOpen(host)
                if (circuitOpen) openCount++
                append(
                    when {
                        e == null -> "⚪ "
                        circuitOpen -> ""
                        e.ok -> "🟢 "
                        else -> "🟡 "
                    }
                ).append(src.name).append("\n")
                when {
                    e == null -> append("   Chưa có dữ liệu (app chưa gọi nguồn này)\n")
                    circuitOpen -> {
                        val remainSec = ((e.atMs + 120_000L - System.currentTimeMillis()) / 1000)
                            .coerceAtLeast(0)
                        append("   Cầu dao ĐANG MỞ — lỗi liên tiếp ${e.consecutiveFails} lần")
                        append(", tự thử lại sau ~${remainSec}s (ước tính)")
                        e.error?.takeIf { it.isNotBlank() }?.let { append("\n   Lỗi gần nhất: $it") }
                        append("\n")
                    }
                    e.ok -> append("   OK — ${e.latencyMs} ms · lúc ${timeFmt.format(java.util.Date(e.atMs))}\n")
                    else -> {
                        append("   Lỗi gần nhất (${e.consecutiveFails} lần liên tiếp) · lúc ${timeFmt.format(java.util.Date(e.atMs))}")
                        e.error?.takeIf { it.isNotBlank() }?.let { append("\n   $it") }
                        append("\n")
                    }
                }
            }
        }
        dialog?.dismiss()
        val dlg = AlertDialog.Builder(ctx, R.style.Theme_KenhLive_Dialog)
            .setTitle(
                getString(R.string.settings_api_health) +
                    if (openCount > 0) " — $openCount nguồn đang ngắt mạch" else ""
            )
            .setMessage(msg.trim())
            .setPositiveButton("Đóng", null)
            .setNeutralButton("Kiểm tra ngay") { _, _ -> runLiveProbe() }
            .setOnDismissListener { dialog = null }
            .create()
        dialog = dlg
        dlg.show()
    }

    /** Probe chủ động từng nguồn (hành vi cũ) — chỉ chạy khi người dùng bấm "Kiểm tra ngay". */
    private fun runLiveProbe() {
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
            rebuild(focusRow = lastFocusedRow)
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
            rebuild(focusRow = lastFocusedRow)
        }
    }

    // M3: pickMvLayout() đã bị xoá — tuỳ chọn bố cục MultiView trong Cài đặt là code
    // chết (không row nào gọi tới). MultiViewActivity vẫn tự đọc prefs "mv"/layout
    // như cũ nếu sau này nối lại UI; khi đó chỉ cần thêm row gọi lại hàm chọn.

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

    private fun showCustomM3uDialog(ctx: Context) {
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_custom_m3u, null)
        val edt = v.findViewById<EditText>(R.id.edtM3uUrl)
        val btnSample = v.findViewById<TextView>(R.id.btnM3uSample)
        val btnClear = v.findViewById<TextView>(R.id.btnM3uClear)
        val btnClose = v.findViewById<android.widget.Button>(R.id.btnM3uClose)
        val btnSave = v.findViewById<android.widget.Button>(R.id.btnM3uSave)

        val currentUrl = com.kenhlive.tv.iptv.IptvRepository.getCustomM3uUrl(ctx)
        edt.setText(currentUrl)
        edt.setSelection(edt.text.length)

        val d = androidx.appcompat.app.AlertDialog.Builder(ctx, R.style.Theme_KenhLive_Dialog)
            .setView(v)
            .create()

        btnSample?.setOnClickListener {
            edt.setText(com.kenhlive.tv.iptv.IptvRepository.DEFAULT_CUSTOM_M3U)
            edt.setSelection(edt.text.length)
        }

        btnClear?.setOnClickListener {
            edt.setText("")
        }

        btnClose?.setOnClickListener {
            d.dismiss()
        }

        btnSave?.setOnClickListener {
            val newUrl = edt.text.toString().trim()
            com.kenhlive.tv.iptv.IptvRepository.setCustomM3uUrl(ctx, newUrl)
            Toast.makeText(ctx, "Đã lưu đường dẫn M3U mới", Toast.LENGTH_SHORT).show()
            d.dismiss()
            rebuild()
        }

        d.show()
    }

    private fun clearCache() {
        val ctx = requireContext()
        val appCtx = ctx.applicationContext
        // H4: clear disk cache là thao tác IO — chạy nền, xong mới toast + đo lại.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                coil.Coil.imageLoader(appCtx).memoryCache?.clear()
                coil.Coil.imageLoader(appCtx).diskCache?.clear()
                Http.get().cache?.evictAll()
                com.kenhlive.tv.phim.NguoncRepository.clearCache()
            }
            if (!isAdded) return@launch
            Toast.makeText(ctx, getString(R.string.settings_clear_cache_done), Toast.LENGTH_SHORT).show()
            storageSummaryText = null
            rebuild()
        }
    }

    override fun onDestroyView() {
        dialog?.dismiss()
        dialog = null
        super.onDestroyView()
    }
}
