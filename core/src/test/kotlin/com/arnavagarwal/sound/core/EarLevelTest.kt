package com.arnavagarwal.sound.core

import com.arnavagarwal.sound.core.EarLevel.Heard.SAME
import com.arnavagarwal.sound.core.EarLevel.Heard.TUNED_LOUDER
import com.arnavagarwal.sound.core.EarLevel.Heard.TUNED_QUIETER
import kotlin.test.Test
import kotlin.test.assertEquals

class EarLevelTest {
    @Test fun louderTurnsTheTunedSideDownOneDbEachTime() {
        val e = EarLevel()
        e.answer(TUNED_LOUDER); e.answer(TUNED_LOUDER)
        assertEquals(-2.0, e.offsetDb, 1e-9)
    }

    @Test fun sameChangesNothing() {
        val e = EarLevel(-1.5)
        e.answer(SAME)
        assertEquals(-1.5, e.offsetDb, 1e-9); assertEquals(1.0, e.step, 1e-9)
    }

    @Test fun aFlipHalvesTheStepSoItClosesIn() {
        val e = EarLevel()
        e.answer(TUNED_LOUDER)   // -1
        e.answer(TUNED_QUIETER)  // flip: step 0.5 -> -0.5
        assertEquals(-0.5, e.offsetDb, 1e-9)
        e.answer(TUNED_LOUDER)   // flip again: step stays at the 0.5 floor -> -1.0
        assertEquals(-1.0, e.offsetDb, 1e-9); assertEquals(EarLevel.MIN_STEP, e.step, 1e-9)
    }

    @Test fun itStaysInItsRange() {
        val e = EarLevel()
        repeat(20) { e.answer(TUNED_LOUDER) }
        assertEquals(EarLevel.MIN, e.offsetDb, 1e-9)
        assertEquals(EarLevel.MAX, EarLevel(99.0).offsetDb, 1e-9)
    }
}
