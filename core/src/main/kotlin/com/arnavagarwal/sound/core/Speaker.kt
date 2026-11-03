package com.arnavagarwal.sound.core

import com.arnavagarwal.sound.core.FilterType.HIGH_PASS
import com.arnavagarwal.sound.core.FilterType.PEAK
import kotlin.math.log10

/**
 * The phone's own speaker. His question, 22 Sep: "Why is the difference not hearable in normal without earbuds?"
 *
 * A phone speaker is a few millimetres across and plays almost nothing below ~200-300 Hz. The plan made for earbuds is
 * wasted there: its bass boost lands where the speaker cannot follow, and the loudness match then turns the whole song
 * down to pay for a boost nobody hears (his phone test: Bass fully right "just sounds quieter"). On the speaker the
 * plan is changed in three ways:
 *  1. the deep bass (below ~150 Hz) is taken out instead of boosted: the speaker cannot play it, and it only eats
 *     headroom, so the limiter pulls everything else down;
 *  2. the bass the plan asked for is MOVED UP to 250-450 Hz, the lowest notes a phone speaker can play, which the ear
 *     reads as "more bass" (the brain fills in the missing low note from its overtones);
 *  3. a lift in the presence range (~3 kHz), where phone speakers are at their best and where voices and the attack of
 *     instruments live.
 * Loudness is matched through [response], so both sides of the blind test are judged on what the speaker can play.
 * Claude cannot hear: these numbers are standard small-speaker practice and his ear decides (RULES.md).
 */
object Speaker {
    /**
     * One kind of small built-in speaker. [highPassHz] = where its bass falls away (12 dB per octave below);
     * [fullnessDb]/[presenceDb] = its own lifts at amount 1.0 (the app's "Normal" strength).
     */
    data class Profile(
        val label: String,
        val highPassHz: Double,
        val movedBassHz: Double,
        val fullnessDb: Double,
        val presenceDb: Double,
    ) {
        /** What reaches the ear from this speaker: flat above [highPassHz], falling 12 dB per octave below. */
        val response: Curve = Curve.of(ParametricFilter(HIGH_PASS, highPassHz, q = 0.707))
    }

    /** A phone speaker (22 Sep, his phone test). */
    val PHONE = Profile("phone speaker", highPassHz = 250.0, movedBassHz = 350.0, fullnessDb = 3.5, presenceDb = 4.0)

    /**
     * Laptop speakers (23 Sep, his ear test on his Lenovo): a gentle curve was "No difference"; HP 120 / +8 at 350 Hz /
     * +6 at 3 kHz was preferred, and he asked for a SIGNIFICANT change. So at Normal this lands near that curve
     * (fullness + the deep bass the plan moves up = about +8). The 180 Hz roll-off is a typical laptop figure, NOT a
     * measurement of his speakers; it only decides what the loudness match counts.
     * TRIED 23 Sep ~20:30 and rejected by his ear: fullness 10 / presence 10 (+12 / +10, matched) -> "it sounds better
     * without tuning". Bigger peaks are not the lever (E-017).
     */
    val LAPTOP = Profile("laptop speakers", highPassHz = 180.0, movedBassHz = 350.0, fullnessDb = 6.5, presenceDb = 6.0)

    /** The phone's values, kept under their old names. */
    val response: Curve = PHONE.response
    const val MOVED_BASS_HZ = 350.0
    const val MOVE_SHARE = 1.0
    const val FULLNESS_DB = 3.5
    const val PRESENCE_DB = 4.0

    /**
     * [c] rewritten for the speaker. [amount] scales the speaker's own lifts (0 = only move the bass the curve asked
     * for, e.g. with automatic tuning off, so the person's own Bass slider is still heard).
     */
    fun adapt(c: Curve, amount: Double, p: Profile = PHONE): Curve {
        val deepIdx = Grid.freqs.indices.filter { Grid.freqs[it] in 40.0..130.0 }
        val deep = deepIdx.sumOf { c.db[it] } / deepIdx.size
        // take out any BOOST below the speaker's range (cuts are kept), fading from all of it at 100 Hz to none at 200 Hz
        val lo = log10(100.0); val hi = log10(200.0)
        val base = Curve(DoubleArray(Grid.size) { i ->
            val w = ((hi - log10(Grid.freqs[i])) / (hi - lo)).coerceIn(0.0, 1.0)
            c.db[i] - w * c.db[i].coerceAtLeast(0.0)
        })
        val a = amount.coerceIn(0.0, 1.5)
        return base + Curve.of(listOf(
            ParametricFilter(HIGH_PASS, 120.0, q = 0.707),                                   // what it cannot play
            ParametricFilter(PEAK, p.movedBassHz, deep.coerceAtLeast(0.0) * MOVE_SHARE + p.fullnessDb * a, 1.0),
            ParametricFilter(PEAK, 3_000.0, p.presenceDb * a, 0.9),
        ))
    }
}
