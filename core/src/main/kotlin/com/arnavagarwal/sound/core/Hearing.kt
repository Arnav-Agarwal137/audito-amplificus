package com.arnavagarwal.sound.core

import kotlin.math.abs
import kotlin.math.pow

/**
 * Hearing-safe listening, after WHO / ITU-T H.870 "safe listening" (research/data-models-standards.md):
 * the weekly allowance for adults ("mode 1") is 1.6 Pa²h - the sound energy of 80 dBA for 40 hours.
 * Every +3 dB halves the safe time. Android computes its own sound dose internally but gives apps NO
 * way to read it, so we estimate our own. It is an ESTIMATE (we do not know the exact earbud
 * sensitivity) and the app must always say so.
 */
object SoundDose {
    const val WEEKLY_LIMIT_PA2H = 1.6
    private const val P0 = 20e-6 // reference pressure, pascals (0 dB SPL)

    /** Squared pressure (Pa²) of a sound at [dbSpl]. */
    fun pressure2(dbSpl: Double): Double = (P0 * 10.0.pow(dbSpl / 20)).pow(2)

    /** Dose (Pa²h) from listening at [dbSpl] for [hours]. */
    fun dose(dbSpl: Double, hours: Double): Double = pressure2(dbSpl) * hours

    /** Hours at [dbSpl] until [remainingPa2h] is used up. */
    fun hoursLeft(dbSpl: Double, remainingPa2h: Double): Double =
        if (remainingPa2h <= 0) 0.0 else remainingPa2h / pressure2(dbSpl)

    /**
     * Estimated level at the eardrum: [maxSplAtFullScale] (what the earbud produces for a full-scale
     * signal at maximum phone volume; ~100 dB is typical and the EU cap) + the content's level in dBFS
     * + the phone's volume attenuation (<= 0 dB, from AudioManager.getStreamVolumeDb).
     */
    fun estimateSpl(contentDbfs: Double, volumeDb: Double, maxSplAtFullScale: Double = 100.0): Double =
        maxSplAtFullScale + contentDbfs + volumeDb
}

/** Rolling seven-day dose ledger in hourly buckets. Stored by the app; pure logic here. */
class DoseLedger(private val buckets: MutableMap<Long, Double> = HashMap()) {
    private fun hourOf(ms: Long) = ms / 3_600_000

    fun add(timeMs: Long, dbSpl: Double, seconds: Double) {
        if (seconds <= 0) return
        val h = hourOf(timeMs)
        buckets[h] = (buckets[h] ?: 0.0) + SoundDose.dose(dbSpl, seconds / 3600)
        prune(timeMs)
    }

    fun weekDose(nowMs: Long): Double { prune(nowMs); return buckets.values.sum() }
    fun weekFraction(nowMs: Long): Double = weekDose(nowMs) / SoundDose.WEEKLY_LIMIT_PA2H
    fun snapshot(): Map<Long, Double> = HashMap(buckets)

    private fun prune(nowMs: Long) {
        val cutoff = hourOf(nowMs) - 7 * 24
        buckets.keys.removeAll { it <= cutoff }
    }
}

/**
 * "This volume is best for this sound" (his request). A comfortable target level per content kind,
 * lowered when the week's hearing budget is getting used up. Returns the volume step whose estimated
 * level lands closest to the target.
 */
object VolumeAdvisor {
    /** Comfortable listening targets, dBA at the ear. Speech needs less level to be clear than music. */
    val targetDb: Map<ContentKind, Double> = mapOf(
        ContentKind.SPEECH to 65.0, ContentKind.SMOOTH to 69.0, ContentKind.VOCAL_POP to 72.0,
        ContentKind.BASS_HEAVY to 74.0, ContentKind.CLASSICAL to 72.0, ContentKind.ENERGETIC to 74.0,
        ContentKind.MOVIE to 70.0,
    )

    data class Advice(
        val bestIndex: Int,
        val bestPercent: Int,
        val targetDb: Double,
        val currentDb: Double,
        val hoursLeftAtCurrent: Double,
        val weekFraction: Double,
        val message: String,
    )

    /**
     * [volumeDbByIndex][i] = phone attenuation (dB, <= 0) at volume step i (index 0 = mute is skipped).
     * [currentIndex] is the step now set. [contentDbfs] is the measured average level of what is playing.
     */
    fun advise(kind: ContentKind, contentDbfs: Double, volumeDbByIndex: DoubleArray, currentIndex: Int,
               weekFraction: Double, maxSplAtFullScale: Double = 100.0): Advice {
        require(volumeDbByIndex.size >= 2)
        val budgetCut = when { weekFraction >= 1.0 -> 8.0; weekFraction >= 0.8 -> 5.0; weekFraction >= 0.5 -> 2.0; else -> 0.0 }
        val target = targetDb.getValue(kind) - budgetCut
        val best = (1 until volumeDbByIndex.size).minBy {
            abs(SoundDose.estimateSpl(contentDbfs, volumeDbByIndex[it], maxSplAtFullScale) - target)
        }
        val cur = currentIndex.coerceIn(0, volumeDbByIndex.size - 1)
        val curDb = SoundDose.estimateSpl(contentDbfs, volumeDbByIndex[cur], maxSplAtFullScale)
        val remaining = (1.0 - weekFraction).coerceAtLeast(0.0) * SoundDose.WEEKLY_LIMIT_PA2H
        val hours = SoundDose.hoursLeft(curDb, remaining)
        val pct = Math.round(100.0 * best / (volumeDbByIndex.size - 1)).toInt()
        val msg = when {
            weekFraction >= 1.0 -> "You have used this week's safe listening allowance. Best: $pct% or lower."
            curDb > target + 6 -> "About ${"%.0f".format(curDb - target)} dB louder than needed for this. $pct% sounds as good and is safer" +
                (if (hours < 40) " - at this volume your weekly safe time runs out in ${formatHours(hours)}." else ".")
            curDb < target - 8 -> "$pct% will sound fuller for this."
            else -> "Good level for this."
        }
        return Advice(best, pct, target, curDb, hours, weekFraction, msg)
    }

    fun formatHours(h: Double): String = when {
        h < 1.0 -> "${Math.round(h * 60)} min"
        h < 10 -> "%.1f h".format(h)
        else -> "${Math.round(h)} h"
    }
}
