package com.arnavagarwal.sound.desktop

import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The 8D card's wire, end to end: EightD.run -> the real helper (Demucs venv + KEMAR on D:) -> one new file beside the
 * song. The sound itself is checked by eightd.py --selftest (12 checks); this proves the app reaches it and reads its
 * answer. When the helper is not installed the test says so in its output rather than passing quietly (7g).
 */
class EightDTest {
    @Test fun aSongFileBecomesAn8DCopyBesideIt() {
        if (!ToolsRoot.python("demucs-venv").isFile || !java.io.File(ToolsRoot.dir, "hrtf/elev0").isDirectory) {
            println("NOT RUN: 8D helper not installed"); return
        }
        val dir = File(System.getProperty("java.io.tmpdir"), "eightd-test-${System.nanoTime()}").apply { mkdirs() }
        val song = File(dir, "test song.wav")
        val seconds = 6
        writeWav(song, 44100, seconds)
        EightD.run(song)
        val end = System.currentTimeMillis() + 180_000
        while (EightD.state.value is EightD.State.Working && System.currentTimeMillis() < end) Thread.sleep(200)
        val s = EightD.state.value
        if (s !is EightD.State.Done) fail("8D did not finish: $s")
        assertEquals(File(dir, "test song - 8D.wav").canonicalPath, s.file.canonicalPath)
        val bytes = s.file.length()
        assertTrue(bytes > seconds * 44100 * 4 * 9 / 10, "an 8D copy of $seconds s is only $bytes bytes")
        assertTrue(song.length() == 44L + seconds * 44100 * 4, "the song file itself was changed")
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
