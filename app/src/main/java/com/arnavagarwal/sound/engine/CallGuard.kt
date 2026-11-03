package com.arnavagarwal.sound.engine

import android.media.AudioManager

/**
 * Calls are never touched (legal check, research/legal-check.md, 29 Sep): while a phone call or an internet call
 * (WhatsApp, Meet...) is on, the Visualizer stops measuring and the global chain is switched off, so the app neither
 * hears nor shapes a conversation. Read from AudioManager.getMode, which needs no permission.
 */
object CallGuard {
    /** MODE_IN_CALL, MODE_IN_COMMUNICATION, and 4/5/6 = MODE_CALL_SCREENING / CALL_REDIRECT / COMMUNICATION_REDIRECT. */
    private val CALL_MODES = setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, 4, 5, 6)

    fun isCall(mode: Int): Boolean = mode in CALL_MODES
}
