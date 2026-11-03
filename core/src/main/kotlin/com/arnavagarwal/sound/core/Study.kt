package com.arnavagarwal.sound.core

/**
 * Study music on the phone (his ask 28 Sep: *"lower ... mainly the voice of lyrics itself and the bgm should remain"*;
 * phone catch-up 29 Sep, his tap "All possible"; his rule 29 Sep 19:59 "make sure phone app and laptop are same to same").
 *
 * The laptop cuts the voice band (250 Hz - 5 kHz, two shelves) on the MIDDLE of the stereo picture only, where a sung
 * lead voice sits (desktop LaptopSetting.voiceDownText). Android's DynamicsProcessing works channel by channel and
 * cannot mix left and right, so the phone must cut the WHOLE song. His blind test on 29 Sep (Husn, RULES.md) could not
 * tell the whole-song cut from the middle-only one at [SOFTER_DB]. The deeper steps are offered too, as on the laptop
 * ("same to same"), and the phone's card says plainly that instruments in the band go down with the voice.
 *
 * Soft must never mean quieter (his tap 29 Sep "Soft + lift it back up"): [Mixer.plan] gives the lost loudness back,
 * priced on the curve before and after the cut (so a clamp that shrinks the cut shrinks the lift with it).
 */
object StudyMusic {
    const val LOW_HZ = 250.0
    const val HIGH_HZ = 5000.0
    /** The laptop's "Voice softer" step. */
    const val SOFTER_DB = 6.0

    /** The laptop's steps, labels and amounts (desktop Words.STUDY_STEPS; a desktop test holds the two equal). */
    val STEPS = listOf("Off" to 0.0, "Voice softer" to SOFTER_DB, "Voice much softer" to 12.0, "Voice almost gone" to 24.0)

    /** The same two shelves as the laptop's (desktop LaptopSetting.voiceDownCurve): down at [LOW_HZ], back up at [HIGH_HZ]. */
    fun curve(db: Double): Curve = if (db <= 0.05) Curve.FLAT else Curve.of(
        ParametricFilter(FilterType.HIGH_SHELF, LOW_HZ, -db, 0.707),
        ParametricFilter(FilterType.HIGH_SHELF, HIGH_HZ, db, 0.707))
}
