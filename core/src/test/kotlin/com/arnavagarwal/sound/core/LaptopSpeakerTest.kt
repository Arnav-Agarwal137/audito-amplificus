package com.arnavagarwal.sound.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The laptop-speaker profile ([Speaker.LAPTOP]): his ear test 23 Sep asked for a SIGNIFICANT, clearly audible change. */
class LaptopSpeakerTest {
    private fun profile(kind: ContentKind) = ContentProfile(ContentKind.entries.associateWith { if (it == kind) 0.9 else 0.1 / 6 }, "test")
    private fun laptop(kind: ContentKind, sw: UserSwitches = UserSwitches()) =
        Mixer.plan(profile(kind), null, sw, null, null, speaker = true, speakerProfile = Speaker.LAPTOP)

    @Test fun atNormalItIsNearTheCurveHePreferred() {
        // preferred 23 Sep: +8 at 350 Hz, +6 at 3 kHz (a gentle ~2 dB curve was "No difference")
        val p = laptop(ContentKind.BASS_HEAVY)
        assertTrue(p.curve.at(350.0) in 6.5..10.0, "350 Hz ${p.curve.at(350.0)} dB")
        assertTrue(p.curve.at(3_000.0) in 5.0..8.0, "3 kHz ${p.curve.at(3_000.0)} dB")
        assertTrue(p.curve.at(60.0) <= 0.0, "deep bass boosted on a laptop speaker: ${p.curve.at(60.0)} dB")
        assertTrue("tuned for the laptop speakers" in p.explanation)
    }

    @Test fun itIsStrongerThanThePhoneSpeaker() {
        val phone = Mixer.plan(profile(ContentKind.BASS_HEAVY), null, UserSwitches(), null, null, speaker = true)
        val lap = laptop(ContentKind.BASS_HEAVY)
        assertTrue(lap.curve.at(3_000.0) > phone.curve.at(3_000.0) + 1.5)
        assertTrue(lap.curve.at(350.0) > phone.curve.at(350.0) + 2.0)
    }

    @Test fun thePhoneValuesAreUnchanged() {
        assertEquals(250.0, Speaker.PHONE.highPassHz); assertEquals(Speaker.FULLNESS_DB, Speaker.PHONE.fullnessDb)
        assertEquals(Speaker.PRESENCE_DB, Speaker.PHONE.presenceDb); assertEquals(Speaker.MOVED_BASS_HZ, Speaker.PHONE.movedBassHz)
        assertTrue(Speaker.response.approxEquals(Speaker.PHONE.response, 1e-12))
    }

    @Test fun strengthStillScalesIt() {
        val gentle = laptop(ContentKind.SPEECH, UserSwitches(intensity = 0.5))
        val strong = laptop(ContentKind.SPEECH, UserSwitches(intensity = 1.4))
        assertTrue(strong.curve.at(3_000.0) > gentle.curve.at(3_000.0) + 3.0)
    }
}
