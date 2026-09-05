package com.kenhlive.tv

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/** Cài đặt nâng cao hình/âm — lưu SharedPreferences, áp dụng mọi màn hình. */
object EnhanceSettings {
    private const val PREF = "enhance"
    const val VQ_AUTO = 0; const val VQ_HIGH = 1; const val VQ_STABLE = 2
    const val AQ_STANDARD = 0; const val AQ_BASS = 1; const val AQ_DIALOG = 2; const val AQ_NIGHT = 3
    const val AQ_AUTO = 4

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun videoQuality(ctx: Context) = sp(ctx).getInt("vq", VQ_HIGH)
    fun audioMode(ctx: Context) = sp(ctx).getInt("aq", AQ_AUTO)
    fun setVideoQuality(ctx: Context, v: Int) = sp(ctx).edit().putInt("vq", v).apply()
    fun setAudioMode(ctx: Context, v: Int) = sp(ctx).edit().putInt("aq", v).apply()

    fun isLowRamVideo(ctx: Context): Boolean = DeviceMode.isLowRamDevice(ctx)
}

object Enhancer {

    /** TrackSelector theo chế độ hình. */
    fun buildTrackSelector(ctx: Context): DefaultTrackSelector {
        val ts = DefaultTrackSelector(ctx)
        applyVideo(ts, EnhanceSettings.videoQuality(ctx), ctx)
        return ts
    }

    /** TrackSelector riêng cho MultiView — ép bitrate thấp + resolution thấp để ổn định RAM */
    fun buildTrackSelectorForMultiView(ctx: Context, isLowRam: Boolean = false): DefaultTrackSelector {
        val ts = DefaultTrackSelector(ctx)
        try {
            val b = ts.buildUponParameters()
                .setPreferredAudioLanguage("vi")
                .setForceHighestSupportedBitrate(false)
            if (isLowRam) {
                // TV RAM thấp: multiview chỉ 720p max, 1.2Mbps
                b.setMaxVideoSize(1280, 720)
                b.setMaxVideoBitrate(1_200_000)
            } else {
                // TV thường: multiview 720p-1080p, 2Mbps
                b.setMaxVideoSize(1920, 1080)
                b.setMaxVideoBitrate(2_000_000)
            }
            ts.setParameters(b.build())
        } catch (_: Exception) {}
        return ts
    }

    fun applyVideo(ts: DefaultTrackSelector, mode: Int, ctx: Context? = null) {
        try {
            val b = ts.buildUponParameters()
            b.setPreferredAudioLanguage("vi")
            val isLowRam = ctx?.let { DeviceMode.isLowRamDevice(it) } ?: false
            when (mode) {
                EnhanceSettings.VQ_HIGH -> {
                    if (isLowRam) {
                        // Low RAM dù chọn Cao nhất vẫn cap 1080p / 3Mbps để tránh OOM
                        b.setForceHighestSupportedBitrate(false)
                        b.setMaxVideoSize(1920, 1080)
                        b.setMaxVideoBitrate(3_000_000)
                    } else {
                        b.setForceHighestSupportedBitrate(true)
                        b.setMaxVideoBitrate(Int.MAX_VALUE)
                    }
                }
                EnhanceSettings.VQ_STABLE -> {
                    b.setForceHighestSupportedBitrate(false)
                    b.setMaxVideoBitrate(if (isLowRam) 1_200_000 else 1_800_000)
                    if (isLowRam) b.setMaxVideoSize(1280, 720)
                }
                else -> {
                    b.setForceHighestSupportedBitrate(false)
                    b.setMaxVideoBitrate(if (isLowRam) 2_000_000 else Int.MAX_VALUE)
                    if (isLowRam) b.setMaxVideoSize(1920, 1080)
                }
            }
            ts.setParameters(b.build())
        } catch (e: Exception) { /* giữ tham số hiện tại */ }
    }

