package com.arnavagarwal.sound.core

import kotlin.math.log10
import kotlin.math.pow

/**
 * Loudness maths.
 *
 * Why this exists: in any before/after test, the LOUDER version wins even when nothing else improved.
 * So every A/B in the app is loudness-matched: we compute how much louder our processing makes THIS
 * song (from its measured spectrum), and turn the processed version down by exactly that much.
 *
 * Loudness is measured the way broadcast standards do it (ITU-R BS.1770, "K-weighting"): the spectrum
 * is weighted by a curve that roughly follows how sensitive ears are to each frequency, then summed.
 */
object Loudness {
    /** BS.1770 K-weighting, 48 kHz coefficients as published in the Recommendation. */
    private val kStage1 = Biquad.raw(1.53512485958697, -2.69169618940638, 1.19839281085285, -1.69065929318241, 0.73248077421585)
    private val kStage2 = Biquad.raw(1.0, -2.0, 1.0, -1.99004745483398, 0.99007225036621)

    /** K-weighting in dB at each grid frequency. */
    val kWeightDb: DoubleArray = DoubleArray(Grid.size) { kStage1.magnitudeDb(Grid.freqs[it]) + kStage2.magnitudeDb(Grid.freqs[it]) }

    /**
     * Change in loudness (dB) that [eq] causes on a signal with power [spectrum] (linear power per grid point).
     * Positive = the EQ makes it louder.
     */
    fun changeDb(eq: Curve, spectrum: Spectrum = Spectrum.PINK): Double {
        var before = 0.0; var after = 0.0
        for (i in 0 until Grid.size) {
            val p = spectrum.power[i] * 10.0.pow(kWeightDb[i] / 10)
            before += p
            after += p * 10.0.pow(eq.db[i] / 10)
        }
        if (before <= 0.0) return 0.0
        return 10 * log10(after / before)
    }

    /** Gain (dB) to add to [eq] so that its loudness equals the unprocessed signal's. */
    fun matchGainDb(eq: Curve, spectrum: Spectrum = Spectrum.PINK): Double = -changeDb(eq, spectrum)
}

/** Power spectrum sampled on [Grid]: linear power per grid point (relative units). */
class Spectrum(val power: DoubleArray) {
    init { require(power.size == Grid.size) }

    val total: Double get() = power.sum()

    /** This spectrum after passing through [c] (e.g. what a phone speaker lets through). */
    fun through(c: Curve) = Spectrum(DoubleArray(Grid.size) { power[it] * 10.0.pow(c.db[it] / 10) })

    /** Fraction of total power below [hz]. */
    fun fractionBelow(hz: Double): Double {
        val t = total; if (t <= 0) return 0.0
        return Grid.freqs.indices.filter { Grid.freqs[it] < hz }.sumOf { power[it] } / t
    }

    /** Spectral centroid in Hz, computed in log-frequency (the "brightness" of the sound). */
    fun centroidHz(): Double {
        val t = total; if (t <= 0) return 1_000.0
        val logC = Grid.freqs.indices.sumOf { power[it] * log10(Grid.freqs[it]) } / t
        return 10.0.pow(logC)
    }

    /** Spectral flatness 0..1: 1 = noise-like, near 0 = tonal. Geometric mean / arithmetic mean. */
    fun flatness(): Double {
        val eps = 1e-12
        val logMean = power.sumOf { log10(it + eps) } / power.size
        val mean = power.average() + eps
        return (10.0.pow(logMean) / mean).coerceIn(0.0, 1.0)
    }

