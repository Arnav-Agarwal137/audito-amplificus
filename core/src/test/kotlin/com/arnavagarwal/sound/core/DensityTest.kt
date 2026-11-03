package com.arnavagarwal.sound.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Automatic density. The "songs" are the measured curves from tools/windows-spike/ceiling_calib.log (24 Sep):
 * share of 50 ms frames at the ceiling for a lift of 0..9 dB into the measured limiter.
 */
class DensityTest {
    private val killie = doubleArrayOf(0.00, 0.02, 0.05, 0.08, 0.13, 0.18, 0.20, 0.20, 0.21, 0.23) // his clip
    private val quiet = doubleArrayOf(0.00, 0.00, 0.00, 0.00, 0.02, 0.06, 0.12, 0.19, 0.26, 0.35)  // demo song
    private val hot = doubleArrayOf(0.12, 0.22, 0.30, 0.40, 0.49, 0.53, 0.54, 0.54, 0.55, 0.55)    // loud master

    private fun share(song: DoubleArray, lift: Double): Double {
        val i = lift.toInt().coerceIn(0, 8); val f = lift - i
        return song[i] * (1 - f) + song[i + 1] * f
    }

    /** Runs the control for [seconds] on a song and returns where the lift settled. */
    private fun settle(song: DoubleArray, cap: Double, seconds: Int = 60): Double {
        val c = DensityControl(); c.reset(cap)
        repeat(seconds) { c.update(share(song, c.liftDb), cap) }
        return c.liftDb
    }

    @Test fun hisClipSettlesNearTheLikedLift() {
        val lift = settle(killie, 6.0)
        assertTrue(lift in 4.5..6.0, "Killie Killie settled at $lift, liked test was +6")
    }

    @Test fun aLoudMasterGetsLittleAndAQuietOneGetsTheCap() {
        assertTrue(settle(hot, 6.0) <= 1.5, "loud master must get little: ${settle(hot, 6.0)}")
        assertEquals(6.0, settle(quiet, 6.0))
    }

    @Test fun kindCapsTheLift() {
        assertEquals(0.0, settle(quiet, DensityControl.cap(ContentKind.CLASSICAL, 1.0)))
        val smooth = DensityControl.cap(ContentKind.SMOOTH, 1.0)
        assertTrue(smooth in 3.5..4.5, "smooth: a little less than the full 6 (his ear), got $smooth")
        assertEquals(6.0, DensityControl.cap(ContentKind.BASS_HEAVY, 1.0))
        assertEquals(6.0, DensityControl.cap(null, 1.0))              // unsure of the kind: no cap from it
        assertEquals(3.0, DensityControl.cap(null, 0.5))              // Gentle halves it
    }

    @Test fun silenceHoldsTheLiftAndTooFewFramesAreNotData() {
        val c = DensityControl(); c.reset(6.0)
        val start = c.liftDb
        repeat(10) { c.update(null, 6.0) }
        assertEquals(start, c.liftDb)
        assertNull(DensityControl.ceilingShare(List(30) { 0.99 }))           // 1.5 s of frames: too noisy to steer by
        assertNull(DensityControl.ceilingShare(List(60) { 0.0 }))            // silence
        assertEquals(0.25, DensityControl.ceilingShare(List(30) { 0.5 } + List(10) { 0.99 }))
    }

    @Test fun onceSettledASlightlyHotChorusDoesNotMoveIt() {
        val c = DensityControl(); c.reset(6.0)
        repeat(DensityControl.SETTLE_STEPS) { c.update(0.20, 6.0) }
        val settled = c.liftDb
        c.update(0.28, 6.0); assertEquals(settled, c.liftDb)          // a little hot: holds
        c.update(0.45, 6.0); assertEquals(settled - 0.5, c.liftDb)    // far off: still steers
        c.reset(6.0); c.update(0.28, 6.0); assertEquals(2.5, c.liftDb) // a new song steers finely again
    }

    /** The engine's loop: one vote a second ([sureEvery] = sure on these seconds), the song's cap, one update. */
    private fun play(song: DoubleArray, sure: (Int) -> Boolean, seconds: Int = 60): Double {
        val c = DensityControl(); c.reset(DensityControl.cap(null, 1.0) * DensityControl.UNSURE_FLOOR)
        repeat(seconds) { t -> c.vote(sure(t)); c.update(share(song, c.liftDb), c.songCap(null, 1.0)) }
        return c.liftDb
    }

    @Test fun unsureFactorShape() {
        assertEquals(1.0, DensityControl.unsureFactor(0.24))                 // Killie Killie
        assertEquals(1.0, DensityControl.unsureFactor(0.41))                 // his beat song
        assertEquals(0.25, DensityControl.unsureFactor(0.90))                // Anuv Jain
        assertEquals(0.25, DensityControl.unsureFactor(1.0))                 // a TV episode
        val mid = DensityControl.unsureFactor(0.675)
        assertTrue(mid > 0.6 && mid < 0.65, "halfway between 1.0 and 0.25, got $mid")
    }

    @Test fun unheardSecondsCountAsUnsure() {
        val c = DensityControl(); c.reset(6.0)
        assertEquals(1.0, c.unsureShare())                                   // nothing heard yet
        repeat(10) { c.vote(true) }
        assertEquals(0.5, c.unsureShare())                                   // 10 sure of 20
        repeat(30) { c.vote(true) }
        assertEquals(0.0, c.unsureShare())
        c.reset(6.0); assertEquals(1.0, c.unsureShare())                     // a new song starts over
    }

