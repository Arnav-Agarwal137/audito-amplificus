package com.arnavagarwal.sound.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recommended volume (no mic), the low-volume fix and auto width - 25 Sep, his plan "All three, in this order". */
class LevelTest {
    // ---------------------------------------------------------------- VolumeCoach

    @Test fun learnsBeforeAdvising() {
        val c = VolumeCoach()
        c.learn(-30.0); c.learn(-28.0)
        assertNull(c.advise(-20.0, -10.0, null, -65.25, 0.0), "two songs are not enough to know his level")
        c.learn(-29.0)
        assertNotNull(c.advise(-20.0, -10.0, null, -65.25, 0.0))
    }

    @Test fun aQuieterMasterAsksForMoreSlider() {
        val c = VolumeCoach(listOf(-30.0, -30.0, -30.0, -24.0))  // usual -30, loudest he chose -24
        // a song at -20 dBFS: usual needs slider -10. He is at -16 -> up 6.
        val quietSong = c.advise(-20.0, -16.0, null, -65.25, 0.0)!!
        assertEquals(VolumeCoach.Verdict.UP, quietSong.verdict)
        assertEquals(-10.0, quietSong.wantSliderDb, 1e-9)
        assertEquals(6.0, quietSong.changeDb, 1e-9)
        // a hot master at -12 dBFS with the same slider -> too loud for him, down
        val hot = c.advise(-12.0, -10.0, null, -65.25, 0.0)!!
        assertEquals(VolumeCoach.Verdict.DOWN, hot.verdict)
        assertEquals(-18.0, hot.wantSliderDb, 1e-9)
        // within 2 dB = leave it
        assertEquals(VolumeCoach.Verdict.GOOD, c.advise(-20.0, -11.0, null, -65.25, 0.0)!!.verdict)
    }

    @Test fun neverAdvisesLouderThanHeHasEverChosen() {
        val c = VolumeCoach(listOf(-30.0, -30.0, -30.0))  // loudest = usual = -30
        // bass-heavy wants +3 over the average, but he has never gone past -30: capped there
        val a = c.advise(-20.0, -10.0, ContentKind.BASS_HEAVY, -65.25, 0.0)!!
        assertEquals(-10.0, a.wantSliderDb, 1e-9)
        assertEquals(VolumeCoach.Verdict.GOOD, a.verdict)
        // the cap is the only thing holding it: with a louder evening on record, bass-heavy goes above usual
        val c2 = VolumeCoach(listOf(-30.0, -30.0, -30.0, -20.0))
        assertTrue(c2.advise(-20.0, -10.0, ContentKind.BASS_HEAVY, -65.25, 0.0)!!.wantSliderDb > -10.0)
    }

    @Test fun talkIsAdvisedQuieterThanMusic() {
        val c = VolumeCoach(listOf(-30.0, -30.0, -30.0, -10.0))
        val talk = c.advise(-20.0, -10.0, ContentKind.SPEECH, -65.25, 0.0)!!.wantSliderDb
        val bass = c.advise(-20.0, -10.0, ContentKind.BASS_HEAVY, -65.25, 0.0)!!.wantSliderDb
        assertEquals(9.0, bass - talk, 1e-9) // 74 - 65 dBA, the phone's targets
    }

    @Test fun staysInsideTheSlidersRange() {
        val c = VolumeCoach(listOf(-10.0, -10.0, -10.0))
        assertEquals(0.0, c.advise(-40.0, -5.0, null, -65.25, 0.0)!!.wantSliderDb, 1e-9)
    }

    // ---------------------------------------------------------------- ListenLevel

    private fun frames(db: Double, n: Int = 20) = List(n) { db }

    @Test fun takesOneSettledLevelPerSong() {
        val l = ListenLevel()
        var got: Double? = null
        for (s in 0..40) got = l.update(s * 1000L, "song", -10.0, false, frames(-20.0)) ?: got
        assertEquals(-30.0, got!!, 1e-9)
        // not again for the same song
        assertNull(l.update(41_000, "song", -10.0, false, frames(-20.0)))
    }

    @Test fun aSliderStillMovingIsNotAChoice() {
        val l = ListenLevel()
        for (s in 0..40) assertNull(l.update(s * 1000L, "song", -10.0 - (s % 2), false, frames(-20.0)))
    }

    @Test fun mutedAndSilenceTeachNothing() {
        val l = ListenLevel()
        for (s in 0..40) assertNull(l.update(s * 1000L, "song", -10.0, true, frames(-20.0)))
        val q = ListenLevel()
        for (s in 0..40) assertNull(q.update(s * 1000L, "song", -10.0, false, frames(-80.0)))
    }

    // 25 Sep: a silent self-test at the slider's floor (-65 dB) was learned as -83.45, "one of his songs".
    @Test fun aSliderAtTheFloorIsNotListening() {
        val l = ListenLevel()
        for (s in 0..40) assertNull(l.update(s * 1000L, "song", -65.25, false, frames(-18.0)))
        // just above the line still counts
        val q = ListenLevel()
        var got: Double? = null
        for (s in 0..40) got = q.update(s * 1000L, "song", -41.0, false, frames(-18.0)) ?: got
        assertEquals(-59.0, got!!, 1e-9)
    }

