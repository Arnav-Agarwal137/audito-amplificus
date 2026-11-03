package com.arnavagarwal.sound.core

/**
 * Automatic density (his ask, 24 Sep: *"The app will decide whether density will stay on or not"*).
 *
 * Density = a preamp lift into the clean limiter that sits after Equalizer APO on his laptop speakers. How much a song
 * wants depends on how it was mastered, so the lift is steered by what the limiter is actually doing: the share of
 * 50 ms frames whose peak sits at the ceiling. His liked test (Killie Killie, +6 dB, ~5 dB of limiting on the loudest
 * moments) read [TARGET_SHARE] = 0.20 (tools/windows-spike/ceiling_calib.py). A quiet master reads less at the same lift
 * and gets more; an already-loud master reads 0.12 before any lift and 0.54 at +6, so it gets little.
 *
 * On top of that, each kind of content has its own ceiling on the lift ([cap]): classical keeps its dynamics (none),
 * smooth/acoustic and speech get a lighter touch. Smooth is set from his ear (24 Sep); classical and speech are
 * reasoned starting points, NOT yet ear-tested.
 *
 * The first ~25 s of a song find the amount (0.5 dB steps both ways); after that it holds, and only a limiter far off
 * target moves it (down 0.5, up 0.25), so a chorus does not pull the song's own build-ups flat.
 */
class DensityControl {
    var liftDb = 0.0
        private set

    /** Decisions made in this song; after [SETTLE_STEPS] the lift holds unless the limiter is far off target. */
    private var steps = 0

    /** Sounding seconds of this song, and how many the classifier was sure of (any kind). */
    private var heard = 0
    private var sure = 0

    /** A new song: start from half the cap, so the first seconds are never the loudest guess. */
    fun reset(capDb: Double) { liftDb = (capDb / 2).coerceAtLeast(0.0); steps = 0; heard = 0; sure = 0 }

    /** Once a second while the song is sounding: was the classifier sure what this is? Silence is not a vote. */
    fun vote(kindSure: Boolean) { heard++; if (kindSure) sure++ }

    /** Share of this song the classifier was unsure of; seconds not yet heard (below [MIN_SECONDS]) count as unsure. */
    fun unsureShare(): Double { val n = maxOf(heard, MIN_SECONDS); return (n - sure).toDouble() / n }

    /** Has this song sounded long enough for [unsureShare] to be about the song, not about the unheard rest? */
    fun judged(): Boolean = heard >= MIN_SECONDS

    /** The whole song's cap: the kind's cap times [unsureFactor] of the song so far (his tap 28 Sep). */
    fun songCap(kind: ContentKind?, intensity: Double): Double = cap(kind, intensity) * unsureFactor(unsureShare())

    /**
     * Once a second. [ceilingShare] = share of recent sounding frames at the ceiling, measured WITH the current lift;
     * null when nothing is sounding (the lift then holds still: silence is not data).
     */
    fun update(ceilingShare: Double?, capDb: Double): Double {
        if (ceilingShare != null) {
            // settled: a chorus reading a little hot must not pull the song's own build-ups flat (24 Sep, live)
            // while the song is still showing its kind its cap is still moving: settling counts only once it holds
            val settling = steps < SETTLE_STEPS
            val band = if (settling) BAND else BAND_SETTLED
            if (heard !in 1 until MIN_SECONDS) steps++
            liftDb = when {
                ceilingShare > TARGET_SHARE + band -> liftDb - DOWN_DB
                ceilingShare < TARGET_SHARE - band -> liftDb + if (settling) DOWN_DB else UP_DB
                else -> liftDb
            }
        }
        liftDb = liftDb.coerceIn(0.0, capDb.coerceAtLeast(0.0))
        return liftDb
    }

