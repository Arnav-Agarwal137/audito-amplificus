package com.arnavagarwal.sound.core

import kotlin.math.log10

/**
 * One song's measurements, for the voice-led density rule (his choice, 25 Sep: "Learn from my listening"). Density
 * was wrong on soft, voice-led, high-pitched songs (Husn, Anuv Jain), and the app read "Jo Tum Mere Ho" as bass-heavy.
 * Three recorded songs are too few to set a rule, so the app logs every song he plays and the rule is set from those
 * numbers - the same numbers the app will decide with, measured the same way (the uncoloured frame spectra on [Grid]).
 *
 * Fed with every captured frame (~20 a second) and once a second with the app's decision. [summary] is null until the
 * song has [MIN_SOUND_S] seconds of sound: a skipped song is not a data point.
 */
class SongStats(val title: String, val app: String) {
    private val shares = DoubleArray(4) // < 150 Hz, 150-300, 300-3400 (voice), 5-12 kHz
    private var totalPow = 0.0
    private val levels = ArrayList<Double>()
    private val lowDb = ArrayList<Double>()
    private var centroidLogSum = 0.0
    private var flatSum = 0.0
    private val kinds = HashMap<String, Int>()
    private var liftSum = 0.0
    private var liftN = 0
    private var ceilSum = 0.0
    private var ceilN = 0
    private val avgs = sortedMapOf<String, Pair<Double, Int>>()
    val soundFrames get() = levels.size
    var frameRateHz = 20.0

    /** One frame: its (uncoloured) spectrum and level. Frames below [SILENT_DB] are skipped. */
    fun frame(s: Spectrum, levelDb: Double) {
        if (levelDb <= SILENT_DB) return
        val t = s.total
        if (t <= 0) return
        // each frame weighted by its own power share, so a loud chorus and a quiet verse count alike per second
        var low = 0.0
        for (g in Grid.freqs.indices) {
            val f = Grid.freqs[g]; val p = s.power[g] / t
            when {
                f < 150 -> shares[0] += p
                f < 300 -> shares[1] += p
                f in 300.0..3_400.0 -> shares[2] += p
                f in 5_000.0..12_000.0 -> shares[3] += p
            }
            if (f < 200) low += s.power[g]
        }
        totalPow += 1.0
        levels += levelDb
        lowDb += 10 * log10(low + 1e-15) + levelDb - 10 * log10(t) // low band's own level, in the frame's scale
        centroidLogSum += log10(s.centroidHz())
        flatSum += s.flatness()
    }

    /** Once a second: what the app decided. [kind] = the classifier's top kind (null when unsure). */
    fun second(kind: ContentKind?, densityLiftDb: Double?, ceilingShare: Double?) {
        val k = kind?.name ?: "UNSURE"
        kinds[k] = (kinds[k] ?: 0) + 1
        if (densityLiftDb != null) { liftSum += densityLiftDb; liftN++ }
        if (ceilingShare != null) { ceilSum += ceilingShare; ceilN++ }
    }

    /** Any other decision or setting, once a second (his ask 25 Sep 21:03: learn "all things ... what to do when").
     *  Averaged over the song; a switch is noted as 1/0, so its average is the share of the song it was on. */
    fun note(name: String, value: Double?) {
        if (value == null || value.isNaN()) return
        val (s, n) = avgs[name] ?: (0.0 to 0); avgs[name] = (s + value) to (n + 1)
    }

    /** Positive jumps of the low band between frames (dB): beats and kicks give big ones, a sung bass line small ones. */
    private fun lowOnsetDb(): Double {
        val j = lowDb.zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
        return if (j.isEmpty()) 0.0 else j[(j.size * 0.9).toInt().coerceAtMost(j.size - 1)] // 90th percentile: the hits
    }

    private fun pct(v: List<Double>, p: Double): Double { val s = v.sorted(); return s[((s.size - 1) * p).toInt()] }

    fun summary(): Map<String, Any?>? {
        if (levels.size < MIN_SOUND_S * frameRateHz) return null
        val n = totalPow
        return linkedMapOf(
            "title" to title, "app" to app, "soundS" to r(levels.size / frameRateHz, 0),
            "bass" to r(shares[0] / n), "lowMid" to r(shares[1] / n), "voice" to r(shares[2] / n), "air" to r(shares[3] / n),
            "centroidHz" to r(Math.pow(10.0, centroidLogSum / n), 0), "flatness" to r(flatSum / n),
            "dynDb" to r(pct(levels, 0.95) - pct(levels, 0.10), 1), "lowOnsetDb" to r(lowOnsetDb(), 1),
            "kinds" to kinds.toSortedMap(), "densityDb" to if (liftN > 0) r(liftSum / liftN, 2) else null,
            "ceilShare" to if (ceilN > 0) r(ceilSum / ceilN) else null,
            "decisions" to avgs.mapValues { (_, v) -> r(v.first / v.second, 2) },
        )
    }

    private fun r(v: Double, d: Int = 3) = Math.round(v * Math.pow(10.0, d.toDouble())) / Math.pow(10.0, d.toDouble())

    companion object {
        const val SILENT_DB = -60.0
        const val MIN_SOUND_S = 30
    }
}
