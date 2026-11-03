package com.arnavagarwal.sound.engine

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.arnavagarwal.sound.R
import com.arnavagarwal.sound.ui.MainActivity

/**
 * The pull-down shade's on/off tile (his tap, 1 Nov 2026: "Phone: on/off in pull-down shade").
 * It flips the SAME switch as the wand and the notification button (Prefs.engineOn), so the three never disagree.
 * If Android refuses to start the engine from the shade, the app opens instead; its own screen starts the engine.
 */
class TuningTile : TileService() {
    private var undo: (() -> Unit)? = null

    override fun onStartListening() {
        val prefs = Prefs(this)
        show(prefs.engineOn)
        undo = prefs.watchEngine { show(it) }
    }

    override fun onStopListening() { undo?.invoke(); undo = null }

    override fun onClick() {
        val prefs = Prefs(this)
        if (!prefs.onboarded) { openApp(); return } // setup (the Allow taps) has to happen inside the app first
        val on = TileWords.next(prefs.engineOn)
        prefs.engineOn = on
        if (on) { if (!EngineService.tryStart(this)) openApp() } else EngineService.stop(this)
        show(on)
    }

    private fun show(on: Boolean) {
        val t = qsTile ?: return
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = getString(R.string.launcher_name)
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = TileWords.subtitle(on)
        t.contentDescription = TileWords.spoken(on)
        t.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34)
            startActivityAndCollapse(PendingIntent.getActivity(this, 4, i, PendingIntent.FLAG_IMMUTABLE))
        else startActivityAndCollapse(i)
    }
}

/** The tile's words, kept apart so a plain unit test can read them. */
object TileWords {
    fun next(current: Boolean): Boolean = !current
    fun subtitle(on: Boolean): String = if (on) "Tuning on" else "Tuning off"
    fun spoken(on: Boolean): String = if (on) "Amplificus tuning is on. Tap to turn off." else "Amplificus tuning is off. Tap to turn on."
}
