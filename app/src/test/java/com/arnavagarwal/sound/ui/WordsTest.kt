package com.arnavagarwal.sound.ui

import com.arnavagarwal.sound.core.DEFAULT_BAND_CUTOFFS
import com.arnavagarwal.sound.core.VolumeCoach
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WordsTest {
    private fun bands(bass: Double, voice: Double, treble: Double) = DoubleArray(DEFAULT_BAND_CUTOFFS.size) { i ->
        val f = DEFAULT_BAND_CUTOFFS[i]
        when { f <= 180 -> bass; f in 1_000.0..4_000.0 -> voice; f >= 5_600 -> treble; else -> 0.0 }
    }

    @Test fun partsAverageTheRightBands() {
        val p = Words.parts(bands(3.0, -2.0, 1.0))
        assertEquals(3.0, p.bass, 1e-9); assertEquals(-2.0, p.voice, 1e-9); assertEquals(1.0, p.treble, 1e-9)
    }

    @Test fun onTheSpeakerBassMeansItsLowestPlayableNotes() {
        // the speaker plan: deep bass taken out (-12), moved up to 250-500 Hz (+4): that is MORE bass you can hear
        val b = DoubleArray(DEFAULT_BAND_CUTOFFS.size) { i -> val f = DEFAULT_BAND_CUTOFFS[i]; when { f <= 180 -> -12.0; f <= 500 -> 4.0; else -> 0.0 } }
        assertEquals(4.0, Words.parts(b, speaker = true).bass, 1e-9)
        assertEquals(-12.0, Words.parts(b).bass, 1e-9)
        assertTrue(Words.summary(Words.parts(b, speaker = true), true, true).startsWith("More bass"))
    }

    @Test fun summarySaysWhatIsHappening() {
        assertEquals("More bass, clearer voices, less harsh highs.", Words.summary(Words.parts(bands(1.5, 1.0, -1.0)), on = true, playing = true))
        assertEquals("A light touch. It already sounds good.", Words.summary(Words.parts(bands(0.3, -0.2, 0.0)), true, true))
    }

    @Test fun summaryNeverDescribesATuningThatIsNotHappening() {
        val loud = Words.parts(bands(5.0, 5.0, 5.0))
        assertTrue(Words.summary(loud, on = false, playing = true).startsWith("The magic is off"))
        assertTrue(Words.summary(loud, on = true, playing = false).startsWith("Play something"))
    }

    @Test fun appNamesArePlain() {
        assertEquals("JioSaavn", Words.app("com.jio.media.jiobeats"))
        assertEquals("Vlc", Words.app("org.videolan.vlc"))
        assertEquals(null, Words.app(null))
    }

    @Test fun signedUsesARealMinusAndShowsZeroPlainly() {
        assertEquals("+1.5", Words.signed(1.5)); assertEquals("−0.5", Words.signed(-0.5)); assertEquals("0", Words.signed(0.01))
    }

    @Test fun challengeActionIsSaidOnlyWhenItIsDone() {
        assertEquals(null, Words.challengeAction(com.arnavagarwal.sound.core.Challenge.Verdict.CLEAR_WIN, null))
        assertEquals(null, Words.challengeAction(com.arnavagarwal.sound.core.Challenge.Verdict.NO_DIFFERENCE, 0.5))
        assertTrue("Gentle" in Words.challengeAction(com.arnavagarwal.sound.core.Challenge.Verdict.PREFERS_ORIGINAL, 0.5)!!)
        assertTrue("already on Gentle" in Words.challengeAction(com.arnavagarwal.sound.core.Challenge.Verdict.PREFERS_ORIGINAL, null)!!)
        assertEquals(listOf("Gentle", "Normal", "Strong"), listOf(0.5, 1.0, 1.4).map(Words::strength))
        assertEquals(listOf("Clear", "A bit muffled", "Muffled"), listOf(0.7, 0.5, 0.2).map(Words::clarity))
    }

    /** The phone's Density card says exactly what the laptop's says: read from the desktop source, never retyped here. */
    @Test fun densityAboutIsTheLaptopsWordForWord() {
        val src = File("../desktop/src/main/kotlin/com/arnavagarwal/sound/desktop/Words.kt").readText()
        val decl = src.substringAfter("const val ABOUT_DENSITY =").substringBefore("const val ABOUT_VOLUME")
        val laptop = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(decl).joinToString("") { it.groupValues[1] }
        assertTrue(laptop.length > 100, "read only '${laptop.take(40)}' from the desktop file: the check is not reading it")
        assertEquals(laptop, Words.ABOUT_DENSITY)
    }

    /** 8D copy: what it does and the failure line are the laptop's words, read from the desktop source. */
    @Test fun eightDWordsAreTheLaptops() {
        val src = File("../desktop/src/main/kotlin/com/arnavagarwal/sound/desktop/Words.kt").readText()
        val fn = src.substringAfter("fun eightD(").substringBefore("is EightD.State.Working")
        val laptop = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(fn).joinToString("") { it.groupValues[1] }
        assertTrue(laptop.length > 100, "read only '${laptop.take(40)}': the check is not reading the desktop file")
        val s = com.arnavagarwal.sound.engine.EightDMaker.State.Idle
        assertEquals(laptop, Words.eightD(s))
        assertTrue("\"That didn't work: \${s.why}.\"" in src)
        assertEquals("That didn't work: no sound.", Words.eightD(com.arnavagarwal.sound.engine.EightDMaker.State.Failed("no sound")))
        assertEquals(Regex("KARAOKE_PICK = \"([^\"]*)\"").find(src)!!.groupValues[1], Words.EIGHTD_PICK)
    }

    /** Warmth: the laptop's words, read from the desktop source, and the filter they name is really at 300 Hz. */
    @Test fun warmthAboutIsTheLaptopsWordForWord() {
        val src = File("../desktop/src/main/kotlin/com/arnavagarwal/sound/desktop/Words.kt").readText()
        val decl = src.substringAfter("const val ABOUT_WARMTH =").substringBefore("/**")
        val laptop = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(decl).joinToString("") { it.groupValues[1] }
        assertTrue(laptop.length > 60, "read only '${laptop.take(40)}' from the desktop file: the check is not reading it")
        assertEquals(laptop, Words.ABOUT_WARMTH)
        val tone = File("../core/src/main/kotlin/com/arnavagarwal/sound/core/Presets.kt").readText()
        assertTrue("ParametricFilter(PEAK, 300.0, warmth" in tone, "the warmth filter moved: '300 Hz' in the words is now false")
    }

    /** Quiet listening: the laptop's opening sentence and its Off line, read from the desktop source; the cap is the phone's. */
    @Test fun quietWordsStartAsTheLaptopsAndQuoteThePhonesCap() {
        val src = File("../desktop/src/main/kotlin/com/arnavagarwal/sound/desktop/Words.kt").readText()
        val decl = src.substringAfter("val ABOUT_QUIET =").substringBefore("const val ABOUT_STUDY")
        val laptop = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(decl).joinToString("") { it.groupValues[1] }
        val first = laptop.substringBefore("thin.") + "thin."
        assertTrue(first.length > 60, "read '${first.take(40)}': the check is not reading the desktop file")
        assertTrue(Words.ABOUT_QUIET.startsWith(first))
        val off = Regex("!on -> \"([^\"]*)\"").findAll(src.substringAfter("fun quiet(")).first().groupValues[1]
        assertEquals(off, Words.quiet(false))
        assertTrue("up to ${com.arnavagarwal.sound.core.EqualLoudness.MAX_BOOST_DB.toInt()} dB" in Words.ABOUT_QUIET)
    }

    /** The phone's Volume words are the laptop's with only "the Windows volume" changed: read, never retyped. */
    @Test fun volumeWordsAreTheLaptopsWithThePhonesVolume() {
        val src = File("../desktop/src/main/kotlin/com/arnavagarwal/sound/desktop/Words.kt").readText()
        val decl = src.substringAfter("const val ABOUT_VOLUME =").substringBefore("val ABOUT_QUIET")
        val button = Regex("const val VOLUME_BUTTON = \"([^\"]*)\"").find(src)!!.groupValues[1]
        val laptop = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(decl).joinToString("") { it.groupValues[1] }
            .replace("\\\"", "\"").replace("\$VOLUME_BUTTON", button)
        assertTrue(laptop.length > 100 && "Windows" in laptop, "read '${laptop.take(40)}': the check is not reading the desktop file")
        assertEquals(laptop.replace("the Windows volume", "the phone's volume"), Words.ABOUT_VOLUME)
        assertEquals(button, Words.VOLUME_BUTTON)
        // the status line: same words as the laptop's for each verdict
        val dFun = src.substringAfter("fun volume(").substringBefore("/** The low-volume fix card")
        val lines = listOf("This volume suits this song.", "This song was made quieter than most. Turn it up ",
            "This song is louder than you usually listen. Turn it ", "Learning the volume you like. Ready after ")
        for (l in lines) assertTrue(l in dFun, "the laptop no longer says '$l'")
        assertEquals("This volume suits this song.", Words.volume(VolumeCoach.Advice(VolumeCoach.Verdict.GOOD, -20.0, 0.5), 5, true))
        assertTrue(Words.volume(VolumeCoach.Advice(VolumeCoach.Verdict.UP, -10.0, 7.0), 5, true).startsWith(lines[1] + "quite a bit"))
        assertTrue(Words.volume(VolumeCoach.Advice(VolumeCoach.Verdict.DOWN, -30.0, -3.0), 5, true).startsWith(lines[2] + "down a little"))
        assertTrue(Words.volume(null, 1, true).startsWith(lines[3] + "2 more songs"))
    }

    @Test fun densityStatusSaysWhyItIsNotPlaying() {
        assertTrue(Words.density(on = false, live = false, speaker = true).startsWith("Off."))
        assertEquals("On, but it only plays on the phone's own speaker.", Words.density(on = true, live = false, speaker = false))
        assertTrue(Words.density(on = true, live = false, speaker = true).startsWith("On. It starts"))
        assertTrue(Words.density(on = true, live = true, speaker = true, db = 1.5, capDb = 4.0).startsWith("Auto: +1.5 dB for this song"))
        assertTrue("up to +4.0" in Words.density(on = true, live = true, speaker = true, db = 1.5, capDb = 4.0))
        assertTrue(Words.density(on = true, live = true, speaker = true, db = 0.0, capDb = 0.0).startsWith("Auto: none for this kind"))
    }

    /** RULES.md 22 Sep: no brand is ever used as an EXAMPLE on screen - only the app actually playing is named. */
    @Test fun screenTextNamesNoBrandAsAnExample() {
        val files = listOf("ui/MainActivity.kt", "ui/Setup.kt", "engine/EngineService.kt")
        val strings = files.flatMap { f ->
            val src = File("src/main/java/com/arnavagarwal/sound/$f").readText()
            Regex("\"([^\"\\\\]|\\\\.)*\"").findAll(src).map { it.value.lowercase() }.toList()
                .also { assertTrue(it.size > 8, "found only ${it.size} strings in $f: the check is not reading it") }
        }
        for (brand in listOf("spotify", "youtube", "jiosaavn", "gaana", "wynk", "apple music", "amazon")) {
            assertFalse(strings.any { brand in it }, "screen text names $brand")
        }
    }

    /** The green tick has the laptop's title, and both screens actually draw it (his ask, 8 Oct). */
    /** The licence line (PolyForm Noncommercial, his words 8 Oct) says the same on both apps, and the text it names ships. */
    @Test fun licenceLineIsTheSameOnBothAppsAndItsTextShips() {
        val src = File("../desktop/src/main/kotlin/com/arnavagarwal/sound/desktop/Words.kt").readText()
        val laptop = src.substringAfter("const val OWNER = ").substringBefore("const val CLOSE_NOTE")
            .let { Regex("\"([^\"]*)\"").findAll(it).joinToString("") { m -> m.groupValues[1] } }.replace("\\'", "'")
        assertEquals(Words.TRUST_OWNER, laptop)
        assertTrue("PolyForm Noncommercial License 1.0.0" in Words.TRUST_OWNER && "Arnav Agarwal" in Words.TRUST_OWNER)
        val lic = File("src/main/assets/licenses/polyform-noncommercial-1.0.0.txt").readText()
        assertTrue("# PolyForm Noncommercial License 1.0.0" in lic && "Noncommercial Purposes" in lic)
        val activity = File("src/main/java/com/arnavagarwal/sound/ui/MainActivity.kt").readText()
        assertTrue("\"polyform-noncommercial-1.0.0.txt\"" in activity && "Hint(Words.TRUST_OWNER)" in activity)
        assertTrue("Text(Words.OWNER" in File("../desktop/src/main/kotlin/com/arnavagarwal/sound/desktop/Main.kt").readText())
    }

    @Test fun greenTickIsOnBothHomes() {
        val src = File("../desktop/src/main/kotlin/com/arnavagarwal/sound/desktop/Words.kt").readText()
        assertEquals(Regex("""const val SAFE_TITLE = "([^"]+)"""").find(src)!!.groupValues[1], Setup.SAFE_TITLE)
        val laptopHome = File("../desktop/src/main/kotlin/com/arnavagarwal/sound/desktop/Main.kt").readText()
            .substringAfter("fun Home(").substringBefore("BigSwitch(st.tuningOn)")
        assertTrue("SafetyBadge()" in laptopHome, "the laptop home does not draw the green tick")
        val home = File("src/main/java/com/arnavagarwal/sound/ui/MainActivity.kt").readText()
            .substringAfter("private fun Home(").substringBefore("private fun ClarityBar(")
        assertTrue("SafetyBadge()" in home, "the phone home does not draw the green tick")
    }

    /** The big on/off button says what the laptop's says, both states, read from the desktop source. */
    @Test fun bigSwitchWordsAreTheLaptops() {
        val src = File("../desktop/src/main/kotlin/com/arnavagarwal/sound/desktop/Words.kt").readText()
        fun pair(fn: String) = Regex("""fun $fn\(on: Boolean\) = if \(on\) "([^"]+)" else "([^"]+)"""").find(src)!!.groupValues
        val t = pair("switchTitle"); val act = pair("switchAction")
        assertEquals(listOf(t[1], t[2]), listOf(Words.switchTitle(true), Words.switchTitle(false)))
        assertEquals(listOf(act[1], act[2]), listOf(Words.switchAction(true), Words.switchAction(false)))
        val home = File("src/main/java/com/arnavagarwal/sound/ui/MainActivity.kt").readText()
            .substringAfter("private fun Home(").substringBefore("private fun ClarityBar(")
        assertTrue("BigSwitch(engineOn)" in home, "the phone home has no big on/off button")
    }

    /** "Same to same" (29 Sep): the phone's home cards come in the laptop's order. The laptop's list is read from its
     *  screen source; only the two it can do alone (Demucs on a GPU) are left out, by name. */
    @Test fun homeCardsComeInTheLaptopsOrder() {
        val desk = File("../desktop/src/main/kotlin/com/arnavagarwal/sound/desktop/Main.kt").readText()
        val laptop = Regex("""Text\("([^"]+)", color = a\.ink, fontSize = 17\.sp""").findAll(desk).map { it.groupValues[1] }.toList()
        assertTrue(laptop.size >= 8, "read only $laptop from the desktop screen: the check is not reading it")
        val shared = laptop - setOf("Karaoke & music only", "Clean a talk")
        assertEquals(laptop.size - 2, shared.size, "a laptop-only card was renamed: $laptop")
        val phone = File("src/main/java/com/arnavagarwal/sound/ui/MainActivity.kt").readText()
            .substringAfter("private fun Home(").substringBefore("private fun ClarityBar(")
        val at = shared.map { t -> t to phone.indexOf("(\"$t\"") }
        at.forEach { (t, i) -> assertTrue(i >= 0, "the phone home has no \"$t\" card") }
        assertEquals(shared, at.sortedBy { it.second }.map { it.first }, "phone home order differs from the laptop's")
    }

    /** Strong mode's words say what the plan does (core StrongMode / Mixer): louder on the speaker and in the ears (9 Oct). */
    @Test fun strongModeWordsMatchWhatItDoes() {
        val steps = com.arnavagarwal.sound.core.StrongMode.steps
        assertEquals(3, steps.map { Words.strongStep(it) }.toSet().size, "the three steps need three different names")
        for (step in steps) {
            assertTrue("louder on the phone's speaker" in Words.strong(true, step, speaker = true))
            assertTrue("louder in your earbuds" in Words.strong(true, step, speaker = false))
            assertTrue("does not make the sound louder" !in Words.strong(true, step, speaker = false))
        }
        assertTrue(Words.strong(false, com.arnavagarwal.sound.core.StrongMode.BOLD, true).startsWith("Off."))
        val home = File("src/main/java/com/arnavagarwal/sound/ui/MainActivity.kt").readText()
            .substringAfter("private fun Home(").substringBefore("private fun ClarityBar(")
        assertTrue("Toggle(\"Strong mode\"" in home, "the phone home has no Strong mode switch")
    }
}
