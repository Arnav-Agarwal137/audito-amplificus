package com.arnavagarwal.sound.core

import com.arnavagarwal.sound.core.FilterType.PEAK

/**
 * Strong mode for the phone (his tap 9 Oct 2026, "Give phone a strong mode").
 *
 * Why it exists: on the laptop he hears the on/off difference as "wow"; on his phone it is small. The laptop's speakers
 * get the fixed setting he picked by ear (desktop LaptopSetting: +8 dB at 350 Hz Q1, +6 dB at 3 kHz Q0.9, high-pass
 * 120 Hz) and are NOT loudness-matched (23 Sep: "take the same setting and apply"). The phone's automatic curve is
 * loudness-matched by design (Mixer: a tone change must not be a volume change), so it never sounds louder when on.
 *
 * Strong mode is the phone's opt-in version of the laptop setting, OFF by default (today's behaviour is kept):
 *  - the laptop's two peaks, scaled by the step ([LIGHT] 0.6, [BOLD] 1.0 = exactly the laptop's liked numbers,
 *    [MAX] 1.4 = the laptop app's own "Strong" strength), added on top of the automatic tuning;
 *  - on the phone's speaker they REPLACE the speaker's own gentler lifts (Speaker.adapt amount 0), so the speaker hears
 *    the laptop's shape, and the high-pass at 120 Hz comes from Speaker.adapt, which runs ONLY on the speaker: earbuds
 *    and headphones never get a high-pass;
 *  - on the phone's speaker the song is allowed to get louder by [louderDb] (not loudness-matched: that is the point),
 *    with the -1 dBFS limiter (EffectChain) catching the peaks and density still on top;
 *  - on earbuds / headphones the same rise applies (his tap 9 Oct 2026 "Louder in earbuds too", superseding the
 *    night's matched-in-the-ears choice after being told the hearing trade); still no high-pass there, and the earbud
 *    safe cap and dose ledger (Level) keep working on top.
 * The blind challenge stays loudness-matched (Brain.challengePlay matches on the curve alone; density is off during it).
 * Claude cannot hear: these are the laptop's numbers carried over; his ear decides which step, if any.
 */
object StrongMode {
    const val LIGHT = 0
    const val BOLD = 1
    const val MAX = 2
    val steps = listOf(LIGHT, BOLD, MAX)

    /** Scale on the laptop's peaks per step. */
    fun scale(step: Int): Double = when (step.coerceIn(LIGHT, MAX)) { LIGHT -> 0.6; BOLD -> 1.0; else -> 1.4 }

    /** How much louder than Off the song is allowed to get, speaker and earbuds alike (dB). */
    fun louderDb(step: Int): Double = when (step.coerceIn(LIGHT, MAX)) { LIGHT -> 1.5; BOLD -> 3.0; else -> 4.5 }

    /** The laptop's body peak (desktop LaptopSetting.FILTERS), kept here because core cannot read the desktop module. */
    const val BODY_HZ = 350.0
    const val BODY_DB = 8.0
    const val BODY_Q = 1.0
    const val PRESENCE_HZ = 3_000.0
    const val PRESENCE_DB = 6.0
    const val PRESENCE_Q = 0.9

    /** The fixed character for [step]: peaks only. The high-pass is the speaker's (Speaker.adapt), never added here. */
    fun curve(step: Int): Curve {
        val k = scale(step)
        return Curve.of(
            ParametricFilter(PEAK, BODY_HZ, BODY_DB * k, BODY_Q),
            ParametricFilter(PEAK, PRESENCE_HZ, PRESENCE_DB * k, PRESENCE_Q),
        )
    }
}
