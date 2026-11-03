package com.arnavagarwal.sound.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Classifier, taste learner, hearing dose, mixer, 8D and the challenge. */
class BrainTest {
    private val speechSound = SpectralFeatures(0.05, 1_400.0, 0.2, 0.7, 0.35, -24.0)
    private val bassSound = SpectralFeatures(0.55, 400.0, 0.2, 0.15, 0.0, -10.0)
    private val smoothSound = SpectralFeatures(0.18, 900.0, 0.08, 0.2, 0.05, -18.0)

    // ---- classifier ----

    @Test fun lectureOnYouTubeIsSpeech() {
        val p = ContentClassifier.classify(NowPlaying("com.google.android.youtube", "Cardiac cycle - Physiology lecture 12"), null)
        assertEquals(ContentKind.SPEECH, p.top)
    }

    @Test fun unpluggedSongOnSpotifyIsSmooth() {
        val p = ContentClassifier.classify(NowPlaying("com.spotify.music", "Zara Zara (Unplugged)", "Bombay Jayashri"), null)
        assertEquals(ContentKind.SMOOTH, p.top)
    }

    @Test fun soundAloneSeparatesSpeechFromBassMusic() {
        assertEquals(ContentKind.SPEECH, ContentClassifier.classify(null, speechSound).top)
        assertEquals(ContentKind.BASS_HEAVY, ContentClassifier.classify(null, bassSound).top)
    }

    @Test fun keywordsMatchWholeWordsOnly() {
        // "trap" must not fire on "Trapped", "class" must not make "Classical" a lecture
        val p1 = ContentClassifier.classify(NowPlaying(title = "Classical guitar"), null)
        assertEquals(ContentKind.CLASSICAL, p1.top)
        val p2 = ContentClassifier.classify(NowPlaying(title = "Trapped in grapes"), null)
        assertNotEquals(ContentKind.BASS_HEAVY, p2.top)
    }

    @Test fun strongSoundEvidenceOverridesAWeakAppHint() {
        // a talk played in a music app is still speech
        val p = ContentClassifier.classify(NowPlaying("com.spotify.music", "Episode 42"), speechSound)
        assertEquals(ContentKind.SPEECH, p.top)
    }

    @Test fun probabilitiesSumToOne() {
        val p = ContentClassifier.classify(NowPlaying("com.spotify.music", "Brown Munde"), bassSound)
        assertEquals(1.0, p.probs.values.sum(), 1e-9)
        assertTrue(ContentClassifier.certainty(p) in 0.0..1.0)
    }

    @Test fun songKeyIgnoresDecorationButNotRealLetters() {
        assertEquals(NowPlaying(title = "Zara Zara (Official Video)", artist = "X").songKey, NowPlaying(title = "Zara Zara", artist = "X").songKey)
        assertEquals("shades of blue|", NowPlaying(title = "Shades of Blue").songKey) // "hd" inside a word survives
        assertNull(NowPlaying(title = "  ").songKey)
    }

    @Test fun envelopeModulationSeesSyllableRhythm() {
        val fr = 20.0
        val syll = (0 until 80).map { t -> 20 * log10(0.55 + 0.45 * sin(2 * PI * 4.0 * t / fr)) }
        val slow = (0 until 80).map { t -> 20 * log10(0.55 + 0.45 * sin(2 * PI * 0.5 * t / fr)) }
        assertTrue(SpectralFeatures.envelopeModulation(syll, fr) > 0.8)
        assertTrue(SpectralFeatures.envelopeModulation(slow, fr) < 0.2)
    }

    @Test fun silenceIsNotEvidence() {
        val silent = List(40) { Spectrum(DoubleArray(Grid.size)) }
        assertNull(SpectralFeatures.from(silent, List(40) { -90.0 }, 20.0))
        val loud = List(40) { Spectrum.PINK }
        assertNotNull(SpectralFeatures.from(loud, List(40) { -20.0 }, 20.0))
        assertNull(SpectralFeatures.from(loud.take(10), List(10) { -20.0 }, 20.0)) // too short to judge
    }