    /** Buffer tối ưu cho từng chế độ — TV RAM thấp giảm buffer mạnh để tiết kiệm RAM */
    fun buildLoadControl(ctx: Context? = null, isMultiView: Boolean = false): LoadControl {
        val isLowRam = ctx?.let { DeviceMode.isLowRamDevice(it) } ?: false
        return when {
            isMultiView && isLowRam -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(8_000, 20_000, 800, 1_500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(0, false)
                .build()
            isMultiView -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(10_000, 30_000, 1_000, 2_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(0, false)
                .build()
            isLowRam -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(10_000, 30_000, 1_000, 2_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            else -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 60_000, 1_200, 3_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }
    }

    fun buildMediaItem(url: String): MediaItem = MediaItem.fromUri(url)
}

/** Gắn hiệu ứng âm thanh (EQ/Bass/Virtualizer/Loudness) vào audio session của player. */
class AudioEnhancer(private val ctx: Context) {
    var notAttached = true; private set
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null

    fun attach(sessionId: Int, mode: Int) {
        detach()
        if (sessionId == 0) return
        notAttached = false
        try {
            when (mode) {
                EnhanceSettings.AQ_BASS -> {
                    eq = Equalizer(0, sessionId).apply {
                        enabled = true
                        val n = numberOfBands.toInt()
                        val lim = bandLevelRange.let { minOf(it[1].toInt(), 700) }
                        if (n > 0) setBandLevel(0.toShort(), lim.toShort())
                        if (n > 1) setBandLevel(1.toShort(), (lim / 2).toShort())
                    }
                    bass = BassBoost(0, sessionId).apply {
                        enabled = true
                        setStrength(900.toShort()) // thang 0..1000
                    }
                }
                EnhanceSettings.AQ_DIALOG -> {
                    // cắt trầm, đẩy mid-high → lời BLV nổi rõ giữa tiếng ồn
                    eq = Equalizer(0, sessionId).apply {
                        enabled = true
                        val n = numberOfBands.toInt()
                        if (n > 0) setBandLevel(0.toShort(), (-500).toShort())
                        if (n > 2) setBandLevel(2.toShort(), 500.toShort())
                        if (n > 3) setBandLevel(3.toShort(), 400.toShort())
                    }
                    loud = LoudnessEnhancer(sessionId).apply {
                        enabled = true
                        setTargetGain(250) // +2.5dB
                    }
                }
                EnhanceSettings.AQ_NIGHT -> {
                    // giảm trầm (bass pháo sáng/đám đông), giữ lời, không rú khi nhỏ tiếng
                    eq = Equalizer(0, sessionId).apply {
                        enabled = true
                        val n = numberOfBands.toInt()
                        if (n > 0) setBandLevel(0.toShort(), (-400).toShort())
                        if (n > 3) setBandLevel(3.toShort(), 250.toShort())
                    }
                    virt = try {
                        Virtualizer(0, sessionId).apply {
                            enabled = true
                            setStrength(300.toShort()) // thang 0..1000
                        }
                    } catch (e: Exception) { null }
                }
                EnhanceSettings.AQ_AUTO -> {
                    // TỰ ĐỘNG (To & Hay): EQ nổi bass + presence (hay) + LoudnessEnhancer (to)
                    eq = Equalizer(0, sessionId).apply {
                        enabled = true
                        val n = numberOfBands.toInt()
                        if (n > 0) setBandLevel(0.toShort(), 300.toShort())   // +3dB bass
                        if (n > 2) setBandLevel(2.toShort(), 250.toShort())   // rõ lời BLV
                        if (n > 3) setBandLevel(3.toShort(), 300.toShort())   // presence
                    }
                    loud = LoudnessEnhancer(sessionId).apply {
                        enabled = true
                        setTargetGain(450) // +4.5dB — to rõ, không méo
                    }
                }
                else -> { /* chuẩn: không fx */ }
            }
        } catch (e: Throwable) { detach() }
    }

    fun detach() {
        try { eq?.release() } catch (e: Exception) {}
        try { bass?.release() } catch (e: Exception) {}
        try { virt?.release() } catch (e: Exception) {}
        try { loud?.release() } catch (e: Exception) {}
        eq = null; bass = null; virt = null; loud = null
        notAttached = true
    }
}
