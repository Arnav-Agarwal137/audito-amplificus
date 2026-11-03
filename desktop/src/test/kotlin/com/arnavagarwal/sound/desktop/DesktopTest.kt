package com.arnavagarwal.sound.desktop

import com.arnavagarwal.sound.core.Curve
import com.arnavagarwal.sound.core.FilterType.PEAK
import com.arnavagarwal.sound.core.Grid
import com.arnavagarwal.sound.core.ParametricFilter
import com.arnavagarwal.sound.core.Spectrum
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopTest {
    // ---------------------------------------------------------------- the helper's messages

    @Test fun parsesEveryMessageTheHelperSends() {
        val d = assertIs<HelperEvent.Device>(HelperParse.line("""{"t":"dev","id":"x","name":"Speakers (Realtek(R) Audio)","form":"speakers","bt":false,"apo":true}"""))
        assertTrue(d.isSpeaker && d.apo && !d.isHeadphones)
        val b = assertIs<HelperEvent.Device>(HelperParse.line("""{"t":"dev","id":"y","name":"Nord Buds","form":"headphones","bt":true,"apo":false}"""))
        assertTrue(b.isHeadphones && !b.isSpeaker && !b.apo)
        val np = assertIs<HelperEvent.Playing>(HelperParse.line("""{"t":"np","app":"chrome","title":"Killie Killie","artist":"","album":null,"genres":["Malayalam"],"playing":true}"""))
        assertEquals("Killie Killie", np.title); assertNull(np.artist); assertEquals("Malayalam", np.genre); assertTrue(np.playing)
        val f = assertIs<HelperEvent.Frame>(HelperParse.line("""{"t":"fft","fs":48000,"rms":-20.5,"peak":0.5,"m":[0,1,2,3,4]}"""))
        assertEquals(5, f.mags!!.size); assertEquals(-20.5, f.rmsDbfs)
        val l = assertIs<HelperEvent.Frame>(HelperParse.line("""{"t":"lvl","rms":-96,"peak":0}"""))
        assertNull(l.mags)
        assertIs<HelperEvent.Problem>(HelperParse.line("""{"t":"err","where":"loopback","msg":"gone"}"""))
    }

    @Test fun anythingElseIsIgnoredNotThrown() {
        for (s in listOf("", "not json", "{}", """{"t":"new-kind"}""", """{"t":"fft"}""", "[1,2]")) assertNull(HelperParse.line(s), s)
    }

    // ---------------------------------------------------------------- Equalizer APO's files (a temp folder, never the real one)

    private fun tempApo(existing: String?): Apo {
        val dir = Files.createTempDirectory("apo").toFile().apply { deleteOnExit() }
        existing?.let { dir.resolve("config.txt").writeText(it) }
        return Apo(dir)
    }

    @Test fun takeOverKeepsTheOldConfigOnceAndIncludesOurs() {
        val a = tempApo("Preamp: -6 dB\r\nFilter: ON PK Fc 100 Hz Gain 3 dB Q 1\r\n")
        a.takeOver()
        assertTrue("Include: sound-app.txt" in a.main.readText())
        val backup = a.dir.resolve("config.before-sound-app.txt")
        assertTrue(backup.readText().startsWith("Preamp: -6 dB"))
        a.takeOver() // second run must not overwrite the backup with our own file
        assertTrue(backup.readText().startsWith("Preamp: -6 dB"))
    }

    @Test fun neutralIsNeutralAndWritesOnlyOnChange() {
        val a = tempApo(null)
        a.takeOver()
        assertTrue(a.neutral()); assertEquals("Preamp: 0 dB\r\n", a.ours.readText())
        assertFalse(a.neutral(), "unchanged text must not be rewritten (each write makes Equalizer APO reload)")
        val c = Curve.of(ParametricFilter(PEAK, 350.0, 8.0, 1.0))
        assertTrue(a.write(c, -3.8)); assertFalse(a.write(c, -3.8))
        val text = a.ours.readText()
        assertTrue(text.startsWith("Preamp: -3.8 dB\r\nGraphicEQ: 20.0 "), text.take(40))
        assertEquals(Grid.size, text.substringAfter("GraphicEQ: ").trim().split("; ").size)
        assertTrue(a.neutral()); assertEquals(Apo.NEUTRAL, a.ours.readText())
    }

    /** E-015: the preview window left the tuning ON while a song played. Look-only must not be able to write at all. */
    @Test fun lookOnlyNeverTouchesEqualizerApoFiles() {
        val dir = Files.createTempDirectory("apo").toFile().apply { deleteOnExit() }
        val before = "Preamp: -6 dB\r\n"
        dir.resolve("config.txt").writeText(before)
        val a = Apo(dir, lookOnly = true)
        a.takeOver()
        assertFalse(a.write(Curve.of(ParametricFilter(PEAK, 350.0, 8.0, 1.0)), -3.8))
        assertFalse(a.neutral())
        assertEquals(before, a.main.readText(), "config.txt changed in look-only mode")
        assertFalse(a.ours.exists(), "sound-app.txt was written in look-only mode")
        assertFalse(dir.resolve("config.before-sound-app.txt").exists())
    }

    /** 23 Sep ~20:55 "take the same setting and apply": at Normal the text IS his liked spike file, read from that file. */
    @Test fun laptopSettingIsTheFileHeLiked() {
        val liked = java.io.File("../tools/windows-spike/strong-liked.txt").readText().lines().filter { it.isNotBlank() }
        val ours = LaptopSetting.text(1.0, 0.0, null).lines().filter { it.isNotBlank() }
        assertEquals(liked, ours)
        // strength and ear answers move it; knobs add a GraphicEQ line on top
        assertTrue(LaptopSetting.text(1.4, 0.0, null).contains("Gain 11.2 dB"))
        assertTrue(LaptopSetting.text(1.0, -1.0, null).startsWith("Preamp: -4.8 dB"))
        assertTrue("GraphicEQ:" in LaptopSetting.text(1.0, 0.0, Curve.of(ParametricFilter(PEAK, 100.0, 3.0, 1.0))))
    }

    @Test fun parsesTheVolumeAndTheStereoReading() {
        val v = assertIs<HelperEvent.Volume>(HelperParse.line("""{"t":"vol","db":-7.14,"s":0.62,"mute":false,"min":-65.25,"max":0}"""))
        assertEquals(-7.14, v.db); assertEquals(0.62, v.scalar); assertFalse(v.mute); assertEquals(-65.25, v.minDb)
        val f = assertIs<HelperEvent.Frame>(HelperParse.line("""{"t":"fft","fs":48000,"rms":-20.5,"peak":0.5,"sd":-6.1,"m":[0,1]}"""))
        assertEquals(-6.1, f.sideDb)
        assertNull(assertIs<HelperEvent.Frame>(HelperParse.line("""{"t":"lvl","rms":-96,"peak":0,"sd":null}""")).sideDb)
        assertNull(HelperParse.line("""{"t":"vol","s":0.5}"""), "a volume without its dB is not a reading")
    }

    /** Width = Equalizer APO Copy lines. Read the coefficients back OUT of the text and check what they do to L and R. */
    @Test fun widthTextWidensTheSideAndLeavesTheMiddle() {
        assertFalse("Copy" in LaptopSetting.text(1.0, 0.0, null, width = 1.0), "no width = the liked file, untouched")
        val t = LaptopSetting.text(1.0, 0.0, null, width = 1.5)
        assertTrue(t.startsWith("Preamp: -3.8 dB"))
        val lines = t.lines()
        assertEquals("Copy: WIDE=0.5*L+-0.5*R", lines.first { it.startsWith("Copy: WIDE") })
        val hp = lines.indexOfFirst { it.startsWith("Filter: ON HP Fc 250") }
        assertTrue(lines[hp - 1] == "Channel: WIDE" && lines[hp + 1] == "Channel: all", "the high-pass must touch only the side copy")
        val back = lines.last { it.startsWith("Copy: L=") }
        val k = Regex("""L=L\+([0-9.]+)\*WIDE R=R\+-([0-9.]+)\*WIDE""").find(back)!!.groupValues
        val kl = k[1].toDouble(); val kr = k[2].toDouble()
        // L' = L + k*(L-R)/2, R' = R - k*(L-R)/2, for a high note where the high-pass passes the side fully
        val l = 0.8; val r = 0.2
        val s = (l - r) / 2
        val l2 = l + kl * s; val r2 = r - kr * s
        assertEquals((l + r) / 2, (l2 + r2) / 2, 1e-9, "the middle (what the helper analyses) must not move")
        assertEquals(1.5 * s, (l2 - r2) / 2, 1e-9, "the side must be 1.5x")
    }

    /** The learned gentler middle: Study music's own shelves, on the whole song (no channel copies), nothing at 0. */
    @Test fun softMiddleIsTheStudyShelvesOnTheWholeSong() {
        assertEquals("", LaptopSetting.softMiddleText(0.0))
        val soft = LaptopSetting.softMiddleText(6.0).lines().filter { it.isNotBlank() }
        val study = LaptopSetting.voiceDownText(6.0).lines().filter { it.startsWith("Filter:") }
        assertEquals(study, soft, "the same two shelves he heard as Voice softer")
        assertTrue(soft.none { it.startsWith("Copy:") || it.startsWith("Channel:") }, "whole song: no middle copy")
    }

    /** Study music: the middle's voice band down, the side untouched, the song rebuilt exactly (read back OUT of the text). */
    @Test fun voiceDownLowersOnlyTheMiddlesVoiceBand() {
        assertEquals("", LaptopSetting.voiceDownText(0.0), "off = nothing added")
        val lines = LaptopSetting.voiceDownText(12.0).lines()
        val split = Regex("""Copy: VMID=([0-9.]+)\*L\+([0-9.]+)\*R VSIDE=([0-9.]+)\*L\+-([0-9.]+)\*R""").find(lines[0])!!.groupValues
        val back = lines.first { it.startsWith("Copy: L=") }
        assertEquals("Copy: L=VMID+VSIDE R=VMID+-1*VSIDE", back)
        val (ml, mr, sl, sr) = split.drop(1).map { it.toDouble() }
        val l = 0.8; val r = 0.2
        val m = ml * l + mr * r; val s = sl * l - sr * r
        assertEquals(l, m + s, 1e-9, "at 0 dB of cut, L comes back exactly"); assertEquals(r, m - s, 1e-9)
        val shelves = lines.filter { it.startsWith("Filter:") }
        assertEquals(2, shelves.size)
        assertEquals("Channel: VMID", lines[lines.indexOf(shelves[0]) - 1], "the shelves must touch only the middle copy")
        assertEquals("Channel: all", lines[lines.indexOf(shelves[1]) + 1])
        val c = LaptopSetting.voiceDownCurve(12.0)
        fun at(hz: Double) = c.db[com.arnavagarwal.sound.core.Grid.freqs.indices.minBy { kotlin.math.abs(com.arnavagarwal.sound.core.Grid.freqs[it] - hz) }]
        assertTrue(at(1000.0) < -10.0, "voice band down: ${at(1000.0)}")
        assertTrue(at(60.0) > -1.5, "bass kept: ${at(60.0)}")
        assertTrue(at(12000.0) > -2.0, "air back: ${at(12000.0)}")
    }

    /** Study music's lift, centre only: the true lifts measured on his recordings (lift_fix.log, 29 Sep). */
    @Test fun voiceLiftCountsOnlyTheMiddle() {
        assertEquals(2.75, LaptopSetting.centreOnlyLiftDb(2.75, null), 1e-9, "width unknown: whole-song price")
        assertEquals(5.0, LaptopSetting.centreOnlyLiftDb(5.0, -80.0), 0.01, "mono: the whole song IS the middle")
        assertEquals(2.29, LaptopSetting.centreOnlyLiftDb(2.75, -9.4), 0.15, "Husn at 6 dB")
        assertEquals(3.18, LaptopSetting.centreOnlyLiftDb(4.07, -9.5), 0.3, "beat at 9 dB")
        assertEquals(2.54, LaptopSetting.centreOnlyLiftDb(2.72, -14.4), 0.1, "Anuv Jain at 6 dB")
        assertTrue(LaptopSetting.centreOnlyLiftDb(3.0, 0.0) < 3.0 * 0.6, "half the power in the sides: much less lift")
    }

    /**
     * 29 Sep: the lift priced on the raw song gave ~0.5 dB live on Jo Tum against a true 3.08 dB. Truth = BS.1770 drop of
     * the centre-only cut AFTER the liked EQ on his plain recordings (tools/windows-spike/lift_order_check.py,
     * lift_order.log); spectra = the helper's own 2048-point FFT (spectrum_dump.py). Priced at Strong 1.4, the strength the truth
     * was measured at (it was 1.0 until 29 Sep afternoon, hidden by a 0.8 dB band). The FFT's coarse low end costs up
     * to ~0.45 dB of under-lift (the safe side); an over-lift beyond 0.3 dB would make "soft" louder than "off".
     */
    @Test fun voiceLiftMatchesTheRealLoudnessDrop() {
        val truth = mapOf("husn_a" to 2.88, "jotum_a" to 3.08, "anuv_a" to 3.38, "beat_a" to 3.56, "density_a" to 3.77)
        for ((clip, want) in truth) {
            val lines = javaClass.getResource("/lift/$clip.mags.txt")!!.readText().trim().lines()
            val (fs, side) = lines[0].split(" ").map { it.toDouble() }
            val song = Spectrum.fromFft(lines.drop(1).map { it.toDouble() }.toDoubleArray(), fs)
            val got = LaptopSetting.voiceLiftDb(6.0, song, 1.4, null, side)
            assertTrue(got in want - 0.5..want + 0.3, "$clip: lift $got, true drop $want")
            // un-colouring the loopback through the 120 Hz high-pass inflates the sub-bass: must not shrink the lift
            val curve = LaptopSetting.curve(1.4, null)
            val floor = song.power.max() * 1e-5
            val inflated = Spectrum(DoubleArray(Grid.size) { song.power[it] + floor / Math.pow(10.0, curve.db[it] / 10) })
            assertEquals(got, LaptopSetting.voiceLiftDb(6.0, inflated, 1.4, null, side), 0.2, "$clip: fake sub-bass moved the lift")
        }
        assertEquals(0.0, LaptopSetting.voiceLiftDb(0.0, Spectrum.PINK, 1.0, null, null), 1e-9, "off: no lift")
    }

    /**
     * Quiet listening must change the balance, not the level (29 Sep, E-025's same class). Truth = BS.1770 loudness the
     * quiet curve adds AFTER the liked EQ at Strong 1.4 on his plain recordings (tools/windows-spike/quiet_lift_check.py,
     * quiet_lift_check.log; curve printed by the app's own QuietText). Same helper spectra as the Voice softer test.
     */
    @Test fun quietMatchIsPricedAfterTheLikedSetting() {
        val truth = mapOf(
            "husn_a" to listOf(-0.26, -0.52, -0.79), "jotum_a" to listOf(-0.30, -0.59, -0.90),
            "anuv_a" to listOf(-0.27, -0.52, -0.79), "beat_a" to listOf(-0.12, -0.22, -0.33),
            "density_a" to listOf(-0.17, -0.34, -0.51))
        for ((clip, wants) in truth) {
            val lines = javaClass.getResource("/lift/$clip.mags.txt")!!.readText().trim().lines()
            val fs = lines[0].split(" ")[0].toDouble()
            val song = Spectrum.fromFft(lines.drop(1).map { it.toDouble() }.toDoubleArray(), fs)
            for ((drop, want) in listOf(4.0, 8.0, 12.0).zip(wants)) {
                val quiet = com.arnavagarwal.sound.core.QuietFix.curve(drop)
                val got = LaptopSetting.quietMatchDb(quiet, song, 1.4, null)
                assertEquals(want, got, 0.15, "$clip drop $drop")
            }
        }
        assertEquals(0.0, LaptopSetting.quietMatchDb(Curve.FLAT, Spectrum.PINK, 1.4, null), 1e-9, "off: no match")
    }

    /** Speaker-safe (29 Sep): nothing changes at or below volume 0.75 (-4.3 dB); above it the boosts ease, never below 0. */
    @Test fun speakerSafeEasesOnlyAboveTheKnee() {
        assertEquals(0.0, LaptopSetting.speakerBackOffDb(null), 1e-9)
        assertEquals(0.0, LaptopSetting.speakerBackOffDb(-7.63), 1e-9, "his usual 0.61: untouched")
        assertEquals(0.0, LaptopSetting.speakerBackOffDb(-4.3), 1e-9, "at the knee: untouched")
        assertEquals(2.72, LaptopSetting.speakerBackOffDb(-1.58), 1e-9, "0.90")
        assertEquals(4.3, LaptopSetting.speakerBackOffDb(0.0), 1e-9, "100%")
        assertEquals(0.0, LaptopSetting.speakerBackOffDb(0.0, muted = true), 1e-9, "muted")
        assertEquals(LaptopSetting.text(1.4, 0.0, null), LaptopSetting.text(1.4, 0.0, null, safeDb = 0.0), "no back-off = same file")
        val full = LaptopSetting.text(1.4, 0.0, null, safeDb = 4.3)
        assertTrue("PK Fc 350 Hz Gain 9 dB" in full && "PK Fc 3000 Hz Gain 6.2 dB" in full, full)
        assertTrue("HP Fc 120 Hz" in full, "the high-pass never eases")
        val flat = LaptopSetting.curve(0.1, null, 4.3).db
        assertTrue(flat.all { it <= 1e-9 }, "a boost smaller than the back-off stops at 0, never becomes a cut above 0")
    }

    /** Density (his ear test 24 Sep) = the liked file with ONLY the preamp raised by the lift the app chose. */
    @Test fun densityRaisesOnlyThePreamp() {
        val plain = LaptopSetting.text(1.0, 0.0, null).lines()
        val dense = LaptopSetting.text(1.0, 0.0, null, densityDb = 6.0).lines()
        assertEquals("Preamp: -3.8 dB", plain[0])
        assertEquals("Preamp: 2.2 dB", dense[0])
        assertEquals(plain.drop(1), dense.drop(1))
        assertEquals("Preamp: -1.3 dB", LaptopSetting.text(1.0, 0.0, null, densityDb = 2.5).lines()[0])
    }

    @Test fun densityCardSaysWhatTheAppChose() {
        assertTrue("+4.5 dB" in Words.density(on = true, live = true, tuningOn = true, db = 4.5, capDb = 6.0))
        assertTrue("none" in Words.density(on = true, live = true, tuningOn = true, db = 0.0, capDb = 0.0))
        assertTrue(Words.density(on = false, live = false, tuningOn = true).startsWith("Off"))
        // speaker-safe: silent at or below the knee, says both amounts above it, and "none" at high volume is not blamed on the music
        assertTrue("easing" !in Words.density(on = true, live = true, tuningOn = true, db = 2.0, capDb = 2.7, safeDb = 0.0))
        val high = Words.density(on = true, live = true, tuningOn = true, db = 1.0, capDb = 1.0, safeDb = 2.72)
        assertTrue("density by 2.7 dB" in high && "voice boosts by 1.4 dB" in high, high)
        val eaten = Words.density(on = true, live = true, tuningOn = true, db = 0.0, capDb = 0.0, safeDb = 4.3)
        assertTrue("none at this volume" in eaten && "kind of music" !in eaten, eaten)
        val off = Words.density(on = false, live = false, tuningOn = true, safeDb = 4.3)
        assertTrue(off.startsWith("Off") && "density by" !in off && "voice boosts by 2.2 dB" in off, off)
        assertTrue("tuning is off" in Words.density(on = true, live = false, tuningOn = false))
    }

    /** With density the limiter after APO holds peaks at 0 dBFS, so the clip guard must not fire at the ceiling. */
    @Test fun clipGuardWaitsForAPeakAboveTheCeilingOnlyWithDensity() {
        assertTrue(0.99 > Engine.clipPeak(false))
        assertTrue(0.99 < Engine.clipPeak(true) && 1.0 < Engine.clipPeak(true))
        assertTrue(1.05 > Engine.clipPeak(true))
    }

    @Test fun renderUsesDotsWhateverTheLocale() {
        val old = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY) // "3,8" would be read by Equalizer APO as garbage
            assertFalse(',' in Apo.render(Curve.FLAT.plusConst(1.25), -3.8).replace("; ", ""))
        } finally { java.util.Locale.setDefault(old) }
    }

    // ---------------------------------------------------------------- dividing out what Equalizer APO played

    private val song = Spectrum(DoubleArray(Grid.size) { 1.0 + it % 3 })

    @Test fun beforeAnyWriteTheFrameIsTheSong() {
        val (s, lv) = Applied().uncolour(song, -20.0, 1_000)
        assertTrue(s === song); assertEquals(-20.0, lv)
    }

    @Test fun aFrameHeardThroughOurCurveGivesBackTheSong() {
        val curve = Curve.of(ParametricFilter(PEAK, 350.0, 8.0, 1.0)).plusConst(-4.0)
        val heard = Spectrum(DoubleArray(Grid.size) { song.power[it] * Math.pow(10.0, curve.db[it] / 10) })
        val a = Applied(); a.record(1_000, curve.db)
        val (s, _) = a.uncolour(heard, -20.0, 1_500)
        for (i in 0 until Grid.size) assertEquals(song.power[i], s.power[i], 1e-9 * song.power[i])
        // a frame captured BEFORE the curve went live is left alone
        assertTrue(Applied().apply { record(2_000, curve.db) }.uncolour(heard, -20.0, 1_500).first === heard)
    }

    @Test fun theNewestLiveCurveWins() {
        val a = Applied()
        a.record(1_000, DoubleArray(Grid.size) { 3.0 })
        a.record(2_000, DoubleArray(Grid.size) { 6.0 })
        assertEquals(3.0, a.at(1_500)!![0]); assertEquals(6.0, a.at(2_500)!![0])
    }

    /** The phone's Study music (core StudyMusic) is the laptop's cut, not a look-alike: same shelves, same "Voice softer". */
    @Test fun phoneStudyMusicIsTheLaptopsCut() {
        for (db in listOf(3.0, 6.0, 12.0))
            assertTrue(com.arnavagarwal.sound.core.StudyMusic.curve(db).approxEquals(LaptopSetting.voiceDownCurve(db), 1e-9), "$db dB")
        assertEquals(Words.STUDY_STEPS.first { it.first == "Voice softer" }.second, com.arnavagarwal.sound.core.StudyMusic.SOFTER_DB)
        // "same to same" (his rule 29 Sep 19:59): the phone offers exactly the laptop's steps, labels and amounts
        assertEquals(Words.STUDY_STEPS, com.arnavagarwal.sound.core.StudyMusic.STEPS)
    }
}
