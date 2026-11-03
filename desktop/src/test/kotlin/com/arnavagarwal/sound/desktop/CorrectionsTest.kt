package com.arnavagarwal.sound.desktop

import com.arnavagarwal.sound.core.ContentKind
import com.arnavagarwal.sound.core.ContentProfile
import com.arnavagarwal.sound.core.Mixer
import com.arnavagarwal.sound.core.Speaker
import com.arnavagarwal.sound.core.UserSwitches
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Laptop: your headphone's own fix" (his tap, 1 Nov 2026): the phone's AutoEq import, on the laptop. */
class CorrectionsTest {
    // the shape of an AutoEq ParametricEQ.txt (research/data-models-standards.md); values made up for the test
    private val autoEq = """
        Preamp: -6.2 dB
        Filter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70
        Filter 2: ON PK Fc 2400 Hz Gain -4.0 dB Q 1.20
        Filter 3: ON HSC Fc 10000 Hz Gain 3.0 dB Q 0.70
    """.trimIndent()
    private val buds = "{0.0.0.00000000}.{aaaa-1111}"
    private val hyperx = "{0.0.0.00000000}.{bbbb-2222}"

    private fun tempDir(): File = Files.createTempDirectory("fixes").toFile().also { it.deleteOnExit() }

    @Test fun keptPerOutputAndReadBack() {
        val c = Corrections(tempDir())
        assertNull(c.save(buds, "OnePlus Nord Buds 3 Pro", "OnePlus Nord Buds 3 Pro", autoEq))
        val got = assertNotNull(c.load(buds))
        assertEquals("OnePlus Nord Buds 3 Pro", got.name)
        assertEquals(3, got.profile.filters.size)
        assertNull(c.load(hyperx), "the buds' file must never be used on another output")
        c.remove(buds)
        assertNull(c.load(buds))
    }

    @Test fun badFilesAreRefusedWithWordsAndNothingIsKept() {
        val dir = tempDir(); val c = Corrections(dir)
        assertEquals(Words.FIX_NO_FILTERS, c.save(buds, "x", "x", "Preamp: -3 dB\n"))
        assertTrue(c.save(buds, "x", "x", "hello, this is a song list") != null)
        assertNull(c.load(buds)); assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun thePreviewKeepsNothing() {
        val c = Corrections(null)
        assertEquals(Words.FIX_NOT_IN_PREVIEW, c.save(buds, "b", "b", autoEq))
        assertNull(c.load(buds))
    }

    @Test fun nameComesFromTheFileName() {
        assertEquals("Sony WH-1000XM4", Corrections.nameFromFile("Sony WH-1000XM4 ParametricEQ.txt"))
        assertEquals("my buds", Corrections.nameFromFile("my buds.txt"))
        assertEquals("Sony WH-1000XM4", Corrections.nameFromFile("Sony WH-1000XM4 ParametricEq.txt"), "any capitals")
        assertEquals("Imported", Corrections.nameFromFile(".txt"))
    }

    @Test fun theFixReachesTheMixerOnlyOffTheSpeakers() {
        val c = Corrections(tempDir()); c.save(buds, "b", "b", autoEq)
        val fix = c.load(buds)
        assertNull(Engine.fixFor(speaker = true, fix = fix), "the speakers play the liked setting, never a headphone file")
        val curve = assertNotNull(Engine.fixFor(speaker = false, fix = fix))
        val music = ContentProfile(ContentKind.entries.associateWith { if (it == ContentKind.BASS_HEAVY) 0.9 else 0.1 / 6 }, "test")
        fun plan(cor: com.arnavagarwal.sound.core.Curve?) =
            Mixer.plan(music, null, Engine.mixerSwitches(UserSwitches(), false), cor, null, speaker = false, speakerProfile = Speaker.LAPTOP)
        val without = plan(null); val with = plan(curve)
        // the file's -4 dB at 2.4 kHz must show up in what the buds get (relative to 100 Hz, so any overall preamp cancels)
        val shift = (with.curve.at(2_400.0) - with.curve.at(1_000.0)) - (without.curve.at(2_400.0) - without.curve.at(1_000.0))
        assertTrue(shift < -2.0, "2.4 kHz dip from the file: $shift dB")
        assertTrue("earbud correction" in with.explanation, "${with.explanation}")
    }

    @Test fun theCardIsForHeadphonesOnlyAndUsesTheEngine() {
        val main = File("src/main/kotlin/com/arnavagarwal/sound/desktop/Main.kt").readText()
        assertTrue("if (st.device?.isSpeaker == false) Card {" in main && "engine.importFix(" in main && "engine.removeFix()" in main)
        val engine = File("src/main/kotlin/com/arnavagarwal/sound/desktop/Engine.kt").readText()
        assertTrue("fixCurve(speaker), null, spectrum" in engine, "the plan must be given the fix")
        assertTrue("fix = corrections.load(e.id)" in engine, "switching outputs must load that output's own file")
    }
}
