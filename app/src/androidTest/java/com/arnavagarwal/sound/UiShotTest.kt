package com.arnavagarwal.sound

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.audiofx.AudioEffect
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arnavagarwal.sound.engine.NowPlayingSource
import com.arnavagarwal.sound.engine.SessionReceiver
import com.arnavagarwal.sound.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A pretend song for screenshots and for the end-to-end check "a playing app is seen and named".
 * The session belongs to this app's own process, which is exactly how any player app looks to the
 * engine - nothing here is special to one brand. Hold time comes from the "holdSec" argument so the
 * host can take screenshots while it plays; the check itself does not depend on it.
 */
@RunWith(AndroidJUnit4::class)
class UiShotTest {
    @Test fun aPlayingSongIsSeenAndShown() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val ua = inst.uiAutomation
        for (p in listOf("android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS", "android.permission.BLUETOOTH_CONNECT"))
            runCatching { ua.grantRuntimePermission(ctx.packageName, p) }
        ua.executeShellCommand("cmd notification allow_listener ${ctx.packageName}/com.arnavagarwal.sound.engine.MediaListenerService").close()

        val session = MediaSession(ctx, "shot").apply {
            setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Tum Hi Ho")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Arijit Singh")
                .putString(MediaMetadata.METADATA_KEY_GENRE, "Bollywood")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 262_000).build())
            setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 30_000, 1f).build())
            isActive = true
        }
        // only when screenshots are being taken: a quiet real sound (a voice-range tone over a low boom), so the
        // screens that need a MEASUREMENT - the voice-clarity bar - have something to measure
        val hold = InstrumentationRegistry.getArguments().getString("holdSec")?.toLongOrNull() ?: 0
        val track = if (hold > 0) tone().also {
            it.play()
            SessionReceiver().onReceive(ctx, Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, it.audioSessionId).putExtra(AudioEffect.EXTRA_PACKAGE_NAME, ctx.packageName))
        } else null
        try {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            var seen = false
            for (i in 0 until 50) { // up to 10 s for the listener to bind and pick the session
                val s = NowPlayingSource.state.value
                if (s.isPlaying && s.now?.title == "Tum Hi Ho") { seen = true; break }
                Thread.sleep(200)
            }
            assertTrue("the engine never saw the pretend song", seen)
            assertEquals("Arijit Singh", NowPlayingSource.state.value.now?.artist)
            Thread.sleep(hold * 1000)
        } finally {
            session.release()
            track?.run { stop(); release() }
        }
    }

    private fun tone(): AudioTrack {
        val fs = 48_000
        val mix = listOf(110.0 to 2500.0, 250.0 to 2000.0, 1_500.0 to 700.0, 3_000.0 to 400.0)
        val pcm = ShortArray(fs) { i -> mix.sumOf { (f, a) -> a * kotlin.math.sin(2 * Math.PI * f * i / fs) }.toInt().toShort() }
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(fs).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            .apply { write(pcm, 0, pcm.size); setLoopPoints(0, pcm.size, -1) }
    }
}
