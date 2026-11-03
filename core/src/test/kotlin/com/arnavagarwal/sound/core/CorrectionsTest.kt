package com.arnavagarwal.sound.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** His 7 corrections after the first APK (22 Sep 2026): the parts that live in core. */
class CorrectionsTest {
    private val speech = ContentProfile(ContentKind.entries.associateWith { if (it == ContentKind.SPEECH) 1.0 else 0.0 }, "test")

    // ---- "More controls": three fine sliders ----

    @Test fun eachFineSliderBoostsItsOwnRegionOnly() {
        val rumble = Fine(rumble = 6.0).curve(); val presence = Fine(presence = 6.0).curve(); val air = Fine(air = 6.0).curve()
        assertTrue(rumble.at(50.0) > 5.5 && rumble.at(1_000.0) < 0.3, "rumble: ${rumble.at(50.0)} at 50 Hz, ${rumble.at(1_000.0)} at 1 kHz")
        assertTrue(presence.at(5_000.0) > 5.5 && presence.at(200.0) < 0.3 && presence.at(500.0) < 0.5, "presence")
        assertTrue(air.at(18_000.0) > 4.5 && air.at(2_000.0) < 0.3, "air: ${air.at(18_000.0)} at 18 kHz")
        assertTrue(Fine.ZERO.curve().approxEquals(Curve.FLAT, 1e-9))
    }

    @Test fun fineSlidersReachTheSoundThroughTheMixer() {
        val off = Mixer.plan(speech, null, UserSwitches(autoEnabled = false), null, null)
        val on = Mixer.plan(speech, null, UserSwitches(autoEnabled = false, manualFine = Fine(presence = 6.0)), null, null)
        assertTrue(on.curve.at(5_000.0) - off.curve.at(5_000.0) > 5.5)
        assertTrue("your extra controls" in on.explanation)
    }

    // ---- "How strong" as three buttons ----

    @Test fun strengthStepsDownAndStopsAtGentle() {
        assertEquals(Strength.NORMAL, Strength.stepDown(Strength.STRONG))
        assertEquals(Strength.GENTLE, Strength.stepDown(Strength.NORMAL))
        assertNull(Strength.stepDown(Strength.GENTLE))
        assertEquals(Strength.GENTLE, Strength.stepDown(0.73)) // an old slider value
        assertEquals(Strength.NORMAL, Strength.nearest(1.1))
        assertEquals(Strength.STRONG, Strength.nearest(1.5))
    }

    // ---- voice clarity ----

    private fun shaped(curve: Curve) = Spectrum(DoubleArray(Grid.size) { Math.pow(10.0, curve.db[it] / 10) })

    @Test fun voiceClarityFollowsTheVoiceAgainstTheBoom() {
        val flat = VoiceClarity.balanceDb(Spectrum.PINK)!!
        assertEquals(0.0, flat, 1e-9)
        // speech-like: the 1-4 kHz range 10 dB under the 150-500 Hz range -> about 0.7 = "Clear"
        val speechy = shaped(Curve(DoubleArray(Grid.size) { if (Grid.freqs[it] > 800.0) -10.0 else 0.0 }))
        val s = VoiceClarity.score(speechy)!!
        assertEquals(10.0 / 14, s, 1e-9, "speech-like score")
        // a bass boost buries the voice; a clarity boost brings it back
        val boom = Curve.of(ParametricFilter(FilterType.PEAK, 250.0, 8.0, 0.8))
        assertTrue(VoiceClarity.score(speechy, boom)!! < s - 0.2)
        assertTrue(VoiceClarity.score(speechy, Tone(clarity = 6.0).curve())!! > s + 0.15)
        // the speech preset must never make a lecture LESS clear
        val lecture = Presets.all.getValue(ContentKind.SPEECH).curve
        assertTrue(VoiceClarity.score(speechy, lecture)!! > s)
    }

    @Test fun voiceClarityHasNoAnswerForSilence() {
        assertNull(VoiceClarity.score(Spectrum(DoubleArray(Grid.size))))
        assertNotNull(VoiceClarity.score(Spectrum.PINK))
    }

    // ---- the challenge ----

    @Test fun aRoundCannotBeScoredUntilBothSidesWereHeard() {
        val c = Challenge(6, seed = 3)
        assertFalse(c.canPick)
        assertFailsWith<IllegalStateException> { c.pick(true) }
        c.heard(true); assertFalse(c.canPick)
        c.heard(true); assertFalse(c.canPick) // the same side twice is still one side
        c.heard(false); assertTrue(c.canPick)
        c.pick(true)
        assertFalse(c.canPick, "a new round starts unheard")
        assertEquals(1, c.round)
    }

    @Test fun noResultPromisesSomethingTheAppDoesNotDo() {
        for (wins in 0..6) {
            val c = Challenge(6, seed = 5)
            var left = wins
            repeat(6) { c.heard(true); c.heard(false); val pickEnh = left-- > 0; c.pick(pickedA = if (pickEnh) c.enhancedIsA[c.round] else !c.enhancedIsA[c.round]) }
            val m = c.result().message.lowercase()
            for (promise in listOf("we'll", "we will", "learn", "keep it", "tune closer")) assertFalse(promise in m, "\"$m\" promises: $promise")
            assertTrue("$wins of 6" in m || "${6 - wins} of 6" in m, m)
        }
    }
}
