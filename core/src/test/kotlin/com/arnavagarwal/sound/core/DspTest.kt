package com.arnavagarwal.sound.core

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DspTest {
    private fun bq(t: FilterType, fc: Double, g: Double = 0.0, q: Double = 0.707) = Biquad.of(ParametricFilter(t, fc, g, q))

    @Test fun peakHitsItsGainExactlyAtFc() {
        for (fc in listOf(60.0, 1_000.0, 3_000.0, 9_000.0)) for (g in listOf(-6.0, 3.5, 9.0)) {
            assertEquals(g, bq(FilterType.PEAK, fc, g, 1.0).magnitudeDb(fc), 0.05, "fc=$fc g=$g")
        }
    }

    @Test fun peakIsFlatFarAway() {
        val p = bq(FilterType.PEAK, 1_000.0, 6.0, 2.0)
        assertTrue(abs(p.magnitudeDb(50.0)) < 0.1)
        assertTrue(abs(p.magnitudeDb(15_000.0)) < 0.2)
    }

    @Test fun shelvesReachTheirGainOnTheirSideOnly() {
        val ls = bq(FilterType.LOW_SHELF, 105.0, 6.0)
        assertEquals(6.0, ls.magnitudeDb(20.0), 0.5)
        assertEquals(3.0, ls.magnitudeDb(105.0), 0.3) // half gain at the corner
        assertTrue(abs(ls.magnitudeDb(5_000.0)) < 0.1)
        val hs = bq(FilterType.HIGH_SHELF, 8_000.0, -4.0)
        assertEquals(-4.0, hs.magnitudeDb(20_000.0), 0.6)
        assertTrue(abs(hs.magnitudeDb(200.0)) < 0.1)
    }

    @Test fun highPassIsMinus3AtCornerAnd40DownADecadeBelow() {
        val hp = bq(FilterType.HIGH_PASS, 80.0)
        assertEquals(-3.01, hp.magnitudeDb(80.0), 0.1)
        assertEquals(-40.0, hp.magnitudeDb(8.0), 1.0)
        assertTrue(abs(hp.magnitudeDb(2_000.0)) < 0.05)
    }

    @Test fun gridIsLogSpacedAcrossHearing() {
        assertEquals(20.0, Grid.freqs.first(), 1e-9)
        assertEquals(20_000.0, Grid.freqs.last(), 1e-6)
        val r = Grid.freqs[1] / Grid.freqs[0]
        assertTrue(Grid.freqs.toList().zipWithNext().all { (a, b) -> abs(b / a - r) < 1e-9 })
    }

    @Test fun bandsCarryTheCurveToTheRightPlace() {
        assertTrue(Curve.FLAT.toBands(DEFAULT_BAND_CUTOFFS).all { it == 0.0 })
        val c = Curve.of(ParametricFilter(FilterType.PEAK, 3_000.0, 6.0, 1.5))
        val bands = c.toBands(DEFAULT_BAND_CUTOFFS)
        assertEquals(DEFAULT_BAND_CUTOFFS.size, bands.size)
        val loudest = bands.indices.maxBy { bands[it] }
        // 3 kHz lives in the band 2800..4000
        assertEquals(DEFAULT_BAND_CUTOFFS.toList().indexOf(4_000.0), loudest)
        assertTrue(bands[loudest] > 4.0)
        assertTrue(abs(bands[0]) < 0.1)
    }

    @Test fun curveInterpolationMatchesDirectEvaluation() {
        val f = ParametricFilter(FilterType.LOW_SHELF, 150.0, 5.0)
        val c = Curve.of(f)
        for (hz in listOf(33.0, 150.0, 777.0, 4_321.0)) assertEquals(Biquad.of(f).magnitudeDb(hz), c.at(hz), 0.15, "at $hz")
    }

    // ---- loudness ----

    @Test fun kWeightingMatchesTheStandardsShape() {
        // BS.1770's -0.691 constant exists because K-weighting gains ~+0.69 dB at 1 kHz: an independent check of the coefficients
        val at1k = Curve(Loudness.kWeightDb).at(1_000.0)
        assertEquals(0.69, at1k, 0.12)
        assertEquals(4.0, Curve(Loudness.kWeightDb).at(10_000.0), 0.6) // the ~+4 dB head shelf
        assertTrue(Curve(Loudness.kWeightDb).at(20.0) < -10) // the low cut
    }

    @Test fun loudnessChangeIsZeroForFlatAndExactForUniformGain() {
        assertEquals(0.0, Loudness.changeDb(Curve.FLAT), 1e-9)
        assertEquals(6.0, Loudness.changeDb(Curve.FLAT.plusConst(6.0)), 1e-9)
    }

    @Test fun matchGainMakesTheComparisonFair() {
        val eq = Curve.of(ParametricFilter(FilterType.LOW_SHELF, 90.0, 6.0), ParametricFilter(FilterType.PEAK, 3_000.0, 4.0, 1.0))
        val g = Loudness.matchGainDb(eq)
        assertTrue(g < -0.5, "a boosting EQ must be turned down, got $g")
        assertEquals(0.0, Loudness.changeDb(eq.plusConst(g)), 1e-6)
    }

    @Test fun matchGainDependsOnTheSongsSpectrum() {
        val bassBoost = Curve.of(ParametricFilter(FilterType.LOW_SHELF, 100.0, 6.0))
        val bassySong = Spectrum(DoubleArray(Grid.size) { if (Grid.freqs[it] < 200) 20.0 else 1.0 })
        val thinSong = Spectrum(DoubleArray(Grid.size) { if (Grid.freqs[it] < 200) 0.05 else 1.0 })
        // a bass boost makes a bass-heavy song much louder than a thin one, so it needs more turning down
        assertTrue(Loudness.matchGainDb(bassBoost, bassySong) < Loudness.matchGainDb(bassBoost, thinSong) - 1.0)
    }

    @Test fun spectrumFromFftPutsAToneInTheRightPlace() {
        val n = 513; val fs = 44_100.0
        val mags = DoubleArray(n)
        val bin = (1_000.0 / (fs / (2.0 * (n - 1)))).toInt()
        mags[bin] = 1.0
        val s = Spectrum.fromFft(mags, fs)
        val peak = s.power.indices.maxBy { s.power[it] }
        assertTrue(abs(Grid.freqs[peak] / 1_000.0 - 1) < 0.12, "tone at 1 kHz landed at ${Grid.freqs[peak]}")
        assertTrue(s.power.none { it.isNaN() })
    }

    // ---- equal loudness (ISO 226:2003) ----

    @Test fun iso226ReproducesKnownPoints() {
        assertEquals(40.0, EqualLoudness.spl(1_000.0, 40.0), 0.3)   // definition of the phon
        assertEquals(99.85, EqualLoudness.spl(20.0, 40.0), 0.8)     // widely quoted 40-phon value at 20 Hz
        assertTrue(EqualLoudness.spl(100.0, 40.0) > 55)             // bass needs far more level when quiet
        assertTrue(EqualLoudness.spl(3_150.0, 40.0) < 40)           // ear is most sensitive near 3-4 kHz
    }

    @Test fun compensationBoostsBassOnlyWhenQuietAndIsCapped() {
        assertTrue(EqualLoudness.compensation(85.0).approxEquals(Curve.FLAT, 1e-9))
        val quiet = EqualLoudness.compensation(40.0)
        assertTrue(quiet.at(50.0) > 3.0)
        assertTrue(quiet.at(1_000.0) < 0.3)
        assertTrue(quiet.max() <= 9.0 + 1e-9)
        assertTrue(EqualLoudness.compensation(30.0).at(50.0) >= quiet.at(50.0)) // quieter -> at least as much
    }

    // ---- AutoEq import ----

    private val sample = """
        Preamp: -6.4 dB
        Filter 1: ON LSC Fc 105 Hz Gain 5.3 dB Q 0.70
        Filter 2: ON PK Fc 2000 Hz Gain -2.1 dB Q 1.41
        Filter 3: OFF PK Fc 5000 Hz Gain 9.0 dB Q 2.00
        Filter 4: ON HSC Fc 10000 Hz Gain 3.0 dB Q 0.70
    """.trimIndent()

    @Test fun autoEqParsesEveryOnFilterAndSkipsOff() {
        val r = AutoEqProfile.parse("test", sample)
        val p = assertNotNull(r.profile)
        assertEquals(-6.4, p.preampDb)
        assertEquals(listOf(FilterType.LOW_SHELF, FilterType.PEAK, FilterType.HIGH_SHELF), p.filters.map { it.type })
        assertEquals(2_000.0, p.filters[1].fc); assertEquals(-2.1, p.filters[1].gainDb); assertEquals(1.41, p.filters[1].q)
        assertTrue(r.problems.isEmpty(), r.problems.toString())
        assertEquals(-6.4 + 5.3, p.curve().at(20.0), 0.6)
    }

    @Test fun autoEqReportsGarbageInsteadOfSwallowingIt() {
        val r = AutoEqProfile.parse("bad", "Preamp: -3 dB\nFilter 1: ON XYZ Fc 100 Hz Gain 2 dB Q 1\nhello")
        assertNull(r.profile)
        assertEquals(3, r.problems.size, r.problems.toString()) // unknown type, unreadable line, no filters
    }

    @Test fun uniformPowerMeansPinkNoise() {
        assertEquals(1.0, Spectrum.PINK.flatness(), 1e-9)
        val tonal = Spectrum(DoubleArray(Grid.size) { if (it == 40) 1.0 else 1e-9 })
        assertTrue(tonal.flatness() < 0.01)
        assertEquals(0.0, 10.0.pow(0.0) - 1.0)
    }
}