    // ---- taste learner ----

    private fun profile(kind: ContentKind) = ContentProfile(ContentKind.entries.associateWith { if (it == kind) 0.9 else 0.1 / 6 }, "test")

    @Test fun learnsBassUpForBassSongsAndDownForSmoothOnes() {
        val tl = TasteLearner()
        repeat(5) { tl.record(profile(ContentKind.BASS_HEAVY), null, Tone(bass = 4.0), it.toLong()) }
        repeat(5) { tl.record(profile(ContentKind.SMOOTH), null, Tone(bass = -3.0), it.toLong()) }
        assertTrue(tl.predict(profile(ContentKind.BASS_HEAVY), null).tone.bass > 2.5)
        assertTrue(tl.predict(profile(ContentKind.SMOOTH), null).tone.bass < -1.8)
    }

    @Test fun oneTweakIsNotALaw() {
        val tl = TasteLearner()
        assertEquals(Tone.ZERO, tl.predict(profile(ContentKind.SMOOTH), null).tone)
        tl.record(profile(ContentKind.SMOOTH), null, Tone(bass = -6.0), 0)
        val b = tl.predict(profile(ContentKind.SMOOTH), null).tone.bass
        assertTrue(b < 0 && b > -6.0, "single sample must be shrunk, got $b")
    }

    @Test fun thisSongsMemoryWins() {
        val tl = TasteLearner()
        repeat(8) { tl.record(profile(ContentKind.VOCAL_POP), null, Tone(bass = 3.0), it.toLong()) }
        tl.record(profile(ContentKind.VOCAL_POP), "akhiyaan gulaab|mitraz", Tone(bass = -4.0), 99)
        val p = tl.predict(profile(ContentKind.VOCAL_POP), "akhiyaan gulaab|mitraz")
        assertTrue(p.fromSongMemory)
        assertTrue(p.tone.bass < -1.0, "song memory should dominate, got ${p.tone.bass}")
    }

    @Test fun capacityHoldsAtTenTimesTheLimitAndKeepsSongMemories() {
        val tl = TasteLearner(maxSamples = 50)
        repeat(20) { tl.record(profile(ContentKind.SMOOTH), "song$it|a", Tone(bass = -1.0), it.toLong()) }
        repeat(500) { tl.record(profile(ContentKind.BASS_HEAVY), null, Tone(bass = 2.0), it.toLong()) }
        assertEquals(50, tl.size)
        assertEquals(20, tl.all().count { it.songKey != null })
    }

    // ---- hearing dose ----

    @Test fun whoAllowanceIs80dBFor40Hours() {
        assertEquals(1.6, SoundDose.dose(80.0, 40.0), 1e-9)
        assertEquals(40.0, SoundDose.hoursLeft(80.0, 1.6), 1e-6)
        assertEquals(20.0, SoundDose.hoursLeft(83.0103, 1.6), 0.01) // +3 dB halves the time
    }

    @Test fun ledgerForgetsAfterSevenDays() {
        val l = DoseLedger()
        val day = 86_400_000L
        l.add(0, 80.0, 3600.0 * 20)
        assertEquals(0.5, l.weekFraction(day), 1e-9)
        assertEquals(0.0, l.weekFraction(8 * day), 1e-9)
    }

    private val volumeSteps = DoubleArray(16) { i -> if (i == 0) -96.0 else -60.0 + 60.0 * i / 15 }

    @Test fun speechGetsAQuieterRecommendationThanBassMusic() {
        val s = VolumeAdvisor.advise(ContentKind.SPEECH, -20.0, volumeSteps, 10, 0.0)
        val b = VolumeAdvisor.advise(ContentKind.BASS_HEAVY, -20.0, volumeSteps, 10, 0.0)
        assertTrue(s.bestIndex < b.bestIndex)
        assertTrue(s.bestPercent in 1..100)
    }

