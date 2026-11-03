package com.arnavagarwal.sound.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The phone's 8D copy (core EightDCopy) against the laptop's eightd.py, whose output he approved by ear. */
class EightDCopyTest {
    private val kit = EightDCopy.Kit.bundled()
    private val tmp = Files.createTempDirectory("8dtest").toFile().apply { deleteOnExit() }

    /** The fixture tools/eightd/export_kit.py wrote: a seeded 1 s clip and eightd.render()'s output for it. */
    private val fixture: Triple<Int, Array<DoubleArray>, Array<DoubleArray>> by lazy {
        val b = ByteBuffer.wrap(javaClass.getResourceAsStream("/eightd_fixture.bin")!!.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { b.get(it) }
        check(String(magic) == "8DF1")
        val n = b.int
        val inp = Array(2) { DoubleArray(n) }; val out = Array(2) { DoubleArray(n) }
        for (i in 0 until n) { inp[0][i] = b.float.toDouble(); inp[1][i] = b.float.toDouble() }
        for (i in 0 until n) { out[0][i] = b.float.toDouble(); out[1][i] = b.float.toDouble() }
        Triple(n, inp, out)
    }

    private fun render(pcm: EightDCopy.Pcm, block: Int): Pair<Array<DoubleArray>, EightDCopy.Result> {
        val out = Array(2) { DoubleArray(pcm.n.toInt()) }
        var at = 0
        val res = EightDCopy.render(kit, pcm, tmp, block) { buf, cnt ->
            for (i in 0 until cnt) { out[0][at + i] = buf[2 * i].toDouble(); out[1][at + i] = buf[2 * i + 1].toDouble() }
            at += cnt
        }
        assertEquals(pcm.n.toInt(), at, "every sample emitted once")
        return out to res
    }

    /** Error energy against the signal's, in dB, over both ears. */
    private fun errDb(a: Array<DoubleArray>, ref: Array<DoubleArray>): Double {
        var e = 0.0; var s = 0.0
        for (c in 0 until 2) for (i in ref[c].indices) { val d = a[c][i] - ref[c][i]; e += d * d; s += ref[c][i] * ref[c][i] }
        return 10 * log10(e / s)
    }

    @Test fun kitIsTheScriptsKit() {
        assertEquals(72, kit.dirs); assertEquals(128, kit.taps); assertEquals(256, kit.eq.size); assertEquals(1023, kit.lp.size)
        assertTrue(kit.lag in 1..127, "lag ${kit.lag}"); assertTrue(kit.room[0].size > 10000)
    }

    /** Sample for sample the laptop's render, at the song-sized block and at an odd small one (block edges). */
    @Test fun sameAsTheLaptopsEightD() {
        val (_, inp, want) = fixture
        for (block in listOf(32768, 777)) {
            val (got, res) = render(EightDCopy.ArrayPcm(inp[0], inp[1]), block)
            val db = errDb(got, want)
            println("parity block=$block err=${"%.1f".format(db)} dB")
            assertTrue(db < -80, "block $block: differs from eightd.py by $db dB")
            assertEquals(0.0, res.cutDb, 1e-9)
        }
    }

    /** The motion is really there: the right ear's level swings as the sources circle (not just a copy of the input). */
    @Test fun itMoves() {
        val n = (EightDCopy.PERIOD_S * EightDCopy.RATE).toInt()
        val rng = java.util.Random(3)
        val x = DoubleArray(n) { rng.nextGaussian() * 0.1 }
        val (out, _) = render(EightDCopy.ArrayPcm(x, x.copyOf()), 32768)
        val hop = EightDCopy.RATE / 2
        val lv = (0 until n / hop).map { k -> 10 * log10((k * hop until (k + 1) * hop).sumOf { out[1][it] * out[1][it] } / hop) }
        assertTrue(lv.max() - lv.min() > 6, "right ear swings only ${lv.max() - lv.min()} dB over a turn")
        val inRms = sqrt(x.sumOf { it * it } / n)
        val outRms = sqrt((out[0].sumOf { it * it } + out[1].sumOf { it * it }) / (2 * n))
        assertEquals(0.0, 20 * log10(outRms / inRms), 0.1, "loudness matched to the original")
    }

    @Test fun loudSongIsScaledUnderTheCeiling() {
        val n = EightDCopy.RATE
        val x = DoubleArray(n) { 0.99 * sin(2 * PI * 200 * it / EightDCopy.RATE) }
        val (out, res) = render(EightDCopy.ArrayPcm(x, x.copyOf()), 32768)
        val peak = out.maxOf { ch -> ch.maxOf { abs(it) } }
        assertTrue(peak <= EightDCopy.CEILING + 1e-6, "peak $peak")
        assertTrue(res.cutDb > 0, "cut ${res.cutDb}")
    }

    @Test fun wavHeaderAndSamples() {
        val bytes = ByteArrayOutputStream()
        EightDCopy.writeWav(bytes, 3) { emit -> emit(floatArrayOf(0f, 0f, 1f, -1f, 0.5f, 2f), 3) }
        val b = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + 12, bytes.size())
        assertEquals("RIFF", String(bytes.toByteArray(), 0, 4)); assertEquals(36 + 12, b.getInt(4))
        assertEquals(EightDCopy.RATE, b.getInt(24)); assertEquals(16, b.getShort(34).toInt()); assertEquals(12, b.getInt(40))
        assertEquals(listOf(0, 0, 32767, -32767, 16384, 32767), (0 until 6).map { b.getShort(44 + 2 * it).toInt() })
    }

    /** 48 kHz -> 44.1 kHz: a 1 kHz tone comes through clean and at its level; a 23 kHz tone (above 22.05) is removed. */
    @Test fun resampleKeepsTheToneAndDropsWhatCannotFit() {
        fun tone(hz: Double, n: Int) = DoubleArray(n) { 0.5 * sin(2 * PI * hz * it / 48000) }
        val n = 48000
        val a = Resample.Pcm(EightDCopy.ArrayPcm(tone(1000.0, n), tone(1000.0, n)), 48000, 44100)
        assertEquals(44100L, a.n)
        val l = DoubleArray(44100); val r = DoubleArray(44100)
        a.read(0, l, r)
        var e = 0.0; var s = 0.0
        for (i in 2000 until 42000) { val w = 0.5 * sin(2 * PI * 1000 * i / 44100); e += (l[i] - w) * (l[i] - w); s += w * w }
        assertTrue(10 * log10(e / s) < -60, "1 kHz off by ${10 * log10(e / s)} dB")
        val b = Resample.Pcm(EightDCopy.ArrayPcm(tone(23000.0, n), tone(23000.0, n)), 48000, 44100)
        b.read(0, l, r)
        val rms = sqrt((2000 until 42000).sumOf { l[it] * l[it] } / 40000)
        assertTrue(20 * log10(rms / (0.5 / sqrt(2.0))) < -60, "23 kHz leaks at ${20 * log10(rms / (0.5 / sqrt(2.0)))} dB")
        // reading in pieces gives the same samples as one read
        val p1 = DoubleArray(1000); val p2 = DoubleArray(1000)
        a.read(5000, p1, p2)
        a.read(0, l, r)
        for (i in 0 until 1000) assertEquals(l[5000 + i], p1[i], 1e-12)
    }
}
