package com.arnavagarwal.sound.desktop

import com.arnavagarwal.sound.core.ContentClassifier
import com.arnavagarwal.sound.core.DensityControl
import com.arnavagarwal.sound.core.SongStats
import com.arnavagarwal.sound.core.SpectralFeatures
import com.arnavagarwal.sound.core.Spectrum
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The song log for a FILE instead of the loopback (his ask 25 Sep 21:03: many copyright-free songs of all sorts, as
 * data). The helper's framing is reproduced here exactly (windows/helper/Program.cs: 48 kHz, channels averaged to
 * mono, 2048-point Hann FFT of the newest samples every 2400 samples, level = RMS of that hop); everything after the
 * magnitudes is the app's own code (Spectrum.fromFft, SongStats, SpectralFeatures, ContentClassifier), so a web song
 * is measured exactly as a song he plays - tuning off, i.e. the uncoloured frames.
 * Decoding uses ffmpeg. Classification is sound-only (no title), as the app's keywords would not know a web song.
 *   gradle :desktop:songFile --args="<out.jsonl> <file|folder>..."
 */
fun main(args: Array<String>) {
    val out = File(args[0])
    val files = args.drop(1).flatMap { a -> File(a).let { if (it.isDirectory) it.walk().filter { f -> f.isFile && f.extension.lowercase() in AUDIO }.toList() else listOf(it) } }
    var n = 0
    for (f in files) {
        val row = runCatching { measure(f) }.getOrElse { e -> System.err.println("FAILED ${f.name}: ${e.message}"); null } ?: continue
        out.parentFile?.mkdirs(); out.appendText(SongLog.json(row).toString() + "\n", Charsets.UTF_8); n++
    }
    println("${n} of ${files.size} files logged to $out")
}

private val AUDIO = setOf("mp3", "ogg", "opus", "flac", "wav", "m4a", "aac", "webm")
private const val FS = 48_000
private const val N = 2048
private const val HOP = 2400
private val HANN = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / (N - 1)) }

fun measure(f: File): Map<String, Any?>? {
    val p = ProcessBuilder("ffmpeg", "-v", "error", "-i", f.path, "-vn", "-ac", "2", "-ar", "$FS", "-f", "f32le", "-")
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val stats = SongStats(f.nameWithoutExtension, "file")
    val ring = DoubleArray(N); var pos = 0; var filled = 0
    var sumSq = 0.0; var peak = 0.0; var since = 0
    val specs = ArrayDeque<Spectrum>(); val levels = ArrayDeque<Double>(); val peaks = ArrayDeque<Double>()
    var frames = 0
    val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
    DataInputStream(p.inputStream.buffered(1 shl 16)).use { input ->
        try {
            while (true) {
                buf.clear(); input.readFully(buf.array()); val l = buf.getFloat(0).toDouble(); val r = buf.getFloat(4).toDouble()
                peak = maxOf(peak, abs(l), abs(r))
                val s = (l + r) / 2
                ring[pos] = s; pos = (pos + 1) % N; if (filled < N) filled++
                sumSq += s * s
                if (++since >= HOP) {
                    val rms = 10 * log10(sumSq / HOP + 1e-20)
                    if (rms >= -80 && filled >= N) {
                        val re = DoubleArray(N) { ring[(pos + it) % N] * HANN[it] }; val im = DoubleArray(N)
                        fft(re, im)
                        val spec = Spectrum.fromFft(DoubleArray(N / 2 + 1) { sqrt(re[it] * re[it] + im[it] * im[it]) }, FS.toDouble())
                        stats.frame(spec, rms)
                        specs.addLast(spec); levels.addLast(rms); peaks.addLast(peak)
                        if (specs.size > 60) { specs.removeFirst(); levels.removeFirst(); peaks.removeFirst() } // the engine's 3 s window
                    }
                    frames++
                    if (frames % 20 == 0 && specs.size >= 30) { // once a second, as the engine does
                        val prof = ContentClassifier.classify(null, SpectralFeatures.from(specs.toList(), levels.toList(), 20.0))
                        stats.second(prof.top.takeIf { prof.confidence >= 0.5 }, null, DensityControl.ceilingShare(peaks.toList()))
                        stats.note("kindConfidence", prof.confidence)
                    }
                    since = 0; sumSq = 0.0; peak = 0.0
                }
            }
        } catch (_: EOFException) { }
    }
    p.waitFor()
    return stats.summary()?.let { linkedMapOf<String, Any?>("source" to f.path) + it }
}

/** In-place radix-2 FFT (the helper's own is the same textbook transform). */
private fun fft(re: DoubleArray, im: DoubleArray) {
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
        val ang = -2 * PI / len
        for (i in 0 until n step len) for (k in 0 until len / 2) {
            val wr = cos(ang * k); val wi = sin(ang * k)
            val ur = re[i + k]; val ui = im[i + k]
            val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
            val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
            re[i + k] = ur + vr; im[i + k] = ui + vi
            re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
        }
        len = len shl 1
    }
}
