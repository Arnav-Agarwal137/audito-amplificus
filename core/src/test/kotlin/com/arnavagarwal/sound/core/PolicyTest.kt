package com.arnavagarwal.sound.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Session routing, level calibration, knob learning and alert rate-limiting. */
class PolicyTest {
    // ---- which chain is live ----

    @Test fun spotifyAnnouncedAndPlayingMeansNoGlobal() =
        assertFalse(SessionPolicy.useGlobal(listOf("com.spotify.music"), "com.spotify.music"))

    @Test fun youtubePlayingWhileSpotifyAnnouncedMeansGlobal() =
        assertTrue(SessionPolicy.useGlobal(listOf("com.spotify.music"), "com.google.android.youtube"))

    @Test fun nothingAnnouncedMeansGlobal() = assertTrue(SessionPolicy.useGlobal(emptyList(), null))

    @Test fun blindWithAnnouncementAvoidsDoubleProcessing() =
        assertFalse(SessionPolicy.useGlobal(listOf("com.spotify.music"), null))

    @Test fun anonymousAnnouncementDoesNotCoverAKnownPlayer() =
        assertTrue(SessionPolicy.useGlobal(listOf(null), "com.google.android.youtube"))

    // ---- level calibration ----

    @Test fun levelThatFollowsVolumeVotesIncludes() {
        val c = LevelCalibrator()
        repeat(3) { assertTrue(c.observe(-20.0, -28.0, -10.0, -18.0)) }
        assertEquals(true, c.verdict)
    }

    @Test fun levelThatIgnoresVolumeVotesExcludes() {
        val c = LevelCalibrator()
        repeat(3) { c.observe(-20.0, -20.5, -10.0, -18.0) }
        assertEquals(false, c.verdict)
    }

    @Test fun oneObservationIsNotAVerdict() {
        val c = LevelCalibrator()
        c.observe(-20.0, -28.0, -10.0, -18.0)
        assertNull(c.verdict)
    }

    @Test fun smallVolumeStepIsNoEvidence() = assertFalse(LevelCalibrator().observe(-20.0, -22.0, -10.0, -12.0))

    @Test fun silenceIsNoEvidence() = assertFalse(LevelCalibrator().observe(-90.0, -90.0, -10.0, -20.0))

    @Test fun musicChangingOnItsOwnIsNoEvidence() =
        // volume -8 dB but level +6 dB (the chorus hit): ratio -0.75, neither vote
        assertFalse(LevelCalibrator().observe(-26.0, -20.0, -10.0, -18.0))

    @Test fun splitEvidenceStaysUndecided() {
        val c = LevelCalibrator()
        repeat(3) { c.observe(-20.0, -28.0, -10.0, -18.0) }
        repeat(3) { c.observe(-20.0, -20.0, -10.0, -18.0) }
        assertNull(c.verdict)
    }

    // ---- knobs ----

    @Test fun briefTweakTeachesNothing() = assertFalse(KnobLearning.worthLearning(Tone(bass = 4.0), 5.0))
    @Test fun heldTweakTeaches() = assertTrue(KnobLearning.worthLearning(Tone(bass = 4.0), 60.0))
    @Test fun tinyTweakTeachesNothing() = assertFalse(KnobLearning.worthLearning(Tone(bass = 0.5), 600.0))

    // ---- alerts ----

    @Test fun alertNeedsSustainedLoudnessAndThenWaits() {
        val a = AlertLimiter(holdMs = 30_000, gapMs = 600_000)
        assertFalse(a.update(0, true))
        assertFalse(a.update(29_000, true))
        assertTrue(a.update(30_000, true))
        assertFalse(a.update(100_000, true))          // inside the gap
        assertFalse(a.update(200_000, false))         // quiet resets the hold
        assertFalse(a.update(700_000, true))          // loud again, hold restarts
        assertTrue(a.update(730_000, true))
    }
}

class RestPolicyTest {
    @Test fun restsOnlyAfterTenQuietSecondsAndWakesAtOnce() {
        val r = RestPolicy()
        assertFalse(r.update(0, false))               // just started: not resting yet
        assertFalse(r.update(9_999, false))
        assertTrue(r.update(10_000, false))           // ten quiet seconds
        assertFalse(r.update(10_500, true))           // sound: awake immediately
        assertFalse(r.update(20_499, false))
        assertTrue(r.update(20_500, false))
    }
}
