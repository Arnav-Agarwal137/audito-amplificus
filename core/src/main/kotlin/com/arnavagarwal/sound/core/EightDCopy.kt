package com.arnavagarwal.sound.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The 8D copy on the phone (his rule 29 Sep "same to same"; the laptop's copy is tools/eightd/eightd.py, approved by his
 * ear on Husn: "Good, keep it"). This is that script's render(), sample for sample:
 *  - the song's left and right become two sources [SPREAD] degrees either side of a centre that circles the head once
 *    every [PERIOD_S] seconds, heard through measured head responses (MIT KEMAR), blended between the two nearest
 *    measured directions at EVERY sample so the motion has no steps;
 *  - bass under 150 Hz skips the 3D stage and goes straight to its own ear (ears cannot place bass);
 *  - an extra left-right pan ([PAN_DEPTH]), a small room at [ROOM_DB], loudness matched to the original, and scaled
 *    down only if it would clip.
 * Every FIXED filter (head responses, their EQ, the crossover, the room) is computed by eightd.py itself and shipped as
 * eightd_kit.bin (tools/eightd/export_kit.py), so nothing here re-derives them. EightDTest holds this to the script's
 * own output on a fixture clip.
 *
 * Songs are processed in blocks from a [Pcm] (a file on disk on the phone), so a long song never has to fit in memory.
 */
object EightDCopy {
    const val RATE = 44100
    const val STEP = 5.0
    const val PERIOD_S = 12.0
    const val SPREAD = 30.0
    const val ROOM_DB = -18.0
    const val PAN_DEPTH = 0.8
    const val CEILING = 0.98

    class Kit(
        val dirs: Int, val taps: Int, val lag: Int, val gain: Double,
        /** [dir][ear], each REVERSED (index 0 = the last tap) so a dot product walks the source forwards. */
        val hrirRev: Array<Array<DoubleArray>>,
        val eq: DoubleArray, val lp: DoubleArray, val room: Array<DoubleArray>,
    ) {
        companion object {
            fun load(input: InputStream): Kit {
                val bytes = input.readBytes()
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(4).also { b.get(it) }
                require(String(magic, Charsets.US_ASCII) == "8DK1") { "not an 8D kit" }
                val rate = b.int; val dirs = b.int; val taps = b.int; val eqLen = b.int; val lpLen = b.int
                val lag = b.int; val roomLen = b.int
                require(rate == RATE) { "kit rate $rate" }
                val gain = b.double
                val h = Array(dirs) { Array(2) { DoubleArray(taps) } }
                for (d in 0 until dirs) for (e in 0 until 2) { val a = h[d][e]; for (t in 0 until taps) a[taps - 1 - t] = b.double }
                val eq = DoubleArray(eqLen) { b.double }
                val lp = DoubleArray(lpLen) { b.double }
                val room = Array(2) { DoubleArray(roomLen) { b.double } }
                require(!b.hasRemaining()) { "kit has ${b.remaining()} bytes left over" }
                return Kit(dirs, taps, lag, gain, h, eq, lp, room)
            }

            /** The kit inside the core jar (packed into the app too). */
            fun bundled(): Kit = EightDCopy::class.java.getResourceAsStream("/eightd_kit.bin")!!.use { load(it) }
        }
    }

    /** Stereo samples by index; anything outside 0 until [n] reads as silence. */
    interface Pcm {
        val n: Long
        fun read(start: Long, l: DoubleArray, r: DoubleArray)
    }

    class ArrayPcm(private val left: DoubleArray, private val right: DoubleArray) : Pcm {
        override val n = left.size.toLong()
        override fun read(start: Long, l: DoubleArray, r: DoubleArray) {
            for (i in l.indices) {
                val k = start + i
                if (k in 0 until n) { l[i] = left[k.toInt()]; r[i] = right[k.toInt()] } else { l[i] = 0.0; r[i] = 0.0 }
            }
        }
    }

