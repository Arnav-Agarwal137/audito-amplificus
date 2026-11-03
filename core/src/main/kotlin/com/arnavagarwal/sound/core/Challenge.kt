package com.arnavagarwal.sound.core

import kotlin.random.Random

/**
 * The launch challenge: "hear your favourite song normally, then with our app".
 *
 * Done honestly, so the result means something:
 *  - BLIND: each round plays "A" then "B"; which one is enhanced is decided at random and hidden.
 *  - LOUDNESS-MATCHED: the enhanced version is turned down by exactly the loudness it adds
 *    ([Loudness.matchGainDb]), because louder always sounds "better" (research/data-models-standards.md).
 *  - BOTH HEARD: a round cannot be scored until both A and B have been played in it ([canPick]);
 *    a pick without hearing both is a guess that would count as a vote.
 *  - SCORED: after [rounds] picks we report how often the enhanced one won and how likely that score
 *    is by pure guessing (binomial), so "you preferred it 6 of 6 times" is distinguishable from luck.
 * The messages only STATE the result. Whatever the app does about it (turning the strength down when the
 * original wins) is done and said by the screen, so no message can promise an action that never happens.
 */
class Challenge(val rounds: Int = 6, seed: Long = System.nanoTime()) {
    init { require(rounds in 1..20) }
    private val rng = Random(seed)
    /** enhancedIsA[i] = true when, in round i, "A" is the enhanced version. */
    val enhancedIsA: BooleanArray = BooleanArray(rounds) { rng.nextBoolean() }
    private val picks = ArrayList<Boolean>() // true = picked enhanced
    private var heardEnhanced = false
    private var heardOriginal = false

    val done: Boolean get() = picks.size >= rounds
    val round: Int get() = picks.size

    /** The person played one side in the current round ([enhanced] = the tuned one). */
    fun heard(enhanced: Boolean) { if (enhanced) heardEnhanced = true else heardOriginal = true }

    /** True once both A and B have been played in this round. */
    val canPick: Boolean get() = !done && heardEnhanced && heardOriginal

    /** Record the person's pick for the current round: [pickedA] = they preferred "A". */
    fun pick(pickedA: Boolean) {
        check(!done) { "challenge already finished" }
        check(canPick) { "play both A and B before choosing" }
        picks += (pickedA == enhancedIsA[picks.size])
        heardEnhanced = false; heardOriginal = false
    }

    data class Result(val enhancedWins: Int, val rounds: Int, val pGuessing: Double, val verdict: Verdict, val message: String)
    enum class Verdict { CLEAR_WIN, LEANS_ENHANCED, NO_DIFFERENCE, PREFERS_ORIGINAL }

    fun result(): Result {
        check(done)
        val k = picks.count { it }
        val p = binomialTailAtLeast(k, rounds)
        val pRev = binomialTailAtLeast(rounds - k, rounds)
        val (v, msg) = when {
            p <= 0.05 -> Verdict.CLEAR_WIN to "You picked the tuned sound $k of $rounds times, at equal loudness. Chance of that by guessing: ${pct(p)}."
            k * 2 > rounds -> Verdict.LEANS_ENHANCED to "You leaned toward the tuned sound ($k of $rounds), but that could still be chance (${pct(p)}). Try again with a song you know well."
            pRev <= 0.05 -> Verdict.PREFERS_ORIGINAL to "You preferred the untouched sound ${rounds - k} of $rounds times, at equal loudness."
            else -> Verdict.NO_DIFFERENCE to "No clear difference for you on this song ($k of $rounds picks went to the tuned sound). Try again with earbuds and a song you know well."
        }
        return Result(k, rounds, p, v, msg)
    }

    private fun pct(p: Double) = if (p < 0.01) "under 1%" else "${Math.round(p * 100)}%"

    companion object {
        /** P(X >= k) for X ~ Binomial(n, 0.5): the chance of scoring at least k by guessing. */
        fun binomialTailAtLeast(k: Int, n: Int): Double {
            var s = 0.0
            for (i in k..n) s += choose(n, i)
            return s / Math.pow(2.0, n.toDouble())
        }
        private fun choose(n: Int, k: Int): Double {
            var r = 1.0
            for (i in 1..k) r = r * (n - k + i) / i
            return r
        }
    }
}