    @Test fun tooLoudGetsTold_andBudgetLowersTheTarget() {
        val loud = VolumeAdvisor.advise(ContentKind.VOCAL_POP, -10.0, volumeSteps, 15, 0.0)
        assertTrue(loud.message.contains("louder than needed"), loud.message)
        val fresh = VolumeAdvisor.advise(ContentKind.VOCAL_POP, -20.0, volumeSteps, 8, 0.1)
        val used = VolumeAdvisor.advise(ContentKind.VOCAL_POP, -20.0, volumeSteps, 8, 0.85)
        assertTrue(used.targetDb < fresh.targetDb)
        assertTrue(used.bestIndex <= fresh.bestIndex)
    }

    // ---- mixer, glide, 8D ----

    @Test fun mixerClampsAndLeavesHeadroom() {
        val sw = UserSwitches(manualTone = Tone(bass = 12.0, treble = 12.0), intensity = 1.5)
        val plan = Mixer.plan(profile(ContentKind.BASS_HEAVY), null, sw, null, null)
        assertTrue(plan.curve.max() <= Mixer.MAX_BOOST + 1e-9)
        assertTrue(plan.preampDb <= 0)
        assertEquals(0.0, Loudness.changeDb(plan.curve) + plan.preampDb, 1e-9) // equal loudness
        assertEquals(DEFAULT_BAND_CUTOFFS.size, plan.bandGainsDb.size)
    }

    // his phone test, 22 Sep: Bass fully right "just sounds quieter". The old preamp (-0.75 x the largest boost)
    // left the bass only ~+2 dB up and pulled everything else ~6 dB down.
    @Test fun bassBoostRaisesTheBassWithoutLoweringTheSong() {
        val sw = UserSwitches(autoEnabled = false, manualTone = Tone(bass = 8.0))
        val plan = Mixer.plan(profile(ContentKind.BASS_HEAVY), null, sw, null, null)
        val bassNet = plan.curve.at(50.0) + plan.preampDb
        val midNet = plan.curve.at(1_000.0) + plan.preampDb
        assertTrue(bassNet >= 6.0, "bass only up $bassNet dB")
        assertTrue(midNet >= -2.0, "rest of the song down $midNet dB")
        assertEquals(0.0, Loudness.changeDb(plan.curve) + plan.preampDb, 0.01)
    }

    @Test fun preampNeverBoostsWhenTheToneOnlyCuts() {
        val sw = UserSwitches(autoEnabled = false, manualTone = Tone(bass = -6.0, treble = -6.0))
        assertEquals(0.0, Mixer.plan(profile(ContentKind.SMOOTH), null, sw, null, null).preampDb)
    }

    @Test fun toneFitRecoversKnobSettings() {
        val t = Tone(bass = 3.0, warmth = -2.0, clarity = 1.5, treble = 4.0)
        val f = Tone.fit(t.curve())
        assertEquals(t.bass, f.bass, 0.3); assertEquals(t.warmth, f.warmth, 0.3)
        assertEquals(t.clarity, f.clarity, 0.3); assertEquals(t.treble, f.treble, 0.3)
        assertEquals(Tone.ZERO, Tone.fit(Curve.FLAT))
    }

    @Test fun appToneShowsWhatTheAppChoseAndNotTheKnobs() {
        val plain = Mixer.plan(profile(ContentKind.BASS_HEAVY), null, UserSwitches(), null, null).appTone
        assertTrue(plain.bass > 1.0, "a bass-heavy song should show the app adding bass, got $plain")
        val knobbed = Mixer.plan(profile(ContentKind.BASS_HEAVY), null, UserSwitches(manualTone = Tone(bass = 5.0)), null, null).appTone
        assertEquals(plain, knobbed)
        assertEquals(Tone.ZERO, Mixer.plan(profile(ContentKind.BASS_HEAVY), null, UserSwitches(autoEnabled = false), null, null).appTone)
    }

