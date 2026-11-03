package com.arnavagarwal.sound.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Calls are never heard or shaped (research/legal-check.md). */
class CallGuardTest {
    @Test fun callsAndInternetCallsCountMusicAndRingingDoNot() {
        assertTrue(CallGuard.isCall(2))  // MODE_IN_CALL
        assertTrue(CallGuard.isCall(3))  // MODE_IN_COMMUNICATION (WhatsApp, Meet)
        assertTrue(CallGuard.isCall(4))  // MODE_CALL_SCREENING
        assertFalse(CallGuard.isCall(0)) // MODE_NORMAL: music
        assertFalse(CallGuard.isCall(1)) // MODE_RINGTONE: not a conversation yet
    }

    @Test fun theBrainStandsDownBeforeAnythingElseInTheTick() {
        // the guard is only a guard if it is wired: first thing in tick, and it stops the analyzer and the global chain
        val src = File("src/main/java/com/arnavagarwal/sound/engine/Brain.kt").readText()
        val tick = src.substringAfter("private fun tick(dt: Double) {").substringBefore("val soundActive")
        assertTrue("CallGuard.isCall(" in tick && "analyzer.stop()" in tick && "global?.setEnabled(false)" in tick && "if (inCall || inPrivate) return" in tick)
    }
}
