package com.arnavagarwal.sound.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pull-down shade tile (his tap, 1 Nov 2026). */
class TuningTileTest {
    @Test fun manifestDeclaresTheTileSoOnlyTheSystemCanBindIt() {
        val m = File("src/main/AndroidManifest.xml").readText()
        val block = m.substringAfter("android:name=\".engine.TuningTile\"").substringBefore("</service>")
        assertTrue("android.permission.BIND_QUICK_SETTINGS_TILE" in block, block)
        assertTrue("android.service.quicksettings.action.QS_TILE" in block, block)
        assertTrue("TOGGLEABLE_TILE" in block, block)
        assertFalse("android.permission.INTERNET" in m)
    }

    @Test fun tapFlipsAndWordsSayTheState() {
        assertTrue(TileWords.next(false)); assertFalse(TileWords.next(true))
        assertEquals("Tuning on", TileWords.subtitle(true))
        assertEquals("Tuning off", TileWords.subtitle(false))
        assertTrue("off" in TileWords.spoken(false) && "turn on" in TileWords.spoken(false))
    }

    @Test fun tileUsesTheSameSwitchAsTheWand() {
        val src = File("src/main/java/com/arnavagarwal/sound/engine/TuningTile.kt").readText()
        assertTrue("prefs.engineOn = on" in src && "watchEngine" in src, "the tile must flip and follow Prefs.engineOn")
        assertTrue("onboarded" in src, "before setup, a tap must open the app, not start a half-set-up engine")
    }
}
