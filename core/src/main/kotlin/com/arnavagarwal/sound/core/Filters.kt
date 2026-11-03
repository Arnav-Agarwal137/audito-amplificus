package com.arnavagarwal.sound.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Filter shapes. Names match AutoEq's file tokens (PK, LSC, HSC, LP, HP). */
enum class FilterType { PEAK, LOW_SHELF, HIGH_SHELF, LOW_PASS, HIGH_PASS }

/** One parametric filter: centre/corner frequency [fc] in Hz, [gainDb], and [q] (bandwidth; higher = narrower). */
data class ParametricFilter(val type: FilterType, val fc: Double, val gainDb: Double = 0.0, val q: Double = 0.707) {
    init {
        require(fc > 0) { "fc must be positive: $fc" }
        require(q > 0) { "q must be positive: $q" }
    }
}

/**
 * Second-order ("biquad") filter from the RBJ Audio EQ Cookbook. We never run audio through this class
 * (Android's DynamicsProcessing does the audio); we use it to compute the exact response a filter would
 * have, so curves can be built and MEASURED in tests.
 */
class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a0: Double, private val a1: Double, private val a2: Double,
) {
    /** Magnitude response in dB at frequency [f] (Hz) for sample rate [fs]. */
    fun magnitudeDb(f: Double, fs: Double = 48_000.0): Double {
        val w = 2 * PI * f / fs
        val c1 = cos(w); val s1 = sin(w)
        val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
        val dr = a0 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
        val mag2 = (nr * nr + ni * ni) / (dr * dr + di * di)
        return 10 * log10(mag2)
    }

    companion object {
        /** A biquad from published coefficients (a0 = 1), e.g. the ITU-R BS.1770 K-weighting stages. */
        fun raw(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) = Biquad(b0, b1, b2, 1.0, a1, a2)

        fun of(p: ParametricFilter, fs: Double = 48_000.0): Biquad {
            val a = 10.0.pow(p.gainDb / 40)
            val w0 = 2 * PI * p.fc / fs
            val cw = cos(w0); val sw = sin(w0)
            val alpha = sw / (2 * p.q)
            return when (p.type) {
                FilterType.PEAK -> Biquad(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
                FilterType.LOW_SHELF -> {
                    val k = 2 * sqrt(a) * alpha
                    Biquad(
                        a * ((a + 1) - (a - 1) * cw + k), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - k),
                        (a + 1) + (a - 1) * cw + k, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - k,
                    )
                }
                FilterType.HIGH_SHELF -> {
                    val k = 2 * sqrt(a) * alpha
                    Biquad(
                        a * ((a + 1) + (a - 1) * cw + k), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - k),
                        (a + 1) - (a - 1) * cw + k, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - k,
                    )
                }
                FilterType.LOW_PASS -> Biquad((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
                FilterType.HIGH_PASS -> Biquad((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
            }
        }
    }
}

/**
 * The frequencies every curve in the app is sampled at: [size] points spaced evenly on a log scale
 * from 20 Hz to 20 kHz (the range of human hearing). Log spacing because pitch is heard logarithmically.
 */
object Grid {
    const val size = 96
    const val fMin = 20.0
    const val fMax = 20_000.0
    val freqs: DoubleArray = DoubleArray(size) { i -> fMin * (fMax / fMin).pow(i.toDouble() / (size - 1)) }
}

/** A gain curve: one dB value per [Grid] frequency. Immutable. */
class Curve(val db: DoubleArray) {
    init { require(db.size == Grid.size) { "curve must have ${Grid.size} points, has ${db.size}" } }

    operator fun plus(o: Curve) = Curve(DoubleArray(Grid.size) { db[it] + o.db[it] })
    operator fun times(k: Double) = Curve(DoubleArray(Grid.size) { db[it] * k })
    fun plusConst(k: Double) = Curve(DoubleArray(Grid.size) { db[it] + k })
    fun clamp(lo: Double, hi: Double) = Curve(DoubleArray(Grid.size) { db[it].coerceIn(lo, hi) })
    fun max(): Double = db.max()
    fun min(): Double = db.min()

    /** Gain at any frequency, linearly interpolated in log-frequency. */
    fun at(f: Double): Double {
        val fr = Grid.freqs
        if (f <= fr.first()) return db.first()
        if (f >= fr.last()) return db.last()
        val x = log10(f / Grid.fMin) / log10(Grid.fMax / Grid.fMin) * (Grid.size - 1)
        val i = x.toInt().coerceIn(0, Grid.size - 2)
        val t = x - i
        return db[i] * (1 - t) + db[i + 1] * t
    }

    /**
     * Average gain per band, for Android DynamicsProcessing, whose EQ bands are defined by their UPPER
     * cutoff frequency (band i spans cutoff[i-1]..cutoff[i]). Averaged in dB over the grid points inside
     * each band; a band too narrow to contain a grid point takes the interpolated value at its centre.
     */
    fun toBands(cutoffs: DoubleArray): DoubleArray {
        require(cutoffs.isNotEmpty() && (1 until cutoffs.size).all { cutoffs[it] > cutoffs[it - 1] }) { "cutoffs must increase" }
        return DoubleArray(cutoffs.size) { b ->
            val lo = if (b == 0) Grid.fMin / 2 else cutoffs[b - 1]
            val hi = cutoffs[b]
            val inside = Grid.freqs.indices.filter { Grid.freqs[it] > lo && Grid.freqs[it] <= hi }
            if (inside.isEmpty()) at(sqrt(lo * hi)) else inside.map { db[it] }.average()
        }
    }

    fun approxEquals(o: Curve, tolDb: Double) = db.indices.all { abs(db[it] - o.db[it]) <= tolDb }

    companion object {
        val FLAT = Curve(DoubleArray(Grid.size))
        fun of(filters: List<ParametricFilter>, preampDb: Double = 0.0, fs: Double = 48_000.0): Curve {
            val bq = filters.map { Biquad.of(it, fs) }
            return Curve(DoubleArray(Grid.size) { i -> preampDb + bq.sumOf { it.magnitudeDb(Grid.freqs[i], fs) } })
        }
        fun of(vararg filters: ParametricFilter) = of(filters.toList())
    }
}

/**
 * Band layout used on the phone: 20 bands, roughly third-octave-ish at the ends and denser where
 * ears are most sensitive. Upper cutoffs in Hz; the last band runs to Nyquist.
 */
val DEFAULT_BAND_CUTOFFS = doubleArrayOf(
    40.0, 63.0, 90.0, 125.0, 180.0, 250.0, 355.0, 500.0, 710.0, 1_000.0,
    1_400.0, 2_000.0, 2_800.0, 4_000.0, 5_600.0, 8_000.0, 11_200.0, 14_000.0, 17_000.0, 24_000.0,
)
