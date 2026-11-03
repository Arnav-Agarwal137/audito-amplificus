package com.arnavagarwal.sound.core

import com.arnavagarwal.sound.core.FilterType.HIGH_PASS
import com.arnavagarwal.sound.core.FilterType.HIGH_SHELF
import com.arnavagarwal.sound.core.FilterType.LOW_SHELF
import com.arnavagarwal.sound.core.FilterType.PEAK

/** Settings for the dynamics (compressor) stage: how much quiet parts are lifted relative to loud ones. */
data class Dynamics(val thresholdDb: Double, val ratio: Double, val attackMs: Double, val releaseMs: Double, val makeupDb: Double) {
    companion object {
        val OFF = Dynamics(0.0, 1.0, 10.0, 200.0, 0.0)
        /** Gentle levelling so a quiet voice and a loud one stay close: lectures, podcasts. */
        val SPEECH = Dynamics(-28.0, 2.5, 5.0, 150.0, 4.0)
        /** Night mode: dialogue up, explosions down, stronger than speech. */
        val NIGHT = Dynamics(-34.0, 4.0, 3.0, 250.0, 7.0)
    }
}

/** A starting sound for one kind of content. */
data class Preset(val kind: ContentKind, val filters: List<ParametricFilter>, val dynamics: Dynamics, val allow8D: Boolean) {
    val curve: Curve by lazy { Curve.of(filters) }
}

/**
 * Starting sounds per content kind. Deliberately modest (boosts <= ~4.5 dB): a preset is a starting
 * point the [TasteLearner] personalises, not a flavour to force on everyone. Reasoning per preset is
 * in the comments. Claude cannot hear: these are set from standard mixing practice and must be checked
 * by ear in his end-of-build test (RULES.md).
 */
object Presets {
    val all: Map<ContentKind, Preset> = listOf(
        // Clear voice: cut rumble below the voice, a little less "boxiness" at 250 Hz, lift consonants at 3 kHz.
        Preset(ContentKind.SPEECH, listOf(
            ParametricFilter(HIGH_PASS, 80.0, q = 0.707),
            ParametricFilter(PEAK, 250.0, -2.0, 1.0),
            ParametricFilter(PEAK, 3_000.0, 3.5, 0.9),
            ParametricFilter(HIGH_SHELF, 9_000.0, -1.0, 0.7),
        ), Dynamics.SPEECH, allow8D = false),
        // Sub-bass and punch up, a touch of air so it does not turn muddy.
        Preset(ContentKind.BASS_HEAVY, listOf(
            ParametricFilter(LOW_SHELF, 90.0, 4.5, 0.7),
            ParametricFilter(PEAK, 350.0, -1.0, 1.0),
            ParametricFilter(HIGH_SHELF, 10_000.0, 1.5, 0.7),
        ), Dynamics.OFF, allow8D = true),
        // His exact complaint: bass boost ruins smooth songs. So bass slightly DOWN, warmth kept, harshness softened.
        Preset(ContentKind.SMOOTH, listOf(
            ParametricFilter(LOW_SHELF, 100.0, -2.0, 0.7),
            ParametricFilter(PEAK, 220.0, 1.0, 0.9),
            ParametricFilter(PEAK, 6_000.0, -1.5, 1.2),
        ), Dynamics.OFF, allow8D = true),
        // Vocals forward, moderate bass: the typical Bollywood/pop balance.
        Preset(ContentKind.VOCAL_POP, listOf(
            ParametricFilter(LOW_SHELF, 100.0, 2.0, 0.7),
            ParametricFilter(PEAK, 2_500.0, 2.0, 1.0),
            ParametricFilter(HIGH_SHELF, 11_000.0, 1.0, 0.7),
        ), Dynamics.OFF, allow8D = true),
        // Almost flat: orchestral recordings are balanced on purpose; just a little air.
        Preset(ContentKind.CLASSICAL, listOf(
            ParametricFilter(HIGH_SHELF, 12_000.0, 1.0, 0.7),
        ), Dynamics.OFF, allow8D = false),
        // Mild "V": weight and bite, less mid congestion.
        Preset(ContentKind.ENERGETIC, listOf(
            ParametricFilter(LOW_SHELF, 80.0, 2.5, 0.7),
            ParametricFilter(PEAK, 400.0, -1.5, 1.0),
            ParametricFilter(PEAK, 4_000.0, 2.0, 1.0),
        ), Dynamics.OFF, allow8D = true),
        // Film: dialogue clarity + some low-end impact, light levelling.
        Preset(ContentKind.MOVIE, listOf(
            ParametricFilter(LOW_SHELF, 60.0, 2.0, 0.7),
            ParametricFilter(PEAK, 2_500.0, 2.0, 1.0),
        ), Dynamics(-26.0, 1.8, 5.0, 200.0, 2.0), allow8D = false),
    ).associateBy { it.kind }