    companion object {
        const val TARGET_SHARE = 0.20
        const val BAND = 0.05
        /** After settling, only a limiter far off target moves the lift (a much louder or quieter section). */
        const val BAND_SETTLED = 0.12
        /** ~25 s of a song at one decision per ~2.5 s. */
        const val SETTLE_STEPS = 10
        // 24 Sep live on Ghungroo: 1.0 / 0.5 on ~1 s of frames hunted 1.7 dB within seconds (a level wobble)
        const val DOWN_DB = 0.5
        const val UP_DB = 0.25
        /** A 50 ms frame peak above this is "at the ceiling" (the limiter holds the output at about -0.1 dBFS). */
        const val CEILING = 0.97
        /** The most lift, at Normal strength, before the kind's own share ([KIND_SHARE]). His liked test used +6. */
        const val MAX_DB = 6.0

        /** How much of [MAX_DB] each kind may use. Classical: dynamics ARE the music. Unknown kind: the full amount. */
        val KIND_SHARE = mapOf(
            ContentKind.CLASSICAL to 0.0,
            // his ear, 24 Sep: on smooth songs density "was still better" but high density is not; "a little less"
            ContentKind.SMOOTH to 0.67,
            ContentKind.SPEECH to 0.67,
        )

        fun cap(kind: ContentKind?, intensity: Double): Double = MAX_DB * intensity * (KIND_SHARE[kind] ?: 1.0)

        /**
         * Less density on a song the classifier cannot place (his tap 28 Sep, "Lower density when unsure"; his ask
         * 25 Sep: density "is not really good" on voice-led songs like Husn / Anuv Jain). Judged per SONG, not per
         * second: the classifier is unsure most seconds of most things, so a per-second rule would take density off
         * the beat songs he called "wow". Measured unsure shares (app's own code): Anuv Jain 0.90, his beat song 0.41,
         * Killie Killie 0.24; a TV episode 1.00. Jamendo (3739 songs, tags): unsure >= 0.75 in 47% of gentle songs
         * and 12% of beat songs; a learned rule failed held-out, so this is a product rule his blind test judges.
         */
        fun unsureFactor(unsureShare: Double): Double = when {
            unsureShare <= UNSURE_FROM -> 1.0
            unsureShare >= UNSURE_FULL -> UNSURE_FLOOR
            else -> 1.0 - (1.0 - UNSURE_FLOOR) * (unsureShare - UNSURE_FROM) / (UNSURE_FULL - UNSURE_FROM)
        }

        /**
         * A gentler middle on the same unsure songs, while density is on (his words 29 Sep: density + Study music "Voice
         * softer" is good on Husn / Jo Tum Mere Ho, "see why and learn"). Measured (tools/windows-spike/
         * why_voice_softer.py): Voice softer hardly moves voice against music there; it takes back about half the Strong
         * middle boost and density's loudness. His blind test (29 Sep): the same cut on the WHOLE song sounded the same
         * as the centre-only one, so the app learns the tone: up to [SOFT_MIDDLE_DB] off 250 Hz - 5 kHz, on the same ramp
         * as [unsureFactor] (none at [UNSURE_FROM] or below, all of it from [UNSURE_FULL]).
         */
        fun softMiddleDb(unsureShare: Double): Double = SOFT_MIDDLE_DB * when {
            unsureShare <= UNSURE_FROM -> 0.0
            unsureShare >= UNSURE_FULL -> 1.0
            else -> (unsureShare - UNSURE_FROM) / (UNSURE_FULL - UNSURE_FROM)
        }

        /** Study music's "Voice softer" step, the amount he liked with density on (Words.STUDY_STEPS). */
        const val SOFT_MIDDLE_DB = 6.0
        const val UNSURE_FROM = 0.5
        const val UNSURE_FULL = 0.85
        /** Share of the cap left on a song that is unsure throughout: 1.5 dB at Normal ("little", not none). */
        const val UNSURE_FLOOR = 0.25
        /** Until this many sounding seconds, the unheard rest counts as unsure: a song earns density by showing its kind. */
        const val MIN_SECONDS = 20

        /** Share of sounding frames (peak above [SOUNDING]) whose peak is at the ceiling; null when too few sounded. */
        fun ceilingShare(peaks: List<Double>, ceiling: Double = CEILING): Double? {
            val sounding = peaks.filter { it > SOUNDING }
            if (sounding.size < MIN_FRAMES) return null
            return sounding.count { it > ceiling }.toDouble() / sounding.size
        }

        /**
         * The phone (29 Sep, his "All possible"): its limiter sits in our own DynamicsProcessing at -1 dBFS, and on the
         * global route the Visualizer hears the mix BEFORE our effect (E-003), so the limiter cannot be watched; its
         * work is predicted instead: each frame peak raised by the lift not yet in it ([liftNotHeardDb]). The phone's
         * tuning is loudness-matched (Mixer: the preamp never boosts), so the peaks are taken as the tuning leaves them.
         * On the per-app route the Visualizer hears after our chain: pass 0, the peaks already carry the lift.
         */
        fun phoneShare(peaks: List<Double>, liftNotHeardDb: Double): Double? {
            val g = Math.pow(10.0, liftNotHeardDb / 20)
            // "sounding" is judged on the peak AS MEASURED: raised first, the Visualizer's -96 dBFS silence floor
            // crossed [SOUNDING] once the volume was added back (+53 dB at a low volume) and silence steered the lift
            return ceilingShare(peaks.filter { it > SOUNDING }.map { it * g }, PHONE_CEILING)
        }

        /** The phone limiter's threshold is -1 dBFS; a peak within 0.3 dB of it counts as held there. */
        val PHONE_CEILING = Math.pow(10.0, -1.3 / 20)

        const val SOUNDING = 1e-3
        /** 2 s of frames at the helper's 20 per second: 1 s gave a share too noisy to steer by (it hunted). */
        const val MIN_FRAMES = 40
    }
}
