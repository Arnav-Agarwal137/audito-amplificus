package com.arnavagarwal.sound.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.arnavagarwal.sound.R
import com.arnavagarwal.sound.SoundApp
import com.arnavagarwal.sound.ui.MainActivity
import com.arnavagarwal.sound.ui.Words
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the engine alive while the screen is off. Its ongoing notification says, in one line, what the
 * app is doing right now ("Tuning for talk / lecture"), so the processing is never invisible.
 */
class EngineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val brain get() = (application as SoundApp).brain
    /** Keeps the notification line current. Cancelled BEFORE the paused notification goes up, or it re-posts "Turn off" over it. */
    private var lineJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs(this).engineOn = false
            lineJob?.cancel(); lineJob = null
            brain.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            channels(this)
            // leave a Turn on button behind, so switching back never means leaving the song for the app (his ask, 7 Oct 2026)
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTE_ID, paused()) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) Prefs(this).engineOn = true // the notification's Turn on: the same switch as the wand
        channels(this)
        try {
            ServiceCompat.startForeground(this, NOTE_ID, ongoing("Starting..."),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch (t: Throwable) {
            // Android 12+ refuses a foreground start from the background in some states; give up quietly, the next open retries
            Log.w(TAG, "startForeground refused: ${t.message}")
            stopSelf(); return START_NOT_STICKY
        }
        brain.onAlert = { text -> alert(text) }
        brain.start()
        lineJob?.cancel()
        lineJob = scope.launch {
            brain.state.map { line(it) }.distinctUntilChanged().collect {
                getSystemService(NotificationManager::class.java).notify(NOTE_ID, ongoing(it))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun line(s: EngineState): String = Words.notificationLine(
        running = s.running, testing = s.challenge != null, playing = s.isPlaying, kind = s.profile?.top,
        tooLoud = s.advice?.let { it.currentDb > it.targetDb + 6 } == true)

    private fun ongoing(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, EngineService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH_ENGINE)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(open)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(folded(text, Words.NOTE_TURN_OFF, stop))
            .addAction(0, Words.NOTE_TURN_OFF, stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Shown while off: swipeable, silent, one Turn on button. Same id as [ongoing], so turning on replaces it in place. */
    private fun paused(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // a notification button may start a foreground service from the background (Android's own exemption)
        val on = PendingIntent.getForegroundService(this, 3, Intent(this, EngineService::class.java).setAction(ACTION_START), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH_ENGINE)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(Words.NOTE_PAUSED)
            .setOngoing(false).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(open)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(folded(Words.NOTE_PAUSED, Words.NOTE_TURN_ON, on))
            .addAction(0, Words.NOTE_TURN_ON, on)
            .build()
    }

    /** The folded view: the line plus the same button, because a silent notification hides its action row until expanded. */
    private fun folded(text: String, button: String, tap: PendingIntent) = RemoteViews(packageName, R.layout.note_collapsed).apply {
        setTextViewText(R.id.note_line, text)
        setTextViewText(R.id.note_toggle, button)
        setOnClickPendingIntent(R.id.note_toggle, tap)
    }

    private fun alert(text: String) {
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle("Easy on the volume")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(open)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(ALERT_ID, n) } // no POST_NOTIFICATIONS: silently nothing
    }

    companion object {
        private const val TAG = "EngineService"
        const val CH_ENGINE = "engine"
        const val CH_ALERTS = "alerts"
        private const val NOTE_ID = 1
        private const val ALERT_ID = 2
        private const val ACTION_STOP = "com.arnavagarwal.sound.STOP"
        private const val ACTION_START = "com.arnavagarwal.sound.START"

        fun channels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_ENGINE, ctx.getString(R.string.channel_engine), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_ALERTS, ctx.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH))
        }

        /** Safe from anywhere: a refused background start is logged, never thrown. */
        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, EngineService::class.java)) }
            catch (t: Throwable) { Log.w(TAG, "could not start engine: ${t.message}") }
        }

        /** Like [start], but says whether Android accepted it (the shade tile opens the app when it did not). */
        fun tryStart(ctx: Context): Boolean =
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, EngineService::class.java)); true }
            catch (t: Throwable) { Log.w(TAG, "could not start engine: ${t.message}"); false }

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, EngineService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
