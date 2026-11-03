package com.arnavagarwal.sound.desktop

import com.arnavagarwal.sound.core.ContentKind
import com.arnavagarwal.sound.core.ContentProfile
import com.arnavagarwal.sound.core.Mixer
import com.arnavagarwal.sound.core.Speaker
import com.arnavagarwal.sound.core.StrongMode
import com.arnavagarwal.sound.core.UserSwitches
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Laptop Strong mode for earbuds / headphones (his tap 31 Oct 2026, after "it didnt make a diff" on his OnePlus buds). */
class StrongTest {
    private val music = ContentProfile(ContentKind.entries.associateWith { if (it == ContentKind.BASS_HEAVY) 0.9 else 0.1 / 6 }, "test")

    private fun buds(sw: UserSwitches) = Mixer.plan(music, null, Engine.mixerSwitches(sw, speaker = false), null, null,
        speaker = false, speakerProfile = Speaker.LAPTOP)

    @Test fun onHeadphonesStrongAddsTheLikedPeaks() {
        val off = buds(UserSwitches())
        val bold = buds(UserSwitches(strongOn = true, strongStep = StrongMode.BOLD))
        // the liked setting's +8 dB at 350 Hz and +6 dB at 3 kHz (LaptopSetting.FILTERS), on top of the automatic tune
        assertTrue(bold.curve.at(350.0) - off.curve.at(350.0) > 6.0, "350 Hz: ${off.curve.at(350.0)} -> ${bold.curve.at(350.0)}")
        assertTrue(bold.curve.at(3_000.0) - off.curve.at(3_000.0) > 4.0, "3 kHz: ${off.curve.at(3_000.0)} -> ${bold.curve.at(3_000.0)}")
        val light = buds(UserSwitches(strongOn = true, strongStep = StrongMode.LIGHT))
        val max = buds(UserSwitches(strongOn = true, strongStep = StrongMode.MAX))
        assertTrue(light.curve.at(350.0) < bold.curve.at(350.0) && bold.curve.at(350.0) < max.curve.at(350.0), "steps must climb")
    }

    @Test fun theSpeakersNeverSeeStrong() {
        val sw = UserSwitches(strongOn = true, strongStep = StrongMode.MAX)
        assertFalse(Engine.mixerSwitches(sw, speaker = true).strongOn)
        assertTrue(Engine.mixerSwitches(sw, speaker = false).strongOn)
    }

    @Test fun headphonePreampNeverGoesAboveZero() {
        assertEquals(0.0, Engine.headphonePreampDb(4.5, 0.0))
        assertEquals(-2.0, Engine.headphonePreampDb(-3.0, 1.0))
        val max = buds(UserSwitches(strongOn = true, strongStep = StrongMode.MAX))
        assertTrue(Engine.headphonePreampDb(max.preampDb, 3.0) <= 0.0)
    }

    @Test fun strongStartsOffAndTheCardSitsUnderTheWand() {
        val engine = File("src/main/kotlin/com/arnavagarwal/sound/desktop/Engine.kt").readText()
        assertTrue("prefs.getBoolean(\"strongOn\", false)" in engine, "Strong must default to OFF")
        val main = File("src/main/kotlin/com/arnavagarwal/sound/desktop/Main.kt").readText()
        val wand = main.indexOf("BigSwitch(st.tuningOn)")
        val card = main.indexOf("Text(\"Strong mode\"")
        val density = main.indexOf("Text(\"Density\"")
        assertTrue(wand in 0 until card && card < density, "Strong card must be right under the wand")
    }

    @Test fun wordsSayWhatIsHappening() {
        assertTrue(Words.strong(false, StrongMode.BOLD, speaker = false, tuningOn = true).startsWith("Off."))
        assertTrue("tuning is off" in Words.strong(true, StrongMode.BOLD, speaker = false, tuningOn = false))
        assertTrue("speakers already" in Words.strong(true, StrongMode.BOLD, speaker = true, tuningOn = true))
        assertEquals("On (Max): fuller and clearer in your earbuds or headphones.", Words.strong(true, StrongMode.MAX, speaker = false, tuningOn = true))
        assertEquals(listOf("Light", "Bold", "Max"), StrongMode.steps.map { Words.strongStep(it) })
    }
}
