package com.arnavagarwal.sound.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Strong mode ([StrongMode], his tap 9 Oct 2026 "Give phone a strong mode"). */
class StrongModeTest {
    private fun profile(kind: ContentKind) = ContentProfile(ContentKind.entries.associateWith { if (it == kind) 0.9 else 0.1 / 6 }, "test")

    private val speakerHeard = Spectrum.PINK.through(Speaker.PHONE.response)

    /** Loudness change the whole plan makes (curve + preamp), on what the output can play. */
    private fun loudness(p: EnginePlan, heard: Spectrum) = Loudness.changeDb(p.curve, heard) + p.preampDb

    private fun plan(kind: ContentKind, sw: UserSwitches, speaker: Boolean) = Mixer.plan(profile(kind), null, sw, null, null, speaker = speaker)

    @Test fun offByDefaultSoOlderInstallsKeepTodaysSound() {
        assertFalse(UserSwitches().strongOn)
        for (kind in ContentKind.entries) {
            val p = plan(kind, UserSwitches(), speaker = true)
            assertEquals(0.0, p.strongLouderDb)
            assertFalse("strong mode" in p.explanation)
        }
    }

    @Test fun strongIsLouderThanOffOnThePhoneSpeaker() {
        for (kind in ContentKind.entries) for (step in StrongMode.steps) {
            val off = loudness(plan(kind, UserSwitches(), speaker = true), speakerHeard)
            val on = loudness(plan(kind, UserSwitches(strongOn = true, strongStep = step), speaker = true), speakerHeard)
            assertTrue(on >= off + StrongMode.louderDb(step) - 0.05,
                "$kind step $step: strong ${"%.2f".format(on)} dB vs off ${"%.2f".format(off)} dB, wanted +${StrongMode.louderDb(step)}")
        }
        // and the steps really are steps: each louder than the one before
        val l = StrongMode.steps.map { loudness(plan(ContentKind.BASS_HEAVY, UserSwitches(strongOn = true, strongStep = it), true), speakerHeard) }
        assertTrue(l[0] < l[1] && l[1] < l[2], "steps not in order: $l")
    }

    @Test fun strongGivesTheLaptopShapeOnTheSpeaker() {
        // at Bold, with the app's own tuning off, the speaker hears the laptop's liked peaks (desktop LaptopSetting: +8 / +6)
        val sw = UserSwitches(autoEnabled = false, strongOn = true, strongStep = StrongMode.BOLD)
        val p = plan(ContentKind.VOCAL_POP, sw, speaker = true)
        val off = plan(ContentKind.VOCAL_POP, UserSwitches(autoEnabled = false), speaker = true)
        assertEquals(8.0, p.curve.at(350.0) - off.curve.at(350.0), 0.5)
        assertEquals(6.0, p.curve.at(3_000.0) - off.curve.at(3_000.0), 0.5)
    }

    @Test fun limiterStaysOnInStrong() {
        for (speaker in listOf(true, false)) for (step in StrongMode.steps) for (kind in ContentKind.entries)
            assertTrue(plan(kind, UserSwitches(strongOn = true, strongStep = step), speaker).limiterOn, "limiter off: $kind step $step speaker $speaker")
    }

    @Test fun highPassOnlyOnTheSpeakerNeverOnHeadphones() {
        for (step in StrongMode.steps) for (kind in ContentKind.entries) {
            val sw = UserSwitches(strongOn = true, strongStep = step)
            val ears = plan(kind, sw, speaker = false)
            val earsOff = plan(kind, UserSwitches(), speaker = false)
            // on headphones Strong adds no low cut: the deep bass is where it was without Strong (its peaks add a hair at most)
            for (f in listOf(40.0, 60.0, 90.0))
                assertTrue(ears.curve.at(f) >= earsOff.curve.at(f) - 0.5, "$kind step $step: headphones cut at $f Hz: ${ears.curve.at(f)} vs ${earsOff.curve.at(f)}")
            // on the speaker the high-pass is there
            val sp = plan(kind, sw, speaker = true)
            assertTrue(sp.curve.at(40.0) < sp.curve.at(1_000.0) - 6.0, "$kind step $step: no high-pass on the speaker")
        }
    }

    // his tap 9 Oct 2026 "Louder in earbuds too": the ears get the same rise as the speaker
    @Test fun onHeadphonesStrongIsLouderByTheStep() {
        for (kind in ContentKind.entries) for (step in StrongMode.steps) {
            val p = plan(kind, UserSwitches(strongOn = true, strongStep = step), speaker = false)
            assertEquals(StrongMode.louderDb(step), p.strongLouderDb)
            val off = plan(kind, UserSwitches(), speaker = false)
            assertTrue(loudness(p, Spectrum.PINK) >= loudness(off, Spectrum.PINK) + StrongMode.louderDb(step) - 0.05,
                "$kind step $step not louder on headphones: ${loudness(p, Spectrum.PINK)} vs ${loudness(off, Spectrum.PINK)}")
        }
    }

    @Test fun keepsTheMixersClamps() {
        val sw = UserSwitches(strongOn = true, strongStep = StrongMode.MAX, manualTone = Tone(bass = 12.0, warmth = 12.0, clarity = 12.0, treble = 12.0), intensity = 1.5)
        for (speaker in listOf(true, false)) {
            val c = plan(ContentKind.BASS_HEAVY, sw, speaker).curve
            assertTrue(c.db.max() <= Mixer.MAX_BOOST + 1e-9 && c.db.min() >= Mixer.MAX_CUT - 1e-9, "clamp broken: ${c.db.min()}..${c.db.max()}")
        }
    }
}
