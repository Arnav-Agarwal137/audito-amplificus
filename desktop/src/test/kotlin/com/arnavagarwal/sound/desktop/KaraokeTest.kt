package com.arnavagarwal.sound.desktop

import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The card's wire, end to end: Karaoke.run -> the real helper (venv on D:) -> two files. A 6 s two-tone WAV stands in
 * for a song. When the helper is not installed the test says so in its output rather than passing quietly (7g).
 */
class KaraokeTest {
    @Test fun aSongFileBecomesMusicAndVoiceFiles() {
        if (!ToolsRoot.python("demucs-venv").isFile) { println("NOT RUN: karaoke helper not installed"); return }
        val dir = File(System.getProperty("java.io.tmpdir"), "karaoke-test-${System.nanoTime()}").apply { mkdirs() }
        val song = File(dir, "test song.wav")
        writeWav(song, 44100, 6)
        Karaoke.run(song)
        val end = System.currentTimeMillis() + 180_000
        while (Karaoke.state.value is Karaoke.State.Working && System.currentTimeMillis() < end) Thread.sleep(200)
        val s = Karaoke.state.value
        if (s !is Karaoke.State.Done) fail("karaoke did not finish: $s")
        val music = File(s.folder, "test song - music only.wav")
        val voice = File(s.folder, "test song - voice only.wav")
        assertTrue(music.length() > 100_000 && voice.length() > 100_000, "both files written: ${music.length()} ${voice.length()}")
        dir.deleteRecursively()
    }

    private fun writeWav(f: File, rate: Int, seconds: Int) {
        val n = rate * seconds
        val data = java.io.ByteArrayOutputStream()
        for (i in 0 until n) {
            val x = 0.2 * sin(2 * PI * 220 * i / rate) + 0.1 * sin(2 * PI * 1000 * i / rate)
            val v = (x * 32767).toInt()
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