    /** Interleaved little-endian float32 stereo on disk (what the phone decodes a song into). */
    class FilePcm(file: File) : Pcm, AutoCloseable {
        private val raf = RandomAccessFile(file, "r")
        override val n = raf.length() / 8
        private var buf = ByteBuffer.allocate(0)
        override fun read(start: Long, l: DoubleArray, r: DoubleArray) {
            l.fill(0.0); r.fill(0.0)
            val a = max(start, 0L); val b = min(start + l.size, n)
            if (b <= a) return
            val count = (b - a).toInt()
            if (buf.capacity() < count * 8) buf = ByteBuffer.allocate(count * 8)
            buf.clear(); buf.limit(count * 8); buf.order(ByteOrder.LITTLE_ENDIAN)
            val ch = raf.channel
            var pos = a * 8
            while (buf.hasRemaining()) { val got = ch.read(buf, pos); if (got < 0) break; pos += got }
            buf.flip()
            val off = (a - start).toInt()
            for (i in 0 until count) { l[off + i] = buf.float.toDouble(); r[off + i] = buf.float.toDouble() }
        }
        override fun close() = raf.close()
    }

    // ---- the render -------------------------------------------------------------------------------------------------

    private fun azimuth(j: Long, startDeg: Double): Double {
        val v = (startDeg + 360.0 * j / (PERIOD_S * RATE)) % 360.0
        return if (v < 0) v + 360.0 else v
    }

    /** One block of final samples [i0, i0 + len): the 3D part (dry, both ears) and the room (wet, both ears). */
    internal class Block(val dry: Array<DoubleArray>, val wet: Array<DoubleArray>)

    internal fun block(kit: Kit, pcm: Pcm, i0: Long, len: Int, ffts: Fft.Cache): Block {
        val n = pcm.n
        val taps = kit.taps
        val xd = kit.lp.size / 2
        val eqHalf = kit.eq.size / 2
        val i1 = i0 + len
        val lowA = i0 - (kit.eq.size - 1) - kit.lag - 1
        val lowB = i1 + eqHalf
        val lo = min(lowA - xd, i0 - kit.room[0].size + 1)
        val hi = lowB + xd + 1
        val w = (hi - lo).toInt()
        val xl = DoubleArray(w); val xr = DoubleArray(w)
        pcm.read(lo, xl, xr)
        val lowLen = (lowB - lowA).toInt()
        val srcA = i0 + eqHalf - taps + 1      // the first source sample the first output needs
        val srcLen = len + taps - 1
        val lows = arrayOfNulls<DoubleArray>(2)
        val srcs = arrayOfNulls<DoubleArray>(2)
        for ((c, x) in listOf(xl, xr).withIndex()) {
            val low = Fft.convRange(kit.lp, x, lo, lowA + xd, lowLen, ffts)
            val high = DoubleArray(lowLen)
            for (q in 0 until lowLen) {
                val m = lowA + q
                high[q] = if (m in 0 until n) x[(m - lo).toInt()] - low[q] else 0.0
            }
            val src = Fft.convRange(kit.eq, high, lowA, srcA, srcLen, ffts)
            for (q in src.indices) src[q] *= kit.gain
            lows[c] = low; srcs[c] = src
        }
        val dry = Array(2) { DoubleArray(len) }
        for (i in 0 until len) {
            val j = i0 + i + eqHalf               // index in the script's un-trimmed output
            var e0 = 0.0; var e1 = 0.0
            for (c in 0 until 2) {
                val az = azimuth(j, (if (c == 0) -SPREAD else SPREAD))
                val pos = az / STEP
                val fl = floor(pos)
                val d0 = Math.floorMod(fl.toInt(), kit.dirs); val d1 = (d0 + 1) % kit.dirs
                val f = pos - fl
                val src = srcs[c]!!
                val base = (j - taps + 1 - srcA).toInt()
                val h00 = kit.hrirRev[d0][0]; val h01 = kit.hrirRev[d0][1]
                val h10 = kit.hrirRev[d1][0]; val h11 = kit.hrirRev[d1][1]
                var a00 = 0.0; var a01 = 0.0; var a10 = 0.0; var a11 = 0.0
                for (t in 0 until taps) {
                    val s = src[base + t]
                    a00 += h00[t] * s; a01 += h01[t] * s; a10 += h10[t] * s; a11 += h11[t] * s
                }
                e0 += (1 - f) * a00 + f * a10
                e1 += (1 - f) * a01 + f * a11
            }
            val th = Math.toRadians(azimuth(j, 0.0))
            val st = sin(th)
            e0 *= sqrt(1 - PAN_DEPTH * st)
            e1 *= sqrt(1 + PAN_DEPTH * st)
            val k = i0 + i - kit.lag              // the bass goes straight to its own ear, after the heads' onset
            if (k in 0 until n) {
                val q = (k - lowA).toInt()
                e0 += lows[0]!![q]; e1 += lows[1]!![q]
            }
            dry[0][i] = e0; dry[1][i] = e1
        }
        val mono = DoubleArray(w) { (xl[it] + xr[it]) / 2 }
        val wet = Array(2) { e -> Fft.convRange(kit.room[e], mono, lo, i0, len, ffts) }
        return Block(dry, wet)
    }

