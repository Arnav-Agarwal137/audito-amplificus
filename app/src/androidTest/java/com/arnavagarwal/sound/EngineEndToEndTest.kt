package com.arnavagarwal.sound

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.audiofx.AudioEffect
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arnavagarwal.sound.core.ContentKind
import com.arnavagarwal.sound.engine.EngineService
import com.arnavagarwal.sound.engine.EngineState
import com.arnavagarwal.sound.engine.Output
import com.arnavagarwal.sound.engine.SessionReceiver
import com.arnavagarwal.sound.ui.Words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/**
 * The whole engine, the way a phone runs it: the real service and [com.arnavagarwal.sound.engine.Brain],
 * a real audio session announced through the real receiver, real media-session metadata read through
 * the real notification listener. This test plays the part of "some player app" - nothing here is special
 * to one brand. What it proves: the engine sees what plays, sorts it, and the sound it publishes (the
 * same numbers its 20 Hz loop pushes to the effect chains) moves the right way for each kind.
 * What it does NOT prove: that the chain changes the audio - that is DspPathTest, measured.
 */
@RunWith(AndroidJUnit4::class)
class EngineEndToEndTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val brain get() = (ctx.applicationContext as SoundApp).brain

    private fun waitFor(what: String, sec: Int, ok: (EngineState) -> Boolean): EngineState {
        repeat(sec * 5) { val s = brain.state.value; if (ok(s)) return s; Thread.sleep(200) }
        val s = brain.state.value
        fail("$what: not reached in ${sec}s. now=${s.now?.title} kind=${s.profile?.top} why=${s.profile?.reason} announced=${s.announced.keys} bands=${s.bandsDb.joinToString { "%.1f".format(it) }}")
        error("unreachable")
    }

    private fun meta(title: String, artist: String, genre: String, ms: Long) = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, title).putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
        .putString(MediaMetadata.METADATA_KEY_GENRE, genre).putLong(MediaMetadata.METADATA_KEY_DURATION, ms).build()

    private fun announce(action: String, id: Int) = SessionReceiver().onReceive(ctx, Intent(action)
        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, id).putExtra(AudioEffect.EXTRA_PACKAGE_NAME, ctx.packageName))

    @Test fun engineSeesSortsAndShapesWhatPlays() {
        val ua = inst.uiAutomation
        for (p in listOf("android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS", "android.permission.BLUETOOTH_CONNECT"))
            runCatching { ua.grantRuntimePermission(ctx.packageName, p) }
        ua.executeShellCommand("cmd notification allow_listener ${ctx.packageName}/com.arnavagarwal.sound.engine.MediaListenerService").close()

        // a player: a looped signal on its own audio session. The engine LISTENS as well as reading the title,
        // and trusts the sound over the words (a pure 440 Hz "Punjabi" song was rightly called smooth: bass 3%),
        // so each pretend item must sound like what its label says.
        val track = player(listOf(440.0 to 3000.0), null)
        var song: AudioTrack? = null
        val session = MediaSession(ctx, "e2e")
        try {
            track.play()
            announce(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, track.audioSessionId)
            EngineService.start(ctx)

            // 1. a lecture
            session.setMetadata(meta("Physiology lecture 5: the cardiac cycle", "Dr Rao", "Education", 50 * 60_000L))
            session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            session.isActive = true
            val talk = waitFor("lecture seen, sorted as speech, voices lifted over bass", 20) { s ->
                val p = Words.parts(s.bandsDb, s.output.kind == Output.Kind.SPEAKER)
                s.running && s.isPlaying && s.now?.title?.startsWith("Physiology") == true &&
                    s.profile?.top == ContentKind.SPEECH && track.audioSessionId in s.announced && p.voice > p.bass + 1.0
            }
            val pt = Words.parts(talk.bandsDb, talk.output.kind == Output.Kind.SPEAKER)
            Log.i(TAG, "lecture: kind=${talk.profile?.top} bass=%.2f voice=%.2f treble=%.2f preamp=%.2f".format(pt.bass, pt.voice, pt.treble, talk.preampDb))
            assertEquals("Tuning for talk / lecture", Words.notificationLine(talk.running, talk.challenge != null, talk.isPlaying, talk.profile?.top, false))

            // 2. the same app switches to a bass-heavy song: the engine must follow, without a restart
            track.pause()
            song = player(listOf(55.0 to 9000.0, 110.0 to 7000.0, 440.0 to 1500.0), track.audioSessionId).also { it.play() }
            session.setMetadata(meta("Bhangra night", "Some Artist", "Punjabi", 3 * 60_000L + 20_000))
            val sung = waitFor("song re-sorted as bass-heavy, bass lifted over voice", 20) { s ->
                val p = Words.parts(s.bandsDb, s.output.kind == Output.Kind.SPEAKER)
                s.now?.title == "Bhangra night" && s.profile?.top == ContentKind.BASS_HEAVY && p.bass > p.voice + 1.0
            }
            val ps = Words.parts(sung.bandsDb, sung.output.kind == Output.Kind.SPEAKER)
            Log.i(TAG, "song: kind=${sung.profile?.top} why=" + sung.profile?.reason + " bass=%.2f voice=%.2f treble=%.2f preamp=%.2f".format(ps.bass, ps.voice, ps.treble, sung.preampDb))
            assertTrue("bass must rise from lecture to song (${pt.bass} -> ${ps.bass})", ps.bass > pt.bass + 1.0)

            // 3. the player closes its session: the engine lets go of it
            announce(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION, track.audioSessionId)
            waitFor("closed session released", 5) { track.audioSessionId !in it.announced }

            // 4. playback stops: the screen and notification say so
            session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0, 0f).build())
            val idle = waitFor("pause noticed", 10) { !it.isPlaying }
            assertEquals("On. Waiting for something to play.", Words.notificationLine(idle.running, false, idle.isPlaying, idle.profile?.top, false))
        } finally {
            session.release(); track.stop(); track.release(); song?.run { stop(); release() }
            EngineService.stop(ctx)
        }
    }

    /** A looped one-second mix of sine tones (Hz to amplitude), on [sessionId] when given so the same "app" keeps its session. */
    private fun player(tones: List<Pair<Double, Double>>, sessionId: Int?): AudioTrack {
        val rate = 48_000
        val pcm = ShortArray(rate) { i -> tones.sumOf { (f, amp) -> amp * sin(2 * PI * f * i / rate) }.coerceIn(-32767.0, 32767.0).toInt().toShort() }
        val b = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2)
        if (sessionId != null) b.setSessionId(sessionId)
        return b.build().apply { write(pcm, 0, pcm.size); setLoopPoints(0, pcm.size, -1) }
    }

    private companion object { const val TAG = "EngineE2E" }
}
