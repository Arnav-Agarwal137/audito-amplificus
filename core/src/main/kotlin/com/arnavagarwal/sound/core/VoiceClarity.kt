package com.arnavagarwal.sound.core

import kotlin.math.log10
import kotlin.math.pow

/**
 * How clearly voices come through (his ask, 22 Sep: "a bar telling voice clearance").
 *
 * An ESTIMATE from the spectrum alone, and the screen says so: the words of speech and singing live
 * mostly at 1-4 kHz (consonants), while the low "boom" that buries them lives at 150-500 Hz. So the
 * balance = mean power 1-4 kHz minus mean power 150-500 Hz, in dB. Speech on its own measures about
 * -10 dB (its long-term average spectrum), which maps to ~0.7; a voice buried under bass reads lower.
 */
object VoiceClarity {
    /** Balance in dB of [spectrum] after [curve] is applied (null = as it is). Null when either range is silent. */
    fun balanceDb(spectrum: Spectrum, curve: Curve? = null): Double? {
        fun mean(lo: Double, hi: Double): Double {
            val idx = Grid.freqs.indices.filter { Grid.freqs[it] in lo..hi }
            return idx.sumOf { spectrum.power[it] * 10.0.pow((curve?.db?.get(it) ?: 0.0) / 10) } / idx.size
        }
        val voice = mean(1_000.0, 4_000.0)
        val boom = mean(150.0, 500.0)
        if (voice <= 0.0 || boom <= 0.0) return null
        return 10 * log10(voice / boom)
    }

    /** 0 (muffled) .. 1 (clear). */
    fun score(spectrum: Spectrum, curve: Curve? = null): Double? = balanceDb(spectrum, curve)?.let { ((it + 20) / 14).coerceIn(0.0, 1.0) }
}