    @Test fun aSureBeatSongStillReachesItsLikedLiftAndAnUnsureOneStaysLight() {
        // Killie Killie: sure ~76% of seconds -> must settle where it did before this rule (4.5, limiter on target)
        val before = settle(killie, 6.0)
        val now = play(killie, { it % 4 != 0 })
        assertTrue(kotlin.math.abs(now - before) <= 0.25, "Killie Killie settled at $now, before the rule $before")
        // Anuv Jain: sure 10% -> the floor, 1.5 dB at most, even on a quiet master that wants the whole cap
        val anuv = play(quiet, { it % 10 == 0 })
        assertTrue(anuv <= 1.5, "unsure song got $anuv")
        // the same quiet master, sure throughout, still gets the full cap: the cut is the unsure rule, not the song
        assertEquals(6.0, play(quiet, { true }))
    }

    @Test fun gentlerMiddleOnlyOnTheUnsureSongsHeLikedItOn() {
        // measured unsure shares (app's own code): Husn 0.909, Jo Tum Mere Ho 0.818, his beat song 0.41, Killie 0.24
        assertEquals(6.0, DensityControl.softMiddleDb(0.909))
        assertEquals(6.0 * (0.818 - 0.5) / 0.35, DensityControl.softMiddleDb(0.818), 1e-9)
        assertEquals(0.0, DensityControl.softMiddleDb(0.41))
        assertEquals(0.0, DensityControl.softMiddleDb(0.24))
        assertEquals(0.0, DensityControl.softMiddleDb(0.5))                 // the ramp starts where density's does
        assertEquals(6.0, DensityControl.softMiddleDb(0.85))
        assertEquals(3.0, DensityControl.softMiddleDb(0.675), 1e-9)
        // the start of every song counts as unsure; judged() keeps a beat song's first seconds from being dulled
        val c = DensityControl(); c.reset(6.0)
        repeat(DensityControl.MIN_SECONDS - 1) { c.vote(true) }; assertFalse(c.judged())
        c.vote(true); assertTrue(c.judged())
    }

    @Test fun settlingStepsHalfADecibelThenUpIsSlower() {
        val c = DensityControl(); c.reset(6.0)          // 3.0
        c.update(0.9, 6.0); assertEquals(2.5, c.liftDb)
        c.update(0.0, 6.0); assertEquals(3.0, c.liftDb)
        c.update(0.2, 6.0); assertEquals(3.0, c.liftDb)  // on target: holds
        repeat(DensityControl.SETTLE_STEPS) { c.update(0.2, 6.0) }
        c.update(0.0, 6.0); assertEquals(3.25, c.liftDb) // settled: up is slow
        c.update(0.9, 6.0); assertEquals(2.75, c.liftDb) // down is not
    }

    /** The phone predicts its limiter from peaks heard BEFORE the lift (E-003); exact values, both routes, both sides. */
    @Test fun phoneShareRaisesUnheardPeaksByTheLift() {
        val peaks = List(30) { 0.5 } + List(10) { 0.8 }      // -6 dBFS and -1.9 dBFS
        assertEquals(0.0, DensityControl.phoneShare(peaks, 0.0))          // nothing reaches -1.3 dBFS
        assertEquals(0.25, DensityControl.phoneShare(peaks, 1.0))         // 0.8 -> 0.898 crosses; 0.5 -> 0.56 does not
        assertEquals(1.0, DensityControl.phoneShare(peaks, 6.0))          // 0.5 -> 0.998: every frame at the ceiling
        assertNull(DensityControl.phoneShare(List(39) { 0.9 }, 0.0), "under 2 s of frames is not data")
        assertTrue(DensityControl.PHONE_CEILING in 0.85..0.87)
    }

    /** The phone loop steers a quiet master up to the cap and a hot one down, through the predicted share. */
    @Test fun phoneSteeringSettles() {
        // [loud] of 40 frames peak at [peak], the rest 12 dB lower
        fun run(peak: Double, loud: Int): Double {
            val c = DensityControl(); c.reset(6.0)
            repeat(60) { c.update(DensityControl.phoneShare(List(40) { i -> if (i < loud) peak else peak / 4 }, c.liftDb), 6.0) }
            return c.liftDb
        }
        assertEquals(6.0, run(0.3, 8), "a quiet master (-10 dBFS peaks) gets the cap")
        assertEquals(0.0, run(0.95, 30), "a master with most hits already at the ceiling gets nothing")
        // exactly the target share (8 of 40 at the ceiling) is where the control rests: it holds, it does not hunt
        assertEquals(3.0, run(0.95, 8))
    }

    /** Emulator, 29 Sep: the -96 dBFS floor raised by +53 dB of volume added back read as sound, and silence steered. */
    @Test fun silenceStaysSilenceWhateverIsAddedBack() {
        val floor = List(60) { Math.pow(10.0, -96.0 / 20) }
        assertEquals(null, DensityControl.phoneShare(floor, 1.5 + 53.3))
        // and a real low-volume song (-40 dBFS peaks) still counts once its volume is added back
        assertEquals(1.0, DensityControl.phoneShare(List(60) { 0.01 }, 40.0))
    }
}
