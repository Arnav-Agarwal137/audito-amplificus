package com.arnavagarwal.sound.desktop

import com.arnavagarwal.sound.core.ContentKind
import com.arnavagarwal.sound.core.Grid
import com.arnavagarwal.sound.core.SongStats
import com.arnavagarwal.sound.core.Spectrum
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SongLogTest {
    private fun song(seconds: Int): SongStats {
        val s = SongStats("Anuv Jain - Husn", "chrome")
        repeat(seconds * 20) { s.frame(Spectrum(DoubleArray(Grid.size) { 1.0 }), -20.0) }
        repeat(seconds) { s.second(ContentKind.SMOOTH, 3.0, 0.1); s.note("tuningOn", 1.0); s.note("sliderDb", -10.0) }
        return s
    }

    @Test fun aPlayedSongBecomesOneLineThatParsesBack() {
        val f = Files.createTempDirectory("songlog").resolve("SoundApp/songs.jsonl").toFile()
        val log = SongLog(f)
        log.write(song(40), "Speakers")
        log.write(song(5), "Speakers") // skipped song: no line
        val lines = f.readLines()
        assertEquals(1, lines.size)
        val o = Json.parseToJsonElement(lines[0]).jsonObject
        assertEquals("Anuv Jain - Husn", o["title"]!!.jsonPrimitive.content)
        assertEquals("Speakers", o["device"]!!.jsonPrimitive.content)
        assertEquals(3.0, o["densityDb"]!!.jsonPrimitive.content.toDouble())
        val d = o["decisions"]!!.jsonObject
        assertEquals(1.0, d["tuningOn"]!!.jsonPrimitive.content.toDouble())
        assertEquals(-10.0, d["sliderDb"]!!.jsonPrimitive.content.toDouble())
    }

    @Test fun aLookOnlyWindowWritesNothing() {
        SongLog(null).write(song(40), "Speakers") // must not throw, and has nowhere to write
        val f = Files.createTempDirectory("songlog").resolve("x.jsonl").toFile()
        SongLog(f).write(null, "Speakers")
        assertFalse(f.exists())
    }
}