    @Test fun eightDNeverRunsOnSpeechAndNightModeCompresses() {
        val sw = UserSwitches(eightD = true, nightMode = true)
        assertFalse(Mixer.plan(profile(ContentKind.SPEECH), null, sw, null, null).allow8D)
        assertTrue(Mixer.plan(profile(ContentKind.BASS_HEAVY), null, sw, null, null).allow8D)
        assertEquals(Dynamics.NIGHT, Mixer.plan(profile(ContentKind.VOCAL_POP), null, sw, null, null).dynamics)
    }

    @Test fun everythingOffMeansFlat() {
        val sw = UserSwitches(autoEnabled = false, learnEnabled = false, correctionEnabled = false, loudnessCompEnabled = false)
        val plan = Mixer.plan(profile(ContentKind.BASS_HEAVY), null, sw, Curve.FLAT.plusConst(3.0), 30.0)
        assertTrue(plan.curve.approxEquals(Curve.FLAT, 1e-9))
        assertEquals(Dynamics.OFF, plan.dynamics)
    }

    @Test fun glideGetsThereSmoothly() {
        val g = Glide(1, tauSec = 0.5)
        g.step(doubleArrayOf(0.0), 0.02)
        var v = 0.0; var t = 0.0; var prev = 0.0
        while (t < 1.5 - 1e-9) { v = g.step(doubleArrayOf(10.0), 0.02)[0]; assertTrue(v >= prev); prev = v; t += 0.02 }
        assertEquals(10.0 * (1 - Math.exp(-3.0)), v, 0.05) // 3 tau -> 95%
    }

    @Test fun eightDKeepsLoudnessConstantWhileMoving() {
        val (l0, r0) = EightD.gainsDb(0.0, 8.0)
        assertEquals(0.0, l0, 1e-6); assertEquals(0.0, r0, 1e-6)
        for (t in listOf(0.7, 2.0, 3.3, 6.1)) {
            val (l, r) = EightD.gainsDb(t, 8.0)
            assertEquals(2.0, 10.0.pow(l / 10) + 10.0.pow(r / 10), 1e-6)
        }
        val (lq, rq) = EightD.gainsDb(2.0, 8.0) // quarter period: far right
        assertTrue(rq > lq + 6)
    }

    // ---- challenge ----

    @Test fun binomialTailIsRight() {
        assertEquals(1.0 / 64, Challenge.binomialTailAtLeast(6, 6), 1e-12)
        assertEquals(7.0 / 64, Challenge.binomialTailAtLeast(5, 6), 1e-12)
        assertEquals(1.0, Challenge.binomialTailAtLeast(0, 6), 1e-12)
    }

    @Test fun challengeReportsAClearWinAndAnHonestLoss() {
        val win = Challenge(6, seed = 1)
        repeat(6) { win.heard(true); win.heard(false); win.pick(pickedA = win.enhancedIsA[win.round]) }
        assertEquals(Challenge.Verdict.CLEAR_WIN, win.result().verdict)
        assertEquals(6, win.result().enhancedWins)

        val loss = Challenge(6, seed = 2)
        repeat(6) { loss.heard(false); loss.heard(true); loss.pick(pickedA = !loss.enhancedIsA[loss.round]) }
        val r = loss.result()
        assertEquals(Challenge.Verdict.PREFERS_ORIGINAL, r.verdict)
        assertTrue(r.message.contains("6 of 6"), r.message)
    }

    @Test fun challengeHidesWhichIsWhichAtRandom() {
        val c = Challenge(20, seed = 7)
        val aCount = c.enhancedIsA.count { it }
        assertTrue(aCount in 4..16, "order should be mixed, got $aCount of 20 as A")
        assertTrue(abs(Challenge(20, seed = 7).enhancedIsA.count { it } - aCount) == 0) // reproducible with a seed
    }
}
