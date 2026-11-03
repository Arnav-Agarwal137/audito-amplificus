package com.arnavagarwal.sound.desktop

import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The card's wire, end to end: Denoise.run -> the real cleaner (venv on D:). Both directions: a spoken talk (Windows'
 * own voice, with a hum added) comes back cleaned, and a song stand-in (chords) is refused with no file written.
 * When the cleaner is not installed the test says so in its output rather than passing quietly (7g).
 */
class DenoiseTest {
    private fun installed() = ToolsRoot.python("denoise-venv").isFile

    private fun waitDone(): Denoise.State {
        val end = System.currentTimeMillis() + 300_000
        while (Denoise.state.value is Denoise.State.Working && System.currentTimeMillis() < end) Thread.sleep(200)
        return Denoise.state.value
    }

    @Test fun aNoisyTalkComesBackCleaned() {
        if (!installed()) { println("NOT RUN: noise cleaner not installed"); return }
        val dir = File(System.getProperty("java.io.tmpdir"), "denoise-test-${System.nanoTime()}").apply { mkdirs() }
        val talk = File(dir, "talk.wav")
        val say = "The heart has four chambers. Blood from the body enters the right atrium and passes to the right " +
            "ventricle, which pumps it to the lungs. It returns to the left atrium and the left ventricle pumps it to the body."
        val ps = "Add-Type -AssemblyName System.Speech; \$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
            "\$s.SetOutputToWaveFile('${talk.path}'); \$s.Speak('$say'); \$s.Dispose()"
        ProcessBuilder("powershell", "-NoProfile", "-Command", ps).redirectErrorStream(true).start().waitFor()
        if (!talk.isFile) fail("could not make the spoken test file")
        Denoise.run(talk, Denoise.GENTLE_DB)
        val s = waitDone()
        if (s !is Denoise.State.Done) fail("cleaning did not finish: $s")
        assertTrue(s.file.isFile && s.file.length() > 100_000, "cleaned copy written: ${s.file} ${s.file.length()}")
        assertTrue(talk.isFile, "the original is still there")
        dir.deleteRecursively()
    }

    /** His rule 29 Sep: whatever he picks is cleaned - the old music check refused his own voice-over-fan recording. */
    @Test fun aSongIsCleanedToo() {
        if (!installed()) { println("NOT RUN: noise cleaner not installed"); return }
        val dir = File(System.getProperty("java.io.tmpdir"), "denoise-test-${System.nanoTime()}").apply { mkdirs() }
        val song = File(dir, "chords.wav")
        writeWav(song, 44100, DoubleArray(44100 * 10) { chord(it, 44100) })
        Denoise.run(song, Denoise.GENTLE_DB)
        val s = waitDone()
        if (s !is Denoise.State.Done) fail("a song should be cleaned, not refused: $s")
        assertTrue(s.file.isFile, "cleaned copy written: ${s.file}")
        dir.deleteRecursively()
    }

