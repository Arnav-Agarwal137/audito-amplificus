package com.arnavagarwal.sound.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The phone-speaker plan ([Speaker]): his question 22 Sep, "why is the difference not hearable without earbuds?" */
class SpeakerTest {
    private fun profile(kind: ContentKind) = ContentProfile(ContentKind.entries.associateWith { if (it == kind) 0.9 else 0.1 / 6 }, "test")

    /** What the speaker actually plays: the plan's gain seen through the speaker's own response. */
    private fun heard(p: EnginePlan, f: Double) = p.curve.at(f) + p.preampDb

    @Test fun bassSliderIsHeardOnTheSpeakerAndDoesNotTurnTheSongDown() {
        val sw = UserSwitches(autoEnabled = false, manualTone = Tone(bass = 8.0))
        val sp = Mixer.plan(profile(ContentKind.BASS_HEAVY), null, sw, null, null, speaker = true)
        // no boost pushed where a phone speaker cannot follow
        assertTrue(sp.curve.at(60.0) <= 0.0, "deep bass still boosted on the speaker: ${sp.curve.at(60.0)} dB")
        // the asked-for bass lands where the speaker can play it
        assertTrue(heard(sp, Speaker.MOVED_BASS_HZ) >= 4.0, "upper bass only ${heard(sp, Speaker.MOVED_BASS_HZ)} dB")
        // and the rest of the song is not turned down to pay for it
        assertTrue(heard(sp, 1_000.0) >= -2.5, "rest of the song down ${heard(sp, 1_000.0)} dB")
    }

    @Test fun onTheSpeakerTheToneChangeIsBiggerWhereItCanBeHeard() {
        val sw = UserSwitches()
        val ears = Mixer.plan(profile(ContentKind.BASS_HEAVY), null, sw, null, null)
        val sp = Mixer.plan(profile(ContentKind.BASS_HEAVY), null, sw, null, null, speaker = true)
        // with loudness matched, what the ear hears is CONTRAST: highest minus lowest point in the speaker's range
        fun contrast(p: EnginePlan): Double {
            val g = Grid.freqs.indices.filter { Grid.freqs[it] in 300.0..6_000.0 }.map { p.curve.db[it] }
            return g.max() - g.min()
        }
        assertTrue(contrast(sp) >= contrast(ears) + 2.0, "speaker contrast ${contrast(sp)} dB vs ${contrast(ears)} dB for earbuds")
        assertTrue(heard(sp, 3_000.0) > heard(ears, 3_000.0) + 1.5, "no presence lift on the speaker")
    }

    @Test fun speakerLoudnessIsMatchedOnWhatTheSpeakerPlays() {
        for (kind in ContentKind.entries) {
            val sp = Mixer.plan(profile(kind), null, UserSwitches(manualTone = Tone(bass = 6.0)), null, null, speaker = true)
            val change = Loudness.changeDb(sp.curve.plusConst(sp.preampDb), Spectrum.PINK.through(Speaker.response))
            assertTrue(change <= 0.01, "$kind: speaker plan louder than the untouched song by $change dB")
            // never a boost of the whole song: where matching would need one, it is left at 0 (as for earbuds)
            if (sp.preampDb < 0.0) assertEquals(0.0, change, 0.01, "$kind")
            assertTrue(sp.speaker && "tuned for the phone speaker" in sp.explanation)
        }
    }

    @Test fun earbudsPlanIsUntouched() {
        val sw = UserSwitches(manualTone = Tone(bass = 8.0))
        val a = Mixer.plan(profile(ContentKind.BASS_HEAVY), null, sw, null, null)
        val b = Mixer.plan(profile(ContentKind.BASS_HEAVY), null, sw, null, null, speaker = false)
        assertTrue(a.curve.approxEquals(b.curve, 1e-9) && !a.speaker)
        assertTrue(a.curve.at(50.0) + a.preampDb >= 6.0, "earbud bass boost lost")
    }

    @Test fun automaticOffStillMovesOnlyWhatThePersonAskedFor() {
        val flat = Mixer.plan(profile(ContentKind.SMOOTH), null, UserSwitches(autoEnabled = false), null, null, speaker = true)
        // nothing asked for -> no speaker lifts either, only the part it cannot play is taken out
        assertTrue(Grid.freqs.indices.filter { Grid.freqs[it] >= 250.0 }.all { kotlin.math.abs(flat.curve.db[it]) < 1.0 },
            "speaker added its own lifts with automatic tuning off")
    }
}
