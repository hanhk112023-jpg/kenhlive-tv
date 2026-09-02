package com.kenhlive.tv

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/** Cài đặt nâng cao hình/âm — lưu SharedPreferences, áp dụng mọi màn hình. */
object EnhanceSettings {
    private const val PREF = "enhance"
    const val VQ_AUTO = 0; const val VQ_HIGH = 1; const val VQ_STABLE = 2
    const val AQ_STANDARD = 0; const val AQ_BASS = 1; const val AQ_DIALOG = 2; const val AQ_NIGHT = 3

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun videoQuality(ctx: Context) = sp(ctx).getInt("vq", VQ_HIGH)
    fun audioMode(ctx: Context) = sp(ctx).getInt("aq", AQ_STANDARD)
    fun setVideoQuality(ctx: Context, v: Int) = sp(ctx).edit().putInt("vq", v).apply()
    fun setAudioMode(ctx: Context, v: Int) = sp(ctx).edit().putInt("aq", v).apply()
}

object Enhancer {

    /** TrackSelector theo chế độ hình. */
    fun buildTrackSelector(ctx: Context): DefaultTrackSelector {
        val ts = DefaultTrackSelector(ctx)
        applyVideo(ts, EnhanceSettings.videoQuality(ctx))
        return ts
    }

    fun applyVideo(ts: DefaultTrackSelector, mode: Int) {
        try {
            val b = ts.buildUponParameters()
            b.setPreferredAudioLanguage("vi")
            when (mode) {
                // Cao nhất: ép chọn rendition bitrate cao nhất khả dụng (không tụt khi mạng tạm ổn)
                EnhanceSettings.VQ_HIGH -> {
                    b.clearConstraints()
                    b.setPreferredAudioLanguage("vi")
                    b.setForceHighestSupportedBitrate(true)
                }
                // Ổn định: cap bitrate ~1.8Mbps → hết giật trên mạng yếu, đổi lấy nét
                EnhanceSettings.VQ_STABLE -> {
                    b.setForceHighestSupportedBitrate(false)
                    b.setMaxVideoBitrateAllowed(1_800_000)
                }
                else -> {
                    b.clearConstraints()
                    b.setPreferredAudioLanguage("vi")
                    b.setForceHighestSupportedBitrate(false)
                }
            }
            ts.setParameters(b.build())
        } catch (e: Exception) { /* giữ tham số hiện tại */ }
    }

    /** Buffer lớn → ít rebuffer, adaptive không tụt chất lượng khi mạng dao động nhẹ. */
    fun buildLoadControl(): LoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(25_000, 120_000, 1_500, 4_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

    /** MediaItem kèm chuẩn hóa âm lượng (loudness + clipping) — cân bằng phòng to/nhỏ. */
    fun buildMediaItem(url: String): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setRequestConfiguration(
                MediaItem.RequestConfiguration.Builder()
                    .setLoudnessNormalizationMode(MediaItem.RequestConfiguration.LOUDNESS_NORMALIZATION_MODE_AUTO)
                    .build()
            )
            .build()
}

/** Gắn hiệu ứng âm thanh (EQ/Bass/Virtualizer/DynamicsProcessing) vào audio session của player. */
class AudioEnhancer(private val ctx: Context) {
    var notAttached = true; private set
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var dyn: DynamicsProcessing? = null

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
                        if (n > 0) setBandLevel(0.toShort(), 700.toShort())
                        if (n > 1) setBandLevel(1.toShort(), 300.toShort())
                    }
                    bass = BassBoost(0, sessionId).apply { enabled = true; setStrength(900.toShort()) }
                }
                EnhanceSettings.AQ_DIALOG -> {
                    eq = Equalizer(0, sessionId).apply {
                        enabled = true
                        val n = numberOfBands.toInt()
                        if (n > 0) setBandLevel(0.toShort(), (-500).toShort())
                        if (n > 2) setBandLevel(2.toShort(), 500.toShort())
                        if (n > 3) setBandLevel(3.toShort(), 400.toShort())
                    }
                    setupCompressor(sessionId, dialog = true, night = false)
                }
                EnhanceSettings.AQ_NIGHT -> {
                    eq = Equalizer(0, sessionId).apply {
                        enabled = true
                        val n = numberOfBands.toInt()
                        if (n > 0) setBandLevel(0.toShort(), (-400).toShort())
                    }
                    setupCompressor(sessionId, dialog = false, night = true)
                    virt = try { Virtualizer(0, sessionId).apply { enabled = true; setStrength(300.toShort()) } }
                           catch (e: Exception) { null }
                }
                else -> { /* chuẩn: chỉ dùng normalization của Media3 */ }
            }
        } catch (e: Throwable) { detach() }
    }

    private fun setupCompressor(sessionId: Int, dialog: Boolean, night: Boolean) {
        if (Build.VERSION.SDK_INT < 28) return
        try {
            val nb = 4
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_PRIMITIVE_LANGUAGE,
                DynamicsProcessing.PreAmpBand.Config(1, false),
                DynamicsProcessing.InputBand.Config(nb, false),
                DynamicsProcessing.PostEqBand.Config(nb, true),
                DynamicsProcessing.CompressorBand.Config(nb, true),
                DynamicsProcessing.LimiterBand.Config(1, true)
            ).build()
            val dp = DynamicsProcessing(0, sessionId)
            dp.setConfig(cfg)
            val post = dp.postEqBands
            for (i in 0 until nb) {
                val band = post[i]
                band.bypass = false
                if (dialog) { band.gainLow = -6f; band.gainMid = 4f; band.gainHigh = -1f }
                post[i] = band
            }
            dp.postEqBands = post
            val comp = dp.compressorBands
            for (i in 0 until nb) {
                val b = comp[i]
                b.bypass = false; b.enabled = true
                b.threshold = if (night) -30f else -20f
                b.ratio = if (night) 8f else 3f
                b.attackTime = 6f; b.releaseTime = 150f; b.kneeWidth = 6f
                comp[i] = b
            }
            dp.compressorBands = comp
            val lim = dp.limiterBands
            if (lim.isNotEmpty()) {
                val l = lim[0]; l.bypass = false; l.enabled = true; l.threshold = -1f
                lim[0] = l
                dp.limiterBands = lim
            }
            dp.enabled = true
            dyn = dp
        } catch (e: Throwable) { /* device không hỗ trợ → bỏ qua */ }
    }

    fun detach() {
        try { eq?.release() } catch (e: Exception) {}
        try { bass?.release() } catch (e: Exception) {}
        try { virt?.release() } catch (e: Exception) {}
        try { dyn?.release() } catch (e: Exception) {}
        eq = null; bass = null; virt = null; dyn = null
        notAttached = true
    }
}
