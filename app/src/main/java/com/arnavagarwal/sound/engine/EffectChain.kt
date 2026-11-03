package com.arnavagarwal.sound.engine

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import com.arnavagarwal.sound.core.DEFAULT_BAND_CUTOFFS
import com.arnavagarwal.sound.core.Dynamics

/**
 * One DynamicsProcessing instance on one audio session (0 = the global output mix).
 * Layout: pre-EQ (our curve, 20 bands) -> 1-band compressor (speech / night levelling) -> limiter
 * (so boosts never clip). Android runs the audio; we only push numbers.
 *
 * Every call is wrapped: an effect can be torn away by the system at any moment (another app takes
 * control, the session ends), and that must never crash the engine.
 */
class EffectChain private constructor(val sessionId: Int, private val dp: DynamicsProcessing) {

    @Volatile var hasControl: Boolean = dp.hasControl(); private set
    private val cutoffs = DEFAULT_BAND_CUTOFFS

    init {
        dp.setControlStatusListener { _, controlGranted -> hasControl = controlGranted }
        dp.setEnableStatusListener { _, _ -> }
    }

    fun setEnabled(on: Boolean) = safe("enable") { dp.enabled = on }
    val enabled: Boolean get() = runCatching { dp.enabled }.getOrDefault(false)

    /** Push band gains (dB, one per [DEFAULT_BAND_CUTOFFS]). */
    fun setBands(gainsDb: DoubleArray) = safe("bands") {
        for (i in cutoffs.indices) {
            dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cutoffs[i].toFloat(), gainsDb[i].toFloat()))
        }
    }

    /** Input gain per channel (dB): preamp, loudness-match offset and the 8D pan all land here. */
    fun setInputGains(leftDb: Double, rightDb: Double) = safe("gain") {
        dp.setInputGainbyChannel(0, leftDb.toFloat())
        dp.setInputGainbyChannel(1, rightDb.toFloat())
    }

    fun setDynamics(d: Dynamics) = safe("mbc") {
        val on = d.ratio > 1.01
        val band = DynamicsProcessing.MbcBand(
            on, 20_000f, d.attackMs.toFloat(), d.releaseMs.toFloat(), d.ratio.toFloat(), d.thresholdDb.toFloat(),
            3f /*knee*/, -90f /*noise gate off*/, 1f, 0f /*pre gain*/, if (on) d.makeupDb.toFloat() else 0f,
        )
        dp.setMbcBandAllChannelsTo(0, band)
    }

    fun setLimiter(on: Boolean) = safe("limiter") {
        dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, on, 0, 1f, 60f, 10f, -1f, 0f))
    }

    fun release() = safe("release") { dp.enabled = false; dp.release() }

    private inline fun safe(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.w(TAG, "session $sessionId: $what failed: ${t.message}") }
    }

    companion object {
        private const val TAG = "EffectChain"
        /** High priority: we want control over the session even if a lower-priority effect is present. */
        private const val PRIORITY = Int.MAX_VALUE

        fun config(): DynamicsProcessing.Config =
            DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, DEFAULT_BAND_CUTOFFS.size,  // pre-EQ
                true, 1,                           // compressor, 1 band
                false, 0,                          // post-EQ unused
                true,                              // limiter
            ).build()

        /** Attach to [sessionId]; null when the device refuses (e.g. session 0 on some phones). */
        fun attach(sessionId: Int): EffectChain? = try {
            val dp = DynamicsProcessing(PRIORITY, sessionId, config())
            EffectChain(sessionId, dp).also { it.setLimiter(true); it.setEnabled(true) }
        } catch (t: Throwable) {
            Log.w(TAG, "attach to session $sessionId refused: ${t.message}")
            null
        }
    }
}