    /** What happened, for the card: the dB taken off to stay under clipping. */
    data class Result(val samples: Long, val cutDb: Double)

    /**
     * Renders the whole song. [sink] gets interleaved final samples (L, R, L, R ...) block by block, in order.
     * Three passes over a scratch file in [tmpDir]: render (and sum the energies), find the peak, emit.
     */
    fun render(
        kit: Kit, pcm: Pcm, tmpDir: File, blockLen: Int = 32768,
        progress: (Double) -> Unit = {}, sink: (FloatArray, Int) -> Unit,
    ): Result {
        val n = pcm.n
        require(n > 0) { "empty song" }
        val scratch = File.createTempFile("eightd", ".f32", tmpDir)
        try {
            val ffts = Fft.Cache()
            val sDD = DoubleArray(2); val sWW = DoubleArray(2); val sDW = DoubleArray(2)
            var sIn = 0.0
            val xl = DoubleArray(blockLen); val xr = DoubleArray(blockLen)
            DataOutputStream(BufferedOutputStream(FileOutputStream(scratch), 1 shl 16)).use { out ->
                val bb = ByteBuffer.allocate(blockLen * 16).order(ByteOrder.LITTLE_ENDIAN)
                var i0 = 0L
                while (i0 < n) {
                    val len = min(blockLen.toLong(), n - i0).toInt()
                    val b = block(kit, pcm, i0, len, ffts)
                    pcm.read(i0, xl, xr)
                    bb.clear()
                    for (i in 0 until len) {
                        sIn += xl[i] * xl[i] + xr[i] * xr[i]
                        for (e in 0 until 2) {
                            val d = b.dry[e][i]; val wv = b.wet[e][i]
                            sDD[e] += d * d; sWW[e] += wv * wv; sDW[e] += d * wv
                            bb.putFloat(d.toFloat()); bb.putFloat(wv.toFloat())
                        }
                    }
                    out.write(bb.array(), 0, bb.position())
                    i0 += len
                    progress(0.9 * i0 / n)
                }
            }
            // the room is set against the 3D part's own level, ear by ear; then the whole is matched to the original
            val roomK = DoubleArray(2) { e ->
                sqrt((sDD[e] / n) / max(sWW[e] / n, 1e-20)) * 10.0.pow(ROOM_DB / 20)
            }
            var sOut = 0.0
            for (e in 0 until 2) sOut += sDD[e] + 2 * roomK[e] * sDW[e] + roomK[e] * roomK[e] * sWW[e]
            var g = sqrt(sIn / (2.0 * n)) / max(sqrt(sOut / (2.0 * n)), 1e-20)
            var peak = 0.0
            forEachFrame(scratch, blockLen) { f, cnt ->
                for (i in 0 until cnt) for (e in 0 until 2)
                    peak = max(peak, abs((f[i * 4 + e * 2] + roomK[e] * f[i * 4 + e * 2 + 1]) * g))
            }
            progress(0.95)
            var cut = 0.0
            if (peak > CEILING) { cut = 20 * kotlin.math.log10(peak / CEILING); g *= CEILING / peak }
            val outBuf = FloatArray(blockLen * 2)
            forEachFrame(scratch, blockLen) { f, cnt ->
                for (i in 0 until cnt) for (e in 0 until 2)
                    outBuf[i * 2 + e] = ((f[i * 4 + e * 2] + roomK[e] * f[i * 4 + e * 2 + 1]) * g).toFloat()
                sink(outBuf, cnt)
            }
            progress(1.0)
            return Result(n, cut)
        } finally {
            scratch.delete()
        }
    }

