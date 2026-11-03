package com.arnavagarwal.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arnavagarwal.sound.engine.EngineService
import com.arnavagarwal.sound.engine.EngineState
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/**
 * CallGuard on a real engine, with music that KEEPS PLAYING through the call (research/legal-check.md round 2:
 * a paused song lets the engine rest on its own, so a fake call with a paused song could not show the guard did it).
 * The test is the "calling app": it switches the phone into internet-call mode itself.
 * Must go red when CallGuard.isCall is forced to false (mutant run, RESUME-HERE).
 */
@RunWith(AndroidJUnit4::class)
class CallGuardDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val brain get() = (ctx.applicationContext as SoundApp).brain
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun waitFor(what: String, sec: Int, ok: (EngineState) -> Boolean) {
        repeat(sec * 5) { if (ok(brain.state.value)) return; Thread.sleep(200) }
        val s = brain.state.value
        fail("$what: not reached in ${sec}s. inCall=${s.inCall} meter=${s.analyzerOn} global=${s.globalActive} mode=${audio.mode} music=${audio.isMusicActive}")
    }

    @Test fun meterAndWholePhoneEffectStandDownForACallWhileMusicPlaysOn() {
        runCatching { inst.uiAutomation.grantRuntimePermission(ctx.packageName, "android.permission.RECORD_AUDIO") }
        val rate = 48_000
        val pcm = ShortArray(rate) { i -> (6000 * sin(2 * PI * 440.0 * i / rate)).toInt().toShort() }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            .apply { write(pcm, 0, pcm.size); setLoopPoints(0, pcm.size, -1) }
        try {
            track.play()
            EngineService.start(ctx)
            waitFor("before the call: meter on", 20) { it.running && it.analyzerOn && !it.inCall }

            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            waitFor("during the call (music still playing): meter off, whole-phone effect off", 10) {
                it.inCall && !it.analyzerOn && !it.globalActive
            }
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) fail("the music stopped, so this did not test music playing on")

            audio.mode = AudioManager.MODE_NORMAL
            waitFor("after the call: meter back", 10) { !it.inCall && it.analyzerOn }
        } finally {
            audio.mode = AudioManager.MODE_NORMAL
            track.stop(); track.release()
            EngineService.stop(ctx)
        }
    }
}
