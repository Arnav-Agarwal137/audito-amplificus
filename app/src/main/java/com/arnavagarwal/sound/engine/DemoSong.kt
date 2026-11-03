package com.arnavagarwal.sound.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import com.arnavagarwal.sound.R
import com.arnavagarwal.sound.ui.Words
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's own free song (`res/raw/demo_song`, public domain - `licenses/demo-song.txt`), so "Hear the difference"
 * works when nothing else is playing (his ask, 22 Sep 22:03). It goes through exactly the path any music app does:
 * its audio session is announced (route A) and it shows as a playing media session, so the engine classifies, plans
 * and runs the loudness-matched challenge on it with no special case.
 */
object DemoSong {
    private const val TAG = "DemoSong"
    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private var sessionId = 0

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    /** The audio session it plays on while playing, else 0 (for tests). */
    val audioSessionId: Int get() = if (player != null) sessionId else 0

    fun start(ctx: Context) {
        if (player != null) return
        val app = ctx.applicationContext
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val id = app.getSystemService(AudioManager::class.java).generateAudioSessionId()
        val mp = try { MediaPlayer.create(app, R.raw.demo_song, attrs, id) } catch (t: Throwable) { Log.w(TAG, "could not open the demo song", t); null } ?: return
        mp.isLooping = true
        // announce BEFORE the first sound, so the engine's chain is on it as early as possible
        SessionRegistry.open(id, app.packageName)
        val ms = MediaSession(app, TAG).apply {
            setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, Words.DEMO_TITLE)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, Words.DEMO_ARTIST)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, mp.duration.toLong()).build())
            setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            isActive = true
        }
        player = mp; session = ms; sessionId = id
        mp.start()
        _playing.value = true
        if (Prefs(app).engineOn) EngineService.start(app)
    }

    fun stop() {
        val mp = player ?: return
        player = null
        runCatching { mp.stop() }; mp.release()
        SessionRegistry.close(sessionId)
        session?.let { runCatching { it.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0f).build()) }; it.release() }
        session = null; sessionId = 0
        _playing.value = false
    }
}
