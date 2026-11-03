package com.arnavagarwal.sound

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.audiofx.AudioEffect
import android.media.audiofx.Visualizer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arnavagarwal.sound.core.ContentKind
import com.arnavagarwal.sound.core.Curve
import com.arnavagarwal.sound.core.Loudness
import com.arnavagarwal.sound.core.Speaker
import com.arnavagarwal.sound.engine.Output
import com.arnavagarwal.sound.engine.EngineService
import com.arnavagarwal.sound.engine.EngineState
import com.arnavagarwal.sound.engine.SessionReceiver
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * "Is the challenge working?" (his question, 22 Sep) - answered by MEASURING the sound, not by reading the code.
 *
 * The real engine plays a bass-heavy "song" through the real chain. For each side of the blind test, a
 * Visualizer on the output mix (session 0: it hears a session's chain AFTER our effect, E-003) records what
 * actually comes out. Two things must both hold, or the test is meaningless:
 *  1. the tuned side really is SHAPED differently (bass against the 440 Hz tone moves by several dB), and
 *  2. both sides are EQUALLY LOUD (K-weighted, the way Loudness weighs it) - otherwise louder wins and the
 *     result is fake.
 * Plus the published numbers: untouched = every band 0 and no gain change.
 */
@RunWith(AndroidJUnit4::class)
class ChallengeDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val brain get() = (ctx.applicationContext as SoundApp).brain
    private val fs = 48_000

    private fun waitFor(what: String, sec: Int, ok: (EngineState) -> Boolean): EngineState {
        repeat(sec * 5) { val s = brain.state.value; if (ok(s)) return s; Thread.sleep(200) }
        val s = brain.state.value
        fail("$what: not reached in ${sec}s. kind=${s.profile?.top} challenge=${s.challenge} bands=${s.bandsDb.joinToString { "%.1f".format(it) }}")
        error("unreachable")
    }

    /** What came out: K-weighted loudness (dB, relative units) and bass-vs-440 Hz balance (dB), averaged over ~1.5 s. */
    private fun measure(v: Visualizer, speaker: Boolean): Pair<Double, Double> {
        Thread.sleep(800) // let the side switch reach the audio
        val size = v.captureSize
        val bytes = ByteArray(size)
        val binHz = fs.toDouble() / size
        // on the phone speaker, loudness is judged on what the speaker can play (Speaker.response): the deep bass the
        // speaker plan takes out is inaudible there, so a full-range meter would call the tuned side quieter than it sounds
        val kWeight = if (speaker) Curve(Loudness.kWeightDb) + Speaker.response else Curve(Loudness.kWeightDb)
        var loud = 0.0; var bass = 0.0; var mid = 0.0; var got = 0
        repeat(30) {
            if (v.getFft(bytes) == Visualizer.SUCCESS) {
                fun mag(k: Int) = hypot(bytes[2 * k].toDouble(), bytes[2 * k + 1].toDouble())
                var p = 0.0
                for (k in 1 until size / 2) p += mag(k).pow(2) * 10.0.pow(kWeight.at(k * binHz) / 10)
                loud += p
                bass += (1..3).sumOf { mag(it).pow(2) }                 // 47-141 Hz: the 55 and 110 Hz tones
                val k440 = Math.round(440 / binHz).toInt()
                mid += (k440 - 1..k440 + 1).sumOf { mag(it).pow(2) }
                got++
            }
            Thread.sleep(50)
        }
        assertTrue("Visualizer returned no data", got > 10 && loud > 0 && mid > 0)
        return 10 * log10(loud / got) to 10 * log10(bass / mid)
    }

    @Test fun bothSidesAreEquallyLoudButOnlyOneIsTuned() {
        val ua = inst.uiAutomation
        for (p in listOf("android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS", "android.permission.BLUETOOTH_CONNECT"))
            runCatching { ua.grantRuntimePermission(ctx.packageName, p) }
        ua.executeShellCommand("cmd notification allow_listener ${ctx.packageName}/com.arnavagarwal.sound.engine.MediaListenerService").close()
        val am = ctx.getSystemService(AudioManager::class.java)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)

        // quiet enough (peak about -12 dBFS) that the tuned side never reaches the limiter: a limiter squashing
        // the boost would make an UNMATCHED side look matched (seen: an unmatched run read only +1.3 dB louder)
        val track = player(listOf(55.0 to 3000.0, 110.0 to 2500.0, 440.0 to 1500.0))
        val session = MediaSession(ctx, "challenge")
        var viz: Visualizer? = null
        try {
            track.play()
            SessionReceiver().onReceive(ctx, Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.audioSessionId).putExtra(AudioEffect.EXTRA_PACKAGE_NAME, ctx.packageName))
            EngineService.start(ctx)
            session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Bhangra night")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Some Artist").putString(MediaMetadata.METADATA_KEY_GENRE, "Punjabi")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 200_000L).build())
            session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            session.isActive = true
            waitFor("bass-heavy song tuned", 25) { it.profile?.top == ContentKind.BASS_HEAVY && track.audioSessionId in it.announced && it.bandsDb.max() > 2.0 }
            Thread.sleep(3_000) // let the analyzer's average settle on this song, so the loudness match uses its real spectrum

            viz = openOutputMeter()
            val speaker = brain.state.value.output.kind == Output.Kind.SPEAKER

            brain.challengePlay(false)
            val plain = waitFor("untouched side published", 5) { s -> s.challenge?.enhancedAudible == false && s.bandsDb.all { abs(it) < 0.01 } && abs(s.preampDb) < 0.01 }
            val (loudPlain, balPlain) = measure(viz, speaker)

            brain.challengePlay(true)
            val tuned = waitFor("tuned side published", 5) { s -> s.challenge?.enhancedAudible == true && s.bandsDb.max() > 2.0 }
            val (loudTuned, balTuned) = measure(viz, speaker)

            // and back again: the difference must follow the switch, not drift with time
            brain.challengePlay(false)
            val (loudPlain2, balPlain2) = measure(viz, speaker)

            val report = ("speaker=$speaker | untouched: loud %.2f bal %.2f | tuned: loud %.2f bal %.2f (match gain %.2f dB, max band %.1f) | untouched again: loud %.2f bal %.2f")
                .format(loudPlain, balPlain, loudTuned, balTuned, tuned.challenge!!.matchGainDb, tuned.bandsDb.max(), loudPlain2, balPlain2)
            Log.i(TAG, report); println("CHALLENGE_RESULT $report")
            assertTrue("published untouched side is not flat: $plain", plain.bandsDb.all { it == 0.0 })
            // earbuds: the bass tones go UP; the speaker: they go DOWN (it cannot play 55/110 Hz, the plan moves that bass up)
            val dir = if (speaker) -1.0 else 1.0
            assertTrue("the tuned side is not shaped differently: $report", dir * (balTuned - balPlain) >= 2.0 && dir * (balTuned - balPlain2) >= 2.0)
            assertTrue("the two sides are not equally loud: $report", abs(loudTuned - loudPlain) <= 1.0 && abs(loudTuned - loudPlain2) <= 1.0)
            assertTrue("the tuned side was not turned down to match: $report", tuned.challenge!!.matchGainDb < -0.5)
        } finally {
            brain.challengeEnd()
            runCatching { viz?.enabled = false; viz?.release() }
            session.release(); track.stop(); track.release()
            EngineService.stop(ctx)
        }
    }

    /** A looped one-second mix of sine tones (Hz to amplitude); whole cycles, so the loop has no click. */
    private fun player(tones: List<Pair<Double, Double>>): AudioTrack {
        val pcm = ShortArray(fs) { i -> tones.sumOf { (f, amp) -> amp * sin(2 * PI * f * i / fs) }.coerceIn(-32767.0, 32767.0).toInt().toShort() }
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(fs).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            .apply { write(pcm, 0, pcm.size); setLoopPoints(0, pcm.size, -1) }
    }

    private companion object { const val TAG = "ChallengeTest" }
}
