package com.arnavagarwal.sound.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongStatsTest {
    /** A spectrum with power only in [lo, hi) Hz (plus a trace elsewhere so nothing is exactly zero). */
    private fun band(lo: Double, hi: Double, p: Double = 1.0) =
        Spectrum(DoubleArray(Grid.size) { g -> if (Grid.freqs[g] >= lo && Grid.freqs[g] < hi) p else 1e-9 })

    private fun feed(s: SongStats, seconds: Int, frame: (Int) -> Pair<Spectrum, Double>) {
        for (i in 0 until seconds * 20) { val (sp, l) = frame(i); s.frame(sp, l) }
    }

    @Test fun aSkippedSongIsNotADataPoint() {
        val s = SongStats("t", "a")
        feed(s, 29) { band(300.0, 3_000.0) to -20.0 }
        assertNull(s.summary())
        feed(s, 2) { band(300.0, 3_000.0) to -20.0 }
        assertNotNull(s.summary())
    }

    @Test fun silenceIsNotCounted() {
        val s = SongStats("t", "a")
        feed(s, 60) { band(300.0, 3_000.0) to -80.0 }
        assertNull(s.summary())
    }

    @Test fun voiceBandIsMeasured() {
        val s = SongStats("t", "a")
        feed(s, 31) { band(400.0, 3_000.0) to -20.0 }
        val m = s.summary()!!
        assertTrue((m["voice"] as Double) > 0.99, "voice ${m["voice"]}")
        assertTrue((m["bass"] as Double) < 0.01, "bass ${m["bass"]}")
    }

    // the point of the log: kicks (low band jumping) must read apart from a sustained, sung bass line
    @Test fun beatsAndASustainedBassLineReadApart() {
        val beat = SongStats("beat", "a")
        feed(beat, 31) { i -> (if (i % 10 == 0) band(40.0, 150.0, 100.0) else band(40.0, 150.0, 1.0)).let { sp ->
            // same mids in every frame; the low band alone jumps 20 dB on each kick, and the frame level follows its power
            Spectrum(DoubleArray(Grid.size) { g -> sp.power[g] + band(400.0, 3_000.0).power[g] }).let { f -> f to 10 * kotlin.math.log10(f.total) - 40 } } }
        val sung = SongStats("sung", "a")
        feed(sung, 31) { Spectrum(DoubleArray(Grid.size) { g -> band(40.0, 150.0, 10.0).power[g] + band(400.0, 3_000.0).power[g] }) to -20.0 }
        val b = beat.summary()!!["lowOnsetDb"] as Double
        val s = sung.summary()!!["lowOnsetDb"] as Double
        assertTrue(b > 10.0, "beat onsets $b dB")
        assertEquals(0.0, s, 0.5, "sung onsets $s dB")
    }

    @Test fun decisionsAreAveraged() {
        val s = SongStats("t", "a")
        feed(s, 31) { band(300.0, 3_000.0) to -20.0 }
        s.second(ContentKind.BASS_HEAVY, 6.0, 0.2); s.second(ContentKind.BASS_HEAVY, 4.0, null); s.second(null, null, 0.4)
        val m = s.summary()!!
        assertEquals(5.0, m["densityDb"] as Double, 1e-9)
        assertEquals(0.3, m["ceilShare"] as Double, 1e-9)
        assertEquals<Map<*, *>>(mapOf("BASS_HEAVY" to 2, "UNSURE" to 1), (m["kinds"] as Map<*, *>).toMap())
    }
}
