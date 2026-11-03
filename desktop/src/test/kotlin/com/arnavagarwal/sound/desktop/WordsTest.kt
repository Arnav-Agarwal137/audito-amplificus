package com.arnavagarwal.sound.desktop

import com.arnavagarwal.sound.core.ContentKind
import com.arnavagarwal.sound.core.ContentProfile
import com.arnavagarwal.sound.core.Mixer
import com.arnavagarwal.sound.core.Speaker
import com.arnavagarwal.sound.core.UserSwitches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordsTest {
    @Test fun appNamesAreTheOnesHeKnows() {
        assertEquals("Chrome", Words.app("Chrome.UserData.Profile4")) // seen live on his laptop, 23 Sep
        assertEquals("Chrome", Words.app("chrome.exe"))
        assertEquals("Spotify", Words.app("Spotify.exe"))
        assertEquals("Edge", Words.app("MSEdge"))
        assertEquals("Media Player", Words.app("Microsoft.ZuneMusic_8wekyb3d8bbwe!Microsoft.ZuneMusic"))
        assertEquals("Someapp", Words.app("someapp.exe"))
        assertNull(Words.app("")); assertNull(Words.app(null))
    }

    /** On laptop speakers the plan moves the bass to 350 Hz; the words must call that more bass, not less boom. */
    @Test fun theLaptopPlanIsDescribedAsFullerBassAndClearerVoices() {
        val prof = ContentProfile(ContentKind.entries.associateWith { if (it == ContentKind.BASS_HEAVY) 0.9 else 0.1 / 6 }, "t")
        val p = Mixer.plan(prof, null, UserSwitches(), null, null, speaker = true, speakerProfile = Speaker.LAPTOP)
        val s = Words.summary(Words.parts(p.curve.db, speaker = true), on = true, reaches = true, playing = true)
        assertTrue(s.contains("fuller bass", ignoreCase = true) && "clearer voices" in s, s)
        assertTrue("less boom" !in s, s)
    }

    @Test fun offAndUnreachableSayWhatIsTrue() {
        val flat = Words.parts(DoubleArray(96), speaker = true)
        assertTrue("exactly as it did before" in Words.summary(flat, on = false, reaches = true, playing = true))
        assertTrue("cannot reach" in Words.summary(flat, on = true, reaches = false, playing = true))
        // the sliders must not claim "already set" while nothing is being applied (seen 23 Sep in the preview)
        assertTrue("not used right now" in Words.slidersHint(tuningOn = false))
        assertTrue("already set" !in Words.slidersHint(tuningOn = false))
        assertTrue("already set" in Words.slidersHint(tuningOn = true))
    }

    /** 9 Oct 2026: on his dongle and Bluetooth headphones nothing happened, because Equalizer APO was ticked for the
     *  speakers only. The help must say the three things the Device Selector needs, sit at the TOP, and have a button. */
    @Test fun newHeadphonesHelpIsCompleteAndAtTheTop() {
        val h = Words.NOT_ON_DEVICE_HELP
        assertTrue("tap Yes" in h)
        assertTrue("connect later" in h)
        val byHand = Words.NOT_ON_DEVICE_HELP_BY_HAND // fallback when ApoSetup is missing
        assertTrue("only while connected" in byHand)
        assertTrue("restart the laptop" in byHand)
        assertTrue("Each new headphone" in byHand)
        val main = java.io.File("src/main/kotlin/com/arnavagarwal/sound/desktop/Main.kt").readText()
        val help = main.indexOf("Words.NOT_ON_DEVICE_HELP")
        assertTrue(help in 0 until main.indexOf("SafetyBadge()"), "help card must come before the safety badge")
        assertEquals(1, Regex("Words\\.NOT_ON_DEVICE_HELP\\b(?!_)").findAll(main).count())
        assertTrue("Apo.switchOnEverywhere()" in main && "Apo.openDeviceSelector()" in main)
        assertTrue(Apo.DEVICE_SELECTOR.path.endsWith("DeviceSelector.exe"))
    }

    /** The elevated command must keep a path with spaces or an apostrophe whole, and run `setup`. */
    @Test fun switchOnEverywhereCommandQuotesThePath() {
        val c = Apo.elevateCommand(java.io.File("C:/Program Files/Bob's Apps/apo-setup/ApoSetup.dll"))
        assertTrue("-Verb RunAs" in c)
        assertTrue("'\"C:\\Program Files\\Bob''s Apps\\apo-setup\\ApoSetup.dll\" setup'" in c, c)
        assertTrue(ToolsRoot.apoSetup.path.replace('\\', '/').endsWith("apo-setup/ApoSetup.dll"))
    }

    @Test fun amountsReadNaturally() {
        assertEquals("Tuned side turned down 1.5 dB from your answers.", Words.earLevel(-1.5))
        assertEquals("Turned down 2 dB for now, so loud parts do not crackle.", Words.clip(-2.0))
        assertNull(Words.earLevel(0.0)); assertNull(Words.clip(0.0))
        assertNull(Words.tally(0, 0, 0))
    }

    /** The card words repeat two numbers from the code; read them from the code, never re-typed (global 7a). */
    @Test fun featureExplanationsMatchTheCode() {
        val warm = com.arnavagarwal.sound.core.Tone(warmth = 6.0).curve().db
        val peakHz = com.arnavagarwal.sound.core.Grid.freqs[warm.indices.maxByOrNull { warm[it] }!!]
        assertTrue(peakHz in 250.0..360.0, "warmth peaks at $peakHz Hz")
        assertTrue("300 Hz" in Words.ABOUT_WARMTH, Words.ABOUT_WARMTH)
        val most = com.arnavagarwal.sound.core.QuietFix.curve(40.0).db.maxOrNull()!!
        assertTrue(most <= com.arnavagarwal.sound.core.QuietFix.MAX_DB + 1e-9, "quiet lift $most")
        assertTrue("up to ${com.arnavagarwal.sound.core.QuietFix.MAX_DB.toInt()} dB" in Words.ABOUT_QUIET, Words.ABOUT_QUIET)
        for (s in listOf(Words.ABOUT_DENSITY, Words.ABOUT_VOLUME, Words.ABOUT_QUIET, Words.ABOUT_STUDY, Words.ABOUT_WARMTH))
            assertTrue(s.length in 60..400, "card text length ${s.length}: $s")
        assertTrue("\"${Words.VOLUME_BUTTON}\"" in Words.ABOUT_VOLUME)
    }
}
