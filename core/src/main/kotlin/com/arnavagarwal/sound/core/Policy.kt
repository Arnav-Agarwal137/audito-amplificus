package com.arnavagarwal.sound.core

import kotlin.math.abs

/**
 * Which effect chains should be live. A music app that announces its audio session gets its own chain
 * (route A); the global chain on session 0 (route B) catches everything else. Both at once on the same
 * sound would apply the curve TWICE, so the global chain steps aside whenever the thing playing is
 * already covered by an announced session.
 */
object SessionPolicy {
    /**
     * [announcedPackages]: packages that currently have an announced session open (null = the app did
     * not say who it was). [nowPlayingPackage]: who is playing, from the media session; null when we
     * lack notification access or nothing is playing.
     */
    fun useGlobal(announcedPackages: Collection<String?>, nowPlayingPackage: String?): Boolean =
        if (nowPlayingPackage != null) nowPlayingPackage !in announcedPackages
        // we cannot see who is playing: if any app has announced, assume it is the one (double
        // processing is audible; a missed YouTube is merely unprocessed)
        else announcedPackages.isEmpty()
}

/**
 * Does the Visualizer's level already include the phone's volume setting? It differs between phones,
 * and the hearing-dose estimate depends on it (counting the volume twice under-reads by up to 60 dB).
 *
 * Method: when the person moves the volume by a few steps, compare the level just before with just
 * after. If the level moved with the volume, the Visualizer sees the volume; if it stayed put, it does
 * not. Music is loud and quiet on its own, so one observation proves nothing: we need [needVotes]
 * agreeing more than disagreeing before changing our mind. Until then the caller keeps its safe default.
 */
class LevelCalibrator(private val needVotes: Int = 3, private val minStepDb: Double = 4.0) {
    var votesIncludes = 0; private set
    var votesExcludes = 0; private set

    /** Returns true when this pair of readings counted as a vote. */
    fun observe(levelBeforeDb: Double, levelAfterDb: Double, volumeDbBefore: Double, volumeDbAfter: Double): Boolean {
        val dv = volumeDbAfter - volumeDbBefore
        if (abs(dv) < minStepDb) return false
        if (levelBeforeDb < -70 || levelAfterDb < -70) return false // silence or a pause: nothing to compare
        val ratio = (levelAfterDb - levelBeforeDb) / dv
        return when {
            ratio in 0.5..1.5 -> { votesIncludes++; true }
            abs(ratio) < 0.25 -> { votesExcludes++; true }
            else -> false // the music itself changed more than the volume did: no information
        }
    }

    /** null = not enough evidence yet. */
    val verdict: Boolean?
        get() = when {
            votesIncludes >= needVotes && votesIncludes >= votesExcludes + 2 -> true
            votesExcludes >= needVotes && votesExcludes >= votesIncludes + 2 -> false
            else -> null
        }
}

/**
 * The knobs are "adjust it now". When the song changes, whatever the person had dialled in is taught to
 * the [TasteLearner] as a general preference for that kind of sound, and the knobs return to zero - the
 * learner now carries it, so nothing is applied twice. Tiny or brief adjustments teach nothing.
 */
object KnobLearning {
    const val MIN_SECONDS = 20.0
    const val MIN_DB = 1.0

    fun worthLearning(knobs: Tone, secondsHeld: Double): Boolean =
        secondsHeld >= MIN_SECONDS && knobs.toArray().any { abs(it) >= MIN_DB }
}

/** "Too loud" pop-ups: at most one per [gapMs], and only after it has stayed too loud for [holdMs]. */
class AlertLimiter(private val holdMs: Long = 30_000, private val gapMs: Long = 20 * 60_000) {
    private var loudSince: Long? = null
    private var lastAlert: Long = Long.MIN_VALUE / 2

    fun update(nowMs: Long, tooLoud: Boolean): Boolean {
        if (!tooLoud) { loudSince = null; return false }
        val since = loudSince ?: nowMs.also { loudSince = it }
        if (nowMs - since < holdMs || nowMs - lastAlert < gapMs) return false
        lastAlert = nowMs
        return true
    }
}

/**
 * Battery (his ask, 22 Sep: "i hope that it is not drain a lot of battery"). While nothing has played for
 * [quietMs], the engine rests: it checks once a second instead of 20 times, and the sound meter is switched off.
 * The tuning itself stays in place, so a song that starts again is tuned from its first moment; only the
 * measuring restarts. Any sound, a challenge or a coverage check wakes it at once.
 */
class RestPolicy(private val quietMs: Long = 10_000) {
    private var lastSoundMs: Long? = null

    /** True = rest now. */
    fun update(nowMs: Long, soundActive: Boolean): Boolean {
        if (soundActive || lastSoundMs == null) lastSoundMs = nowMs
        return nowMs - lastSoundMs!! >= quietMs
    }
}
