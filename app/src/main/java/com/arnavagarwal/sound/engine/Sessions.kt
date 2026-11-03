package com.arnavagarwal.sound.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** An audio session some app announced, and which app it belongs to. */
data class AnnouncedSession(val sessionId: Int, val packageName: String?, val sinceMs: Long)

/**
 * Keeps the set of sessions music apps have announced (route A, ARCHITECTURE.md). The receiver can
 * fire while the engine is stopped, so the set lives here and the engine attaches when it starts.
 */
object SessionRegistry {
    private val _sessions = MutableStateFlow<Map<Int, AnnouncedSession>>(emptyMap())
    val sessions: StateFlow<Map<Int, AnnouncedSession>> = _sessions

    /** Every package that has EVER announced a session: shown in the coverage panel as "works". */
    private val _seenPackages = MutableStateFlow<Set<String>>(emptySet())
    val seenPackages: StateFlow<Set<String>> = _seenPackages

    fun open(id: Int, pkg: String?) {
        if (id == AudioEffect.ERROR_BAD_VALUE || id <= 0) return
        _sessions.value = _sessions.value + (id to AnnouncedSession(id, pkg, System.currentTimeMillis()))
        if (pkg != null) _seenPackages.value = _seenPackages.value + pkg
    }

    fun close(id: Int) { _sessions.value = _sessions.value - id }

    fun restoreSeen(pkgs: Set<String>) { _seenPackages.value = _seenPackages.value + pkgs }

    fun handle(intent: Intent) {
        val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
        val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> open(id, pkg)
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> close(id)
        }
    }
}

/** Manifest receiver: catches announcements even before the engine runs, then wakes the engine. */
class SessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SessionRegistry.handle(intent)
        if (intent.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION && Prefs(context).engineOn) {
            EngineService.start(context)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs(context).engineOn) EngineService.start(context)
    }
}