    @Test fun pausesDoNotPullTheLevelDown() {
        val l = ListenLevel()
        var got: Double? = null
        for (s in 0..40) got = l.update(s * 1000L, "song", 0.0, false, frames(-20.0, 10) + frames(-90.0, 10)) ?: got
        assertEquals(-20.0, got!!, 1e-9)
    }

    // ---------------------------------------------------------------- QuietFix

    @Test fun nothingAtHisUsualLevel() {
        assertTrue(QuietFix.curve(0.0).db.all { it == 0.0 })
        assertTrue(QuietFix.curve(1.5).db.all { it == 0.0 })
    }

    @Test fun quieterListeningGetsLowMidsBackNotDeepBass() {
        val c = QuietFix.curve(20.0)
        fun at(hz: Double) = c.db[Grid.freqs.indices.minBy { kotlin.math.abs(Grid.freqs[it] - hz) }]
        assertTrue(at(200.0) > 1.0, "200 Hz lifted: ${at(200.0)}")
        assertTrue(at(200.0) > at(1000.0) + 1.0, "lift is in the bass, not flat")
        assertEquals(0.0, at(80.0), 1e-9, "below what laptop speakers can play: nothing")
        assertTrue(c.db.all { it in 0.0..QuietFix.MAX_DB })
        // quieter still = more
        assertTrue(QuietFix.curve(30.0).db.sum() > c.db.sum())
    }

    // ---------------------------------------------------------------- AutoWidth

    @Test fun widensOnlySongsThatHaveStereo() {
        assertEquals(1.0, AutoWidth.width(-30.0, ContentKind.VOCAL_POP), "mono: nothing to widen")
        assertEquals(1.0, AutoWidth.width(-2.0, ContentKind.VOCAL_POP), "already wide")
        assertEquals(1.0, AutoWidth.width(null, ContentKind.VOCAL_POP))
        val w = AutoWidth.width(-12.0, ContentKind.VOCAL_POP)
        assertEquals(10.0.pow(4.0 / 20), w, 1e-9)
        assertTrue(AutoWidth.width(-6.0, ContentKind.VOCAL_POP) < w, "near-wide songs get less, up to the -4 dB line")
        assertTrue(AutoWidth.width(-12.0, ContentKind.VOCAL_POP) <= AutoWidth.MAX_WIDTH)
    }

    @Test fun talkAndClassicalAreLeftAlone() {
        assertEquals(1.0, AutoWidth.width(-12.0, ContentKind.SPEECH))
        assertEquals(1.0, AutoWidth.width(-12.0, ContentKind.CLASSICAL))
    }

    @Test fun loudnessOfWideningIsKnown() {
        assertEquals(0.0, AutoWidth.loudnessDb(-12.0, 1.0), 1e-12)
        // side at -6 dB widened 1.6x: (1 + 2.56*0.251) / 1.251 -> +1.2 dB
        assertEquals(1.19, AutoWidth.loudnessDb(-6.0, 1.6), 0.02)
    }

    @Test fun widthIsDecidedOnceFromTheUnwidenedStartOfTheSong() {
        val c = WidthControl()
        var w = 1.0
        repeat(7) { w = c.update("a", List(20) { -12.0 }, 50, ContentKind.VOCAL_POP, 1.0) }
        assertEquals(1.0, w, "still listening")
        w = c.update("a", List(20) { -12.0 }, 50, ContentKind.VOCAL_POP, 1.0)
        assertTrue(w > 1.0)
        // what the helper hears afterwards includes our widening: it must not move the decision
        repeat(30) { assertEquals(w, c.update("a", List(20) { -2.0 }, 50, ContentKind.VOCAL_POP, 1.0)) }
        // new song starts unwidened again
        assertEquals(1.0, c.update("b", List(20) { -12.0 }, 50, ContentKind.VOCAL_POP, 1.0))
    }

    /** E-029's class on the volume side: silence stays silence when the volume is taken back out. */
    @Test fun phoneLevelsDropSilenceBeforeTakingTheVolumeOut() {
        assertEquals(listOf(-18.0), VolumeCoach.beforeVolume(listOf(-96.0, -71.0), -53.0, includesVolume = true))
        assertEquals(listOf(-71.0), VolumeCoach.beforeVolume(listOf(-96.0, -71.0), -53.0, includesVolume = false))
        // fed to ListenLevel, a silent song at a low volume is never learned as a listening level
        val l = ListenLevel()
        val learned = (0..40).mapNotNull { s -> l.update(s * 1000L, "quiet", -53.0, false, VolumeCoach.beforeVolume(List(20) { -96.0 }, -53.0, true)) }
        assertTrue(learned.isEmpty(), "learned $learned from silence")
    }

