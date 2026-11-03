package com.arnavagarwal.sound.engine

import android.media.audiofx.Visualizer
import android.util.Log
import com.arnavagarwal.sound.core.SpectralFeatures
import com.arnavagarwal.sound.core.Spectrum
import kotlin.math.hypot
import kotlin.math.log10

/**
 * Listens to the spectrum of the global output mix through Android's Visualizer effect (session 0).
 * It hands us ~20 small FFT snapshots a second; audio is never recorded or stored - only band powers
 * are kept, for the last few seconds, to judge what is playing.
 *
 * Needs RECORD_AUDIO. On phones that refuse session 0 the app still works on metadata alone.
 */
class Analyzer(private val windowSec: Double = 3.0) {
    private var viz: Visualizer? = null
    private val lock = Any()
    private val spectra = ArrayDeque<Spectrum>()
    private val levels = ArrayDeque<Double>()
    /** (time ms, linear frame peak) for the same window: Density steers by how often peaks reach the limiter. */
    private val peaks = ArrayDeque<Pair<Long, Double>>()
    /** (time ms, frame level dBFS as measured) for the last [LEVEL_MS]: the volume coach judges a song by these. */
    private val stampedLevels = ArrayDeque<Pair<Long, Double>>()
    @Volatile var rateHz: Double = 20.0; private set
    @Volatile var running = false; private set
    @Volatile var lastLevelDbfs: Double = -96.0; private set
    @Volatile var lastSpectrum: Spectrum? = null; private set

    /**
     * The curve (dB on [com.arnavagarwal.sound.core.Grid]) that the sound we hear has ALREADY been through,
     * divided back out so features describe the song, not our processing. Set by the Brain only when
     * the playing app is processed by an announced-session chain: those run before the output mix this
     * Visualizer reads. The global chain does not need it - on session 0 the Visualizer is inserted
     * FIRST and hears the sound before our effect (measured, ERRORS E-003).
     */
    @Volatile var compensationDb: DoubleArray? = null

    fun start(): Boolean {
        if (running) return true
        return try {
            val v = Visualizer(0)
            v.enabled = false
            v.captureSize = Visualizer.getCaptureSizeRange()[1]
            v.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
            v.measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS
            val rate = minOf(Visualizer.getMaxCaptureRate(), 20_000)
            rateHz = rate / 1000.0
            val fs = v.samplingRate / 1000.0 // Visualizer reports milliHertz
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vz: Visualizer, waveform: ByteArray, samplingRate: Int) {}
                override fun onFftDataCapture(vz: Visualizer, fft: ByteArray, samplingRate: Int) = onFft(vz, fft, fs)
            }, rate, false, true)
            v.enabled = true
            viz = v; running = true
            true
        } catch (t: Throwable) {
            Log.w("Analyzer", "Visualizer unavailable: ${t.message}")
            running = false
            false
        }
    }

    fun stop() {
        running = false
        runCatching { viz?.enabled = false; viz?.release() }
        viz = null
        synchronized(lock) { spectra.clear(); levels.clear(); peaks.clear(); stampedLevels.clear() }
    }

    private fun onFft(v: Visualizer, fft: ByteArray, fs: Double) {
        // Visualizer FFT layout: [0]=DC real, [1]=Nyquist real, then (re, im) pairs for bins 1..n/2-1
        val n = fft.size
        val mags = DoubleArray(n / 2 + 1)
        mags[0] = kotlin.math.abs(fft[0].toDouble())
        mags[n / 2] = kotlin.math.abs(fft[1].toDouble())
        for (k in 1 until n / 2) mags[k] = hypot(fft[2 * k].toDouble(), fft[2 * k + 1].toDouble())
        val spec = Spectrum.fromFft(mags, fs).let { raw ->
            // hearing our own EQ would make a bass boost look like a bass-heavy song -> more boost (ERRORS E-003)
            val comp = compensationDb ?: return@let raw
            Spectrum(DoubleArray(raw.power.size) { g -> raw.power[g] / Math.pow(10.0, comp[g] / 10) })
        }
        val m = runCatching { Visualizer.MeasurementPeakRms().also { v.getMeasurementPeakRms(it) } }.getOrNull()
        val level = m?.let { it.mRms / 100.0 } // millibels -> dB
            ?: 20 * log10((mags.sum() / mags.size + 1e-9) / 128.0)
        // no measurement = no peak: a peak guessed from the FFT would steer Density on made-up numbers
        val peak = m?.let { Math.pow(10.0, it.mPeak / 2000.0) }
        lastLevelDbfs = level; lastSpectrum = spec
        val now = System.currentTimeMillis()
        synchronized(lock) {
            spectra.addLast(spec); levels.addLast(level)
            if (peak != null) peaks.addLast(now to peak)
            stampedLevels.addLast(now to level)
            val max = (windowSec * rateHz).toInt().coerceAtLeast(8)
            while (spectra.size > max) { spectra.removeFirst(); levels.removeFirst() }
            while (peaks.isNotEmpty() && now - peaks.first().first > windowSec * 1000) peaks.removeFirst()
            while (stampedLevels.isNotEmpty() && now - stampedLevels.first().first > LEVEL_MS) stampedLevels.removeFirst()
        }
    }

    /** Features of the last few seconds, or null when there is not enough signal to judge. */
    fun features(): SpectralFeatures? = synchronized(lock) {
        SpectralFeatures.from(spectra.toList(), levels.toList(), rateHz)
    }

    /** Linear frame peaks of the window measured at or after [sinceMs] (older ones were made with an older lift). */
    fun peaks(sinceMs: Long = 0L): List<Double> = synchronized(lock) { peaks.filter { it.first >= sinceMs }.map { it.second } }

    /** Frame levels (dBFS as measured) of the window at or after [sinceMs]. */
    fun levels(sinceMs: Long = 0L): List<Double> = synchronized(lock) { stampedLevels.filter { it.first >= sinceMs }.map { it.second } }

    /** Average spectrum of the window (for loudness-matching the challenge on THIS song). */
    fun averageSpectrum(): Spectrum? = synchronized(lock) {
        if (spectra.isEmpty()) null
        else Spectrum(DoubleArray(spectra.first().power.size) { g -> spectra.sumOf { it.power[g] } / spectra.size })
    }

    companion object {
        /** The song's level for the volume coach, over the same 10 s as the laptop (desktop Engine.LEVEL_MS). */
        const val LEVEL_MS = 10_000L
    }
}