    private fun forEachFrame(file: File, frames: Int, body: (DoubleArray, Int) -> Unit) {
        DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { inp ->
            val bytes = ByteArray(frames * 16)
            val f = DoubleArray(frames * 4)
            while (true) {
                var got = 0
                while (got < bytes.size) { val r = inp.read(bytes, got, bytes.size - got); if (r < 0) break; got += r }
                if (got == 0) break
                val bb = ByteBuffer.wrap(bytes, 0, got).order(ByteOrder.LITTLE_ENDIAN)
                val cnt = got / 16
                for (k in 0 until cnt * 4) f[k] = bb.float.toDouble()
                body(f, cnt)
                if (got < bytes.size) break
            }
        }
    }

    /** A 16-bit stereo WAV of [frames] frames at [RATE]: header first, then [writeData] fills the samples. */
    fun writeWav(out: OutputStream, frames: Long, writeData: ((FloatArray, Int) -> Unit) -> Unit) {
        val data = frames * 4
        require(data + 36 <= 0xFFFFFFFFL) { "too long for a WAV file" }
        val o = BufferedOutputStream(out, 1 shl 16)
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + data).toInt()); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(2); h.putInt(RATE); h.putInt(RATE * 4)
        h.putShort(4); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(data.toInt())
        o.write(h.array())
        var written = 0L
        writeData { buf, cnt ->
            val bb = ByteBuffer.allocate(cnt * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (k in 0 until cnt * 2) {
                val v = Math.round(buf[k].toDouble().coerceIn(-1.0, 1.0) * 32767).toInt()
                bb.putShort(v.toShort())
            }
            o.write(bb.array()); written += cnt
        }
        o.flush()
        check(written == frames) { "wrote $written frames, header says $frames" }
    }
}

/** Radix-2 FFT and linear convolution through it (for the long filters: the crossover, the EQ, the room). */
internal object Fft {
    class Cache {
        /** Spectrum of each filter at each size, so a filter is transformed once per song, not once per block. */
        val spectra = HashMap<Pair<DoubleArray, Int>, Pair<DoubleArray, DoubleArray>>()
        fun spectrum(h: DoubleArray, size: Int): Pair<DoubleArray, DoubleArray> = spectra.getOrPut(h to size) {
            val re = DoubleArray(size); val im = DoubleArray(size)
            h.copyInto(re); transform(re, im, false); re to im
        }
    }

    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci; val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    /** y[o] = sum over t of h[t] * x[o - t], for o in [outStart, outStart + outLen); x[k] is xs[k - xStart], else 0. */
    fun convRange(h: DoubleArray, xs: DoubleArray, xStart: Long, outStart: Long, outLen: Int, cache: Cache): DoubleArray {
        val full = h.size + xs.size - 1
        var size = 1
        while (size < full) size = size shl 1
        val (hr, hi) = cache.spectrum(h, size)
        val re = DoubleArray(size); val im = DoubleArray(size)
        xs.copyInto(re)
        transform(re, im, false)
        for (k in 0 until size) {
            val r = re[k] * hr[k] - im[k] * hi[k]; val i = re[k] * hi[k] + im[k] * hr[k]
            re[k] = r; im[k] = i
        }
        transform(re, im, true)
        return DoubleArray(outLen) { q ->
            val k = outStart + q - xStart
            if (k in 0 until full) re[k.toInt()] else 0.0
        }
    }
}
