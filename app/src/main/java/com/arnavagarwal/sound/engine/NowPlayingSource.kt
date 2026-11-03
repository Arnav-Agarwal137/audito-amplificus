package com.arnavagarwal.sound.engine

import android.content.ComponentName
import android.content.Context
import android.app.Notification
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.arnavagarwal.sound.core.NowPlaying
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The system binds this when the user grants "notification access". We never read messages; being a listener is
 * what lets us ask MediaSessionManager which app is playing what (research/android-feasibility.md, point 5). The one
 * notification looked at is the playing app's own media notification, only to see whether it is private
 * (PrivatePlayback).
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        NowPlayingSource.connect(this)
        // The system re-binds this listener whenever our process comes back after being killed, including kills
        // that cancel START_STICKY (phone battery managers, force-stops). So this is where tuning heals itself (E-008).
        if (Prefs(this).engineOn) EngineService.start(this)
    }
    override fun onListenerDisconnected() { NowPlayingSource.disconnect() }
    // a tab going incognito changes only its media notification, not always the session: look again
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        if (sbn?.notification?.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true) NowPlayingSource.refresh()
    }
}

/** [isPrivate]: incognito / private playback; then [now] is null, so nothing about it is shown or remembered. */
data class PlayingState(val now: NowPlaying?, val isPlaying: Boolean, val positionMs: Long, val isPrivate: Boolean = false)

object NowPlayingSource {
    private val _state = MutableStateFlow(PlayingState(null, false, 0))
    val state: StateFlow<PlayingState> = _state

    private var manager: MediaSessionManager? = null
    private var service: NotificationListenerService? = null
    private var active: MediaController? = null
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list.orEmpty()) }
    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() { active = null; publish() }
    }

    fun hasAccess(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { ComponentName.unflattenFromString(it)?.packageName == ctx.packageName }
    }

    fun connect(ctx: Context) {
        val m = ctx.getSystemService(MediaSessionManager::class.java) ?: return
        manager = m
        service = ctx as? NotificationListenerService
        val comp = ComponentName(ctx, MediaListenerService::class.java)
        try {
            m.addOnActiveSessionsChangedListener(listener, comp)
            pick(m.getActiveSessions(comp))
        } catch (_: SecurityException) { /* access revoked between check and call */ }
    }

    fun disconnect() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(listener) }
        active?.unregisterCallback(cb); active = null; service = null
        publish()
    }

    /** Prefer the session that is actually playing; fall back to the first (most recent). */
    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != active?.sessionToken) {
            active?.unregisterCallback(cb)
            active = best
            best?.registerCallback(cb)
        }
        publish()
    }

    fun refresh() = publish()

    /** The playing app's own media notification, if the listener can see it. */
    private fun mediaNotification(pkg: String): Notification? = runCatching {
        service?.activeNotifications?.firstOrNull {
            it.packageName == pkg && it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        }?.notification
    }.getOrNull()

    private fun publish() {
        val c = active
        if (c == null) { _state.value = PlayingState(null, false, 0); return }
        val md = c.metadata
        val np = NowPlaying(
            packageName = c.packageName,
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            genre = md?.getString(MediaMetadata.METADATA_KEY_GENRE),
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
        )
        val ps = c.playbackState
        val n = mediaNotification(c.packageName)
        val texts = listOf(np.title, np.artist, np.album) + listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT)
            .map { n?.extras?.getCharSequence(it)?.toString() }
        val priv = PrivatePlayback.isPrivate(c.packageName, n?.visibility, texts)
        _state.value = PlayingState(if (priv) null else np, ps?.state == PlaybackState.STATE_PLAYING, ps?.position ?: 0, priv)
    }
}
