package com.arnavagarwal.sound.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sample-rate change for the 8D copy: a song decoded at 48 kHz (most phone files) must become 44.1 kHz, the rate the
 * head measurements were made at. On the laptop ffmpeg does this inside eightd.py's reader. Windowed sinc (Kaiser,
 * [ZEROS] zero crossings each side), low-passed just under the lower Nyquist so nothing folds back, read from a table
 * with linear interpolation (a sin() per tap per sample would take minutes on a phone).
 */
object Resample {
    const val ZEROS = 32
    private const val PER_ZERO = 512
    private const val BETA = 9.0
    private const val CUTOFF = 0.95   // fraction of the lower Nyquist kept flat

    private val table: DoubleArray by lazy {
        val n = ZEROS * PER_ZERO
        DoubleArray(n + 2) { k ->
            if (k > n) 0.0 else {
                val x = k.toDouble() / PER_ZERO
                val s = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                val r = x / ZEROS
                s * bessel0(BETA * sqrt(1 - r * r)) / bessel0(BETA)
            }
        }
    }

    private fun bessel0(x: Double): Double {
        var sum = 1.0; var term = 1.0; var k = 1
        while (term > 1e-12 * sum) { term *= (x / (2 * k)) * (x / (2 * k)); sum += term; k++ }
        return sum
    }

    private fun kernel(d: Double): Double {
        val p = abs(d) * PER_ZERO
        val i = p.toInt()
        if (i >= ZEROS * PER_ZERO) return 0.0
        val f = p - i
        return table[i] * (1 - f) + table[i + 1] * f
    }

    fun frames(inFrames: Long, inRate: Int, outRate: Int): Long = (inFrames * outRate + inRate - 1) / inRate

    /** [src] at [inRate] seen as a [EightDCopy.Pcm] at [outRate]; each read computes its samples from the source. */
    class Pcm(private val src: EightDCopy.Pcm, private val inRate: Int, private val outRate: Int) : EightDCopy.Pcm {
        override val n = frames(src.n, inRate, outRate)
        private val scale = min(1.0, outRate.toDouble() / inRate) * CUTOFF
        private val half = (ZEROS / scale).toInt() + 1

        override fun read(start: Long, l: DoubleArray, r: DoubleArray) {
            val len = l.size
            if (len == 0) return
            val first = start * inRate / outRate - half
            val last = (start + len) * inRate / outRate + half + 1
            val w = (last - first).toInt()
            val sl = DoubleArray(w); val sr = DoubleArray(w)
            src.read(first, sl, sr)
            for (i in 0 until len) {
                val k = start + i
                if (k !in 0 until n) { l[i] = 0.0; r[i] = 0.0; continue }
                val whole = k * inRate / outRate
                val t = whole + (k * inRate % outRate).toDouble() / outRate   // exact position in input samples
                val lo = floor(t).toLong() - half + 1
                var al = 0.0; var ar = 0.0
                for (m in lo..lo + 2 * half - 1) {
                    val c = kernel((t - m) * scale)
                    if (c == 0.0) continue
                    val q = (m - first).toInt()
                    al += c * sl[q]; ar += c * sr[q]
                }
                l[i] = al * scale; r[i] = ar * scale
            }
        }
    }
}