    /**
     * Voice only must take out what is not the voice. The file opens with 3 s of chords alone, then talk over the
     * chords; in the chords-alone stretch Voice only must leave at least 20 dB less than there was, and at least 6 dB
     * less than Gentle (which only turns noise down by 12). Gentle is run too so the test proves the two differ.
     */
    @Test fun voiceOnlyTakesOutWhatIsNotTheVoice() {
        if (!installed()) { println("NOT RUN: noise cleaner not installed"); return }
        val dir = File(System.getProperty("java.io.tmpdir"), "denoise-test-${System.nanoTime()}").apply { mkdirs() }
        val talk = File(dir, "talk.wav")
        speak(talk, "The kidney filters blood. Each kidney holds about a million nephrons, and each nephron ends in a " +
            "tubule that takes back water and salt the body still needs.")
        val (rate, voice) = readMono(talk)
        val lead = 3 * rate
        val mix = DoubleArray(lead + voice.size) { i -> chord(i, rate) * 0.5 + (if (i >= lead) voice[i - lead] else 0.0) }
        val file = File(dir, "mix.wav")
        writeWav(file, rate, mix)
        val before = powerDb(readMono(file), 0.2, 2.8)

        Denoise.run(file, Denoise.GENTLE_DB)
        val g = waitDone() as? Denoise.State.Done ?: fail("gentle did not finish: ${Denoise.state.value}")
        val gentle = powerDb(readMono(g.file), 0.2, 2.8)
        Denoise.run(file, Denoise.VOICE_ONLY)
        val v = waitDone() as? Denoise.State.Done ?: fail("voice only did not finish: ${Denoise.state.value}")
        assertTrue(v.file.name.contains("voice only"), "voice only has its own name: ${v.file.name}")
        val only = powerDb(readMono(v.file), 0.2, 2.8)
        val talkLeft = powerDb(readMono(v.file), 3.5, 3.0 + voice.size.toDouble() / rate)
        println("chords alone: before $before dB, gentle $gentle dB, voice only $only dB; talk in voice only $talkLeft dB")
        assertTrue(before - only >= 20, "voice only took the chords down ${before - only} dB (want 20+)")
        assertTrue(gentle - only >= 6, "voice only beat gentle by ${gentle - only} dB (want 6+)")
        assertTrue(talkLeft > only + 20, "the talk is still there: $talkLeft dB against $only dB")
        assertTrue(file.isFile, "the original is still there")
        dir.deleteRecursively()
    }

    private fun speak(f: File, say: String) {
        val ps = "Add-Type -AssemblyName System.Speech; \$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
            "\$s.SetOutputToWaveFile('${f.path}'); \$s.Speak('$say'); \$s.Dispose()"
        ProcessBuilder("powershell", "-NoProfile", "-Command", ps).redirectErrorStream(true).start().waitFor()
        if (!f.isFile) fail("could not make the spoken test file")
    }

    private fun chord(i: Int, rate: Int): Double {
        val chords = listOf(listOf(261.6, 329.6, 392.0), listOf(220.0, 261.6, 329.6), listOf(174.6, 220.0, 261.6), listOf(196.0, 246.9, 293.7))
        val c = chords[(i / (rate / 2)) % chords.size]
        val beat = if ((i % (rate / 2)) < rate / 40) 0.3 * sin(2 * PI * 60 * i / rate) else 0.0
        return c.sumOf { 0.12 * sin(2 * PI * it * i / rate) } + beat
    }

    /** Any PCM WAV to (rate, mono samples in -1..1) through Java Sound, channels averaged. */
    private fun readMono(f: File): Pair<Int, DoubleArray> {
        val ais = javax.sound.sampled.AudioSystem.getAudioInputStream(f)
        val fmt = ais.format
        val pcm = javax.sound.sampled.AudioSystem.getAudioInputStream(javax.sound.sampled.AudioFormat(fmt.sampleRate, 16,
            fmt.channels, true, false), ais)
        val b = pcm.readAllBytes()
        val ch = fmt.channels
        val n = b.size / (2 * ch)
        val out = DoubleArray(n) { i ->
            (0 until ch).sumOf { c -> val k = 2 * (i * ch + c); ((b[k].toInt() and 0xff) or (b[k + 1].toInt() shl 8)).toShort() / 32768.0 } / ch
        }
        return fmt.sampleRate.toInt() to out
    }

    private fun powerDb(a: Pair<Int, DoubleArray>, fromS: Double, toS: Double): Double {
        val (rate, x) = a
        val from = (fromS * rate).toInt().coerceIn(0, x.size); val to = (toS * rate).toInt().coerceIn(from, x.size)
        if (to <= from) fail("empty window $fromS-$toS s in a ${x.size.toDouble() / rate} s file")
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return 10 * kotlin.math.log10(s / (to - from) + 1e-12)
    }

    private fun writeWav(f: File, rate: Int, mono: DoubleArray) {
        val data = java.io.ByteArrayOutputStream()
        for (x in mono) {
            val v = (x.coerceIn(-1.0, 1.0) * 32767).toInt()
            repeat(2) { data.write(v and 0xff); data.write((v shr 8) and 0xff) }
        }
        val b = data.toByteArray()
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + b.size).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            .putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(b.size)
        f.writeBytes(h.array() + b)
    }
}
