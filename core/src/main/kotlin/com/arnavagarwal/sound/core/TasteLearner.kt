package com.arnavagarwal.sound.core

import kotlin.math.exp

/**
 * Learns what THIS person wants, from the corrections they make. His observation that started the app:
 * he likes bass on some songs and not on smooth ones; his dad dislikes bass everywhere. So taste is a
 * function of (person, kind of content, sometimes the exact song).
 *
 * Method: every time the person settles on a [Tone] for something, we store (content vector, tone).
 * To predict, we take a kernel-weighted average of the stored tones whose content looks similar
 * (k-nearest-neighbour regression). Two safeguards:
 *  - shrinkage: with little or no similar evidence, the prediction falls back toward the person's
 *    overall average, and with no evidence at all toward zero, so one tweak never becomes a law;
 *  - exact-song memory: a correction on this very song supplies [songShare] (80%) of the answer.
 * All on-device; the store is a plain list the app saves as JSON.
 */
class TasteLearner(
    private val bandwidth: Double = 0.35,
    private val priorStrength: Double = 1.5,
    private val songWeight: Double = 6.0,
    private val songShare: Double = 0.8,
    private val maxSamples: Int = 400,
) {
    data class Sample(val content: DoubleArray, val tone: Tone, val songKey: String?, val timeMs: Long)

    private val samples = ArrayList<Sample>()
    val size: Int get() = samples.size
    fun all(): List<Sample> = samples.toList()

    fun record(profile: ContentProfile, songKey: String?, tone: Tone, timeMs: Long) {
        // a new correction on the same song replaces the old one: the latest opinion is the true one
        if (songKey != null) samples.removeAll { it.songKey == songKey }
        samples += Sample(profile.vector(), tone.clamp(12.0), songKey, timeMs)
        // capacity: drop the OLDEST non-song samples first; song memories are the most valuable
        while (samples.size > maxSamples) {
            val idx = samples.indexOfFirst { it.songKey == null }.takeIf { it >= 0 } ?: 0
            samples.removeAt(idx)
        }
    }

    fun load(list: List<Sample>) { samples.clear(); samples.addAll(list.takeLast(maxSamples)) }

    data class Prediction(val tone: Tone, val evidence: Double, val fromSongMemory: Boolean)

    fun predict(profile: ContentProfile, songKey: String?): Prediction {
        if (samples.isEmpty()) return Prediction(Tone.ZERO, 0.0, false)
        val v = profile.vector()
        val global = samples.fold(Tone.ZERO) { acc, s -> acc + s.tone } * (1.0 / samples.size)
        // the person's overall lean, itself shrunk toward zero while evidence is thin
        val globalShrunk = global * (samples.size / (samples.size + priorStrength))

        var wSum = 0.0
        var acc = Tone.ZERO
        for (s in samples) {
            val w = exp(-sq(ContentClassifier.l2(v, s.content)) / (2 * bandwidth * bandwidth))
            wSum += w
            acc += s.tone * w
        }
        // shrink the local estimate toward the person's overall lean in proportion to how little local evidence there is
        val general = (acc + globalShrunk * priorStrength) * (1.0 / (wSum + priorStrength))
        // an explicit correction on THIS song is the person's latest word on it: it takes [songShare] of the result
        val songSample = songKey?.let { k -> samples.lastOrNull { it.songKey == k } }
        if (songSample != null) {
            val tone = songSample.tone * songShare + general * (1 - songShare)
            return Prediction(tone.clamp(12.0), wSum + songWeight, true)
        }
        return Prediction(general.clamp(12.0), wSum, false)
    }

    private fun sq(x: Double) = x * x
}