    @Test fun setItForMePicksTheNearestStepAndNeverMute() {
        val steps = doubleArrayOf(-96.0, -40.0, -30.0, -20.0, -10.0, 0.0)
        assertEquals(3, VolumeCoach.nearestStep(steps, -21.0))
        assertEquals(1, VolumeCoach.nearestStep(steps, -90.0))
        assertEquals(5, VolumeCoach.nearestStep(steps, 4.0))
    }

    /** The emulator ping-pong (29 Sep): steps wider than GOOD_DB, want between two of them. */
    @Test fun phoneStepsDoNotPingPong() {
        val c = VolumeCoach(listOf(-30.0, -30.0, -30.0))       // usual -30
        val steps = doubleArrayOf(-96.0, -20.0, -15.0, -10.0, -5.0, 0.0)
        val song = -17.7                                           // want -12.3: 2.7 from -15, 2.3 from -10
        // the laptop rule alone flips: up from -15, then down from -10
        assertEquals(VolumeCoach.Verdict.UP, c.advise(song, -15.0, null, -20.0, 0.0)!!.verdict)
        assertEquals(VolumeCoach.Verdict.DOWN, c.advise(song, -10.0, null, -20.0, 0.0)!!.verdict)
        // the step rule: a want this near the midpoint is good on EITHER neighbour, and stays so as the level drifts
        for (drift in listOf(-0.4, 0.0, 0.4)) for (at in listOf(2, 3))
            assertEquals(VolumeCoach.Verdict.GOOD, c.adviseSteps(song + drift, steps, at, null, 0.0)!!.verdict, "step $at drift $drift")
        // a want clearly nearer another step: up to it, then good there
        val song2 = -19.0                                          // want -11: 4 from -15, 1 from -10
        val up = c.adviseSteps(song2, steps, 2, null, 0.0)!!
        assertEquals(VolumeCoach.Verdict.UP, up.verdict)
        assertEquals(-10.0, up.wantSliderDb, 1e-9)
        assertEquals(5.0, up.changeDb, 1e-9)
        assertEquals(VolumeCoach.Verdict.GOOD, c.adviseSteps(song2, steps, 3, null, 0.0)!!.verdict)
        assertEquals(VolumeCoach.Verdict.DOWN, c.adviseSteps(song2, steps, 5, null, 0.0)!!.verdict)
        // following "Set it for me" from every start settles on one step and never moves again
        for (start in 1 until steps.size) {
            var at = start
            repeat(5) { c.adviseSteps(song2, steps, at, null, 0.0)!!.let { a -> at = VolumeCoach.nearestStep(steps, a.wantSliderDb) } }
            assertEquals(3, at, "from step $start")
            assertEquals(VolumeCoach.Verdict.GOOD, c.adviseSteps(song2, steps, at, null, 0.0)!!.verdict)
        }
        // the earbud safe cap: never a step above it, even when a louder step is nearer...
        assertEquals(-15.0, c.adviseSteps(song2, steps, 1, null, -12.0)!!.wantSliderDb, 1e-9)
        // ...and a step above the cap is never "good", however near the want (safety beats the dead band)
        assertEquals(VolumeCoach.Verdict.DOWN, c.adviseSteps(song, steps, 3, null, -12.0)!!.verdict)
        assertNull(VolumeCoach().adviseSteps(song, steps, 2, null, 0.0), "still learning")
    }

    /** The emulator log (29 Sep): the sure/unsure kind flipped each second and moved the want 3 dB with it. */
    @Test fun theCoachsKindIsHeldPerSong() {
        val v = KindVote()
        // flickering sure/unsure keeps the first answer
        val seen = (0 until 40).map { v.update("a", if (it % 2 == 0) ContentKind.BASS_HEAVY else null) }
        assertTrue(seen.all { it == ContentKind.BASS_HEAVY }, "flipped: $seen")
        // a kind that clearly wins takes over, once
        val later = (0 until 30).map { v.update("a", ContentKind.SPEECH) }
        assertEquals(ContentKind.SPEECH, later.last())
        assertEquals(1, later.zipWithNext().count { (x, y) -> x != y }, "changed more than once: $later")
        // a new song starts over
        assertNull(v.update("b", null))
        assertEquals(null, v.update("b", ContentKind.SMOOTH), "one vote does not move a held kind")
        // and the coach's advice with a held kind does not flip with the flicker
        val c = VolumeCoach(listOf(-30.0, -30.0, -30.0))
        val steps = doubleArrayOf(-96.0, -20.0, -15.0, -10.0, -5.0, 0.0)
        fun flicker(i: Int) = if (i % 2 == 0) null else ContentKind.SPEECH   // unknown: want -15; speech: about -21
        val raw = (0 until 30).map { c.adviseSteps(-15.0, steps, 2, flicker(it), 0.0)!!.verdict }
        assertTrue(raw.toSet().size > 1, "control: the unheld flicker should flip the advice, got $raw")
        val w = KindVote()
        val held = (0 until 30).map { c.adviseSteps(-15.0, steps, 2, w.update("s", flicker(it)), 0.0)!!.verdict }
        assertEquals(1, held.toSet().size, "advice flipped: $held")
    }

    private fun Double.pow(x: Double) = Math.pow(this, x)
}