    companion object {
        /** Pink noise: equal power per octave = equal power per point on a log grid. */
        val PINK = Spectrum(DoubleArray(Grid.size) { 1.0 })

        /**
         * From FFT magnitudes: [mags][k] is the magnitude of bin k, at frequency k * fs / (2 * (mags.size - 1)).
         * Each grid point collects the power of the bins in its log band; empty bands (low end, coarse FFT)
         * are filled by interpolation from their neighbours so the curve has no holes.
         */
        fun fromFft(mags: DoubleArray, fs: Double): Spectrum {
            val n = mags.size
            require(n >= 4)
            val binHz = fs / (2.0 * (n - 1))
            val half = (Grid.fMax / Grid.fMin).pow(0.5 / (Grid.size - 1))
            val out = DoubleArray(Grid.size) { Double.NaN }
            for (g in 0 until Grid.size) {
                val lo = Grid.freqs[g] / half; val hi = Grid.freqs[g] * half
                var s = 0.0; var c = 0
                for (k in 1 until n) {
                    val f = k * binHz
                    if (f >= lo && f < hi) { s += mags[k] * mags[k]; c++ }
                }
                if (c > 0) out[g] = s
            }
            // fill holes by nearest filled neighbour, scaled by band width so density stays consistent
            val filled = out.indices.filter { !out[it].isNaN() }
            if (filled.isEmpty()) return Spectrum(DoubleArray(Grid.size))
            for (g in out.indices) if (out[g].isNaN()) {
                val nearest = filled.minBy { kotlin.math.abs(it - g) }
                out[g] = out[nearest] * (Grid.freqs[g] / Grid.freqs[nearest]).coerceAtMost(1.0)
            }
            return Spectrum(out)
        }
    }
}

/**
 * ISO 226:2003 equal-loudness contours. At low volume the ear hears bass (and extreme treble) much less,
 * so quiet music sounds thin. Compensating for that as volume drops is Wavelet's best-loved feature
 * (research/competitors.md). Table values are the standard's (29 frequencies, 20 Hz .. 12.5 kHz).
 */
object EqualLoudness {
    /** The most [compensation] ever adds at one frequency (the phone card quotes it: Words.ABOUT_QUIET). */
    const val MAX_BOOST_DB = 9.0
    private val f = doubleArrayOf(20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0,
        500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0)
    private val af = doubleArrayOf(0.532, 0.506, 0.480, 0.455, 0.432, 0.409, 0.387, 0.367, 0.349, 0.330, 0.315, 0.301, 0.288,
        0.276, 0.267, 0.259, 0.253, 0.250, 0.246, 0.244, 0.243, 0.243, 0.243, 0.242, 0.242, 0.245, 0.254, 0.271, 0.301)
    private val lu = doubleArrayOf(-31.6, -27.2, -23.0, -19.1, -15.9, -13.0, -10.3, -8.1, -6.2, -4.5, -3.1, -2.0, -1.1, -0.4,
        0.0, 0.3, 0.5, 0.0, -2.7, -4.1, -1.0, 1.7, 2.5, 1.2, -2.1, -7.1, -11.2, -10.7, -3.1)
    private val tf = doubleArrayOf(78.5, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9, 14.4, 11.4, 8.6, 6.2,
        4.4, 3.0, 2.2, 2.4, 3.5, 1.7, -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3)

    /** Sound pressure level (dB SPL) needed at table frequency index [i] to sound as loud as [phon]. */
    fun splAt(i: Int, phon: Double): Double {
        val a = 4.47e-3 * (10.0.pow(0.025 * phon) - 1.15) + (0.4 * 10.0.pow((tf[i] + lu[i]) / 10 - 9)).pow(af[i])
        return 10 / af[i] * log10(a) - lu[i] + 94
    }

    /** The contour at any frequency (log-interpolated; held flat beyond the table ends). */
    fun spl(freq: Double, phon: Double): Double {
        if (freq <= f.first()) return splAt(0, phon)
        if (freq >= f.last()) return splAt(f.size - 1, phon)
        val i = f.indexOfLast { it <= freq }.coerceAtMost(f.size - 2)
        val t = log10(freq / f[i]) / log10(f[i + 1] / f[i])
        return splAt(i, phon) * (1 - t) + splAt(i + 1, phon) * t
    }

    /**
     * Compensation curve: extra gain per frequency so that listening at [listenPhon] keeps the tonal
     * balance heard at [referencePhon]. Normalised to 0 dB at 1 kHz, scaled by [strength] (0..1) and
     * capped at [maxBoostDb] so quiet listening never turns into a bass cannon.
     */
    fun compensation(listenPhon: Double, referencePhon: Double = 80.0, strength: Double = 0.6, maxBoostDb: Double = MAX_BOOST_DB): Curve {
        if (listenPhon >= referencePhon) return Curve.FLAT
        val q1k = spl(1000.0, listenPhon); val r1k = spl(1000.0, referencePhon)
        return Curve(DoubleArray(Grid.size) { g ->
            val fr = Grid.freqs[g]
            val need = (spl(fr, listenPhon) - q1k) - (spl(fr, referencePhon) - r1k)
            (need * strength).coerceIn(0.0, maxBoostDb)
        })
    }
}
