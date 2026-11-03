package com.arnavagarwal.sound.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The paid-copy switch (Edition.kt, his ask 8 Oct 2026, research/revenue-check.md): a PAID copy must neither show nor run
 * karaoke (Demucs weights, research-only) or clean a talk (DeepFilterNet weights, licence unstated).
 */
class EditionTest {
    @Test fun anythingButFreeIsPaid() {
        assertEquals(Edition.Kind.FREE, Edition.parse(null)) // his laptop: nothing set
        assertEquals(Edition.Kind.FREE, Edition.parse("free"))
        assertEquals(Edition.Kind.FREE, Edition.parse(" FREE "))
        for (v in listOf("paid", "Paid", "pay", "commercial", "", "fre")) assertEquals(Edition.Kind.PAID, Edition.parse(v), v)
        assertTrue(Edition.researchModelsAllowed(Edition.Kind.FREE))
        assertTrue(!Edition.researchModelsAllowed(Edition.Kind.PAID))
    }

    /** Hiding the card is not enough: the run itself refuses, before any helper is looked for. */
    @Test fun aPaidCopyRefusesToRunTheResearchOnlyTools() {
        val before = System.getProperty("audito.edition")
        System.setProperty("audito.edition", "paid")
        try {
            Karaoke.run(File("no-such-song.wav"))
            assertEquals(Karaoke.State.Failed(Edition.NOT_IN_PAID), Karaoke.state.value)
            Denoise.run(File("no-such-talk.wav"), Denoise.GENTLE_DB)
            assertEquals(Denoise.State.Failed(Edition.NOT_IN_PAID), Denoise.state.value)
        } finally {
            if (before == null) System.clearProperty("audito.edition") else System.setProperty("audito.edition", before)
        }
    }

    /** Both cards in the window sit behind the switch; 8D copy (our own code + MIT KEMAR) does not. */
    @Test fun theWindowHidesBothCardsBehindTheSwitch() {
        val lines = File("src/main/kotlin/com/arnavagarwal/sound/desktop/Main.kt").readLines()
        fun cardLine(marker: String) = lines[lines.indexOfFirst { marker in it } - 1]
        assertTrue("Edition.researchModelsAllowed()" in cardLine("Karaoke.state.collectAsState"), cardLine("Karaoke.state.collectAsState"))
        assertTrue("Edition.researchModelsAllowed()" in cardLine("Denoise.state.collectAsState"), cardLine("Denoise.state.collectAsState"))
        assertTrue("Edition" !in cardLine("EightD.state.collectAsState"))
    }

    /** The installed copy and gradle's run pass the same switch name the code reads. */
    @Test fun theBuildPassesTheSwitchTheCodeReads() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue("-Daudito.edition=" in gradle && "auditoEdition" in gradle)
    }
}