    /**
     * Blend presets by the classifier's probabilities, then scale by how sure it is: when unsure, the
     * result stays close to flat instead of committing to the wrong flavour.
     */
    fun blend(profile: ContentProfile, certainty: Double): Curve {
        var c = Curve.FLAT
        for ((k, p) in profile.probs) c += all.getValue(k).curve * p
        val strength = 0.5 + 0.5 * certainty.coerceIn(0.0, 1.0)
        return c * strength
    }
}

/**
 * The four knobs a person adjusts (and the learner learns): bass, warmth, clarity, treble.
 * Kept to four so a correction is one gesture, not a 10-band puzzle.
 */
data class Tone(val bass: Double = 0.0, val warmth: Double = 0.0, val clarity: Double = 0.0, val treble: Double = 0.0) {
    fun toArray() = doubleArrayOf(bass, warmth, clarity, treble)
    operator fun plus(o: Tone) = Tone(bass + o.bass, warmth + o.warmth, clarity + o.clarity, treble + o.treble)
    operator fun times(k: Double) = Tone(bass * k, warmth * k, clarity * k, treble * k)
    fun clamp(lim: Double) = Tone(bass.coerceIn(-lim, lim), warmth.coerceIn(-lim, lim), clarity.coerceIn(-lim, lim), treble.coerceIn(-lim, lim))

    fun curve(): Curve = Curve.of(listOf(
        ParametricFilter(LOW_SHELF, 105.0, bass, 0.7),
        ParametricFilter(PEAK, 300.0, warmth, 0.8),
        ParametricFilter(PEAK, 2_800.0, clarity, 0.8),
        ParametricFilter(HIGH_SHELF, 8_000.0, treble, 0.7),
    ))

    companion object {
        val ZERO = Tone()
        fun of(a: DoubleArray) = Tone(a[0], a[1], a[2], a[3])

        private val basis: List<DoubleArray> = (0 until 4).map { k -> of(DoubleArray(4) { if (it == k) 1.0 else 0.0 }).curve().db }
        private val fitRange = Grid.freqs.indices.filter { Grid.freqs[it] in 30.0..16_000.0 }

        /**
         * The four knob settings that come closest (least squares over 30 Hz - 16 kHz) to [c]. Used to show on the
         * sliders what the app itself has chosen, so they do not sit at an empty zero while the app is tuning.
         */
        fun fit(c: Curve): Tone {
            val m = Array(4) { DoubleArray(4) }; val v = DoubleArray(4)
            for (i in fitRange) for (r in 0 until 4) {
                v[r] += basis[r][i] * c.db[i]
                for (s in 0 until 4) m[r][s] += basis[r][i] * basis[s][i]
            }
            // Gaussian elimination with partial pivoting on the 4x4 normal equations
            for (col in 0 until 4) {
                val piv = (col until 4).maxBy { kotlin.math.abs(m[it][col]) }
                m[col] = m[piv].also { m[piv] = m[col] }; v[col] = v[piv].also { v[piv] = v[col] }
                for (r in col + 1 until 4) {
                    val f = m[r][col] / m[col][col]
                    for (s in col until 4) m[r][s] -= f * m[col][s]
                    v[r] -= f * v[col]
                }
            }
            val x = DoubleArray(4)
            for (r in 3 downTo 0) x[r] = (v[r] - (r + 1 until 4).sumOf { m[r][it] * x[it] }) / m[r][r]
            return of(DoubleArray(4) { x[it] + 0.0 }) // + 0.0 turns -0.0 into 0.0, so a flat curve equals Tone.ZERO
        }
    }
}

/**
 * The three extra sliders under "More controls" (his ask, 22 Sep: "more in a drop down"). Unlike [Tone]
 * they STAY ON for everything until moved back: not reset on a song change and not given to the learner
 * (the learner stores exactly the 4 [Tone] numbers - do not grow Tone to fit these in).
 */
data class Fine(val rumble: Double = 0.0, val presence: Double = 0.0, val air: Double = 0.0) {
    fun curve(): Curve = Curve.of(listOf(
        ParametricFilter(PEAK, 50.0, rumble, 0.9),        // deep rumble: the lowest boom, felt more than heard
        ParametricFilter(PEAK, 5_000.0, presence, 1.0),   // presence: singers sound closer
        ParametricFilter(HIGH_SHELF, 14_000.0, air, 0.7), // air: the faint openness above the cymbals
    ))

    companion object { val ZERO = Fine() }
}

/** "How strong" as three plain choices instead of a slider (his ask: simpler settings). */
object Strength {
    const val GENTLE = 0.5
    const val NORMAL = 1.0
    const val STRONG = 1.4
    val levels = listOf(GENTLE, NORMAL, STRONG)

    /** The choice to show as selected; an old slider value (say 0.73) lights the nearest one. */
    fun nearest(x: Double): Double = levels.minBy { kotlin.math.abs(it - x) }

    /** One step gentler than [x], or null when it is already at the gentlest. */
    fun stepDown(x: Double): Double? = levels.lastOrNull { it < x - 0.01 }
}
