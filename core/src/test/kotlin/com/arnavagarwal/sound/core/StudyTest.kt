package com.arnavagarwal.sound.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Study music on the phone (29 Sep): the singer's band down, the rest untouched, and no quieter overall. */
class StudyTest {
    private fun profile(kind: ContentKind) = ContentProfile(ContentKind.entries.associateWith { if (it == kind) 0.9 else 0.1 / 6 }, "test")
    private fun at(c: Curve, hz: Double) = c.db[Grid.freqs.indices.minBy { abs(Grid.freqs[it] - hz) }]
    private val bare = UserSwitches(autoEnabled = false, learnEnabled = false, correctionEnabled = false, loudnessCompEnabled = false)

    @Test fun offChangesNothing() {
        for (speaker in listOf(false, true)) {
            val a = Mixer.plan(profile(ContentKind.VOCAL_POP), null, UserSwitches(), null, 70.0, speaker = speaker)
            val b = Mixer.plan(profile(ContentKind.VOCAL_POP), null, UserSwitches(voiceDownDb = 0.0), null, 70.0, speaker = speaker)
            assertTrue(a.curve.approxEquals(b.curve, 1e-12)); assertEquals(a.preampDb, b.preampDb, 1e-12)
        }
    }

    @Test fun cutsTheVoiceBandAndLeavesBassAndTreble() {
        val off = Mixer.plan(profile(ContentKind.VOCAL_POP), null, bare, null, null)
        val on = Mixer.plan(profile(ContentKind.VOCAL_POP), null, bare.copy(voiceDownDb = StudyMusic.SOFTER_DB), null, null)
        val d = DoubleArray(Grid.size) { on.curve.db[it] - off.curve.db[it] }
        val cut = Curve(d)
        assertTrue(at(cut, 1000.0) < -5.0, "1 kHz only ${at(cut, 1000.0)}")
        assertTrue(at(cut, 2000.0) < -5.0, "2 kHz only ${at(cut, 2000.0)}")
        assertTrue(abs(at(cut, 50.0)) < 0.5, "bass moved ${at(cut, 50.0)}")
        assertTrue(abs(at(cut, 16000.0)) < 1.0, "treble moved ${at(cut, 16000.0)}")
    }

    /** Every step cuts as deep as its label says: the tuning's -12 dB floor must not quietly turn "almost gone" (24) into 12. */
    @Test fun everyStepCutsItsFullDepth() {
        for (kind in listOf(ContentKind.VOCAL_POP, ContentKind.BASS_HEAVY)) {
            val off = Mixer.plan(profile(kind), null, bare, null, null, speaker = true)
            for ((label, db) in StudyMusic.STEPS.drop(1)) {
                val on = Mixer.plan(profile(kind), null, bare.copy(voiceDownDb = db), null, null, speaker = true)
                val cut = at(on.curve, 1500.0) - at(off.curve, 1500.0)
                assertTrue(cut < -0.85 * db, "$label ($kind): only $cut dB at 1.5 kHz")
            }
        }
    }

    /** His tap "Soft + lift it back up": soft must never mean quieter - on the plain song and through the phone speaker. */
    @Test fun noQuieterWithItOn() {
        for (speaker in listOf(false, true)) for (kind in listOf(ContentKind.VOCAL_POP, ContentKind.BASS_HEAVY)) {
            val sw = UserSwitches(learnEnabled = false, loudnessCompEnabled = false)
            val heard = if (speaker) Spectrum.PINK.through(Speaker.PHONE.response) else Spectrum.PINK
            fun loud(p: EnginePlan) = Loudness.changeDb(p.curve, heard) + p.preampDb
            val off = Mixer.plan(profile(kind), null, sw, null, null, speaker = speaker)
            for ((label, db) in StudyMusic.STEPS.drop(1)) {
                val on = Mixer.plan(profile(kind), null, sw.copy(voiceDownDb = db), null, null, speaker = speaker)
                assertTrue(on.preampDb > off.preampDb, "no lift given back ($label, $kind, speaker=$speaker)")
                assertEquals(loud(off), loud(on), 0.1, "$label $kind speaker=$speaker")
            }
        }
    }
}
