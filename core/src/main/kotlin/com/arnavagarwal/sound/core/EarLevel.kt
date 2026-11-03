package com.arnavagarwal.sound.core

/**
 * The tuned side's level, set by HIS ear. His laptop test, 23 Sep: the loudness-matched tuned side still "feels as if
 * the volume increased". No loudness model settles that (an ear-weighted Zwicker-style match was tried and asked for a
 * SMALLER cut than K-weighting, ERRORS E-014), so after each A/B pass he says which side was louder and this moves
 * the tuned side's level. A staircase: 1 dB steps, halved each time the answer flips direction (so it closes in on
 * "same" instead of swinging round it), never finer than [MIN_STEP].
 */
class EarLevel(offsetDb: Double = 0.0) {
    enum class Heard { TUNED_LOUDER, SAME, TUNED_QUIETER }

    /** Added to the tuned side's preamp. */
    var offsetDb = offsetDb.coerceIn(MIN, MAX); private set
    var step = 1.0; private set
    private var lastDir = 0

    fun answer(h: Heard) {
        val dir = when (h) { Heard.TUNED_LOUDER -> -1; Heard.TUNED_QUIETER -> 1; Heard.SAME -> return }
        if (lastDir != 0 && dir != lastDir) step = (step / 2).coerceAtLeast(MIN_STEP)
        lastDir = dir
        offsetDb = (offsetDb + dir * step).coerceIn(MIN, MAX)
    }

    companion object {
        const val MIN = -8.0
        const val MAX = 3.0
        const val MIN_STEP = 0.5
    }
}
