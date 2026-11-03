package com.arnavagarwal.sound

import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arnavagarwal.sound.engine.DemoSong
import com.arnavagarwal.sound.engine.EngineService
import com.arnavagarwal.sound.engine.EngineState
import com.arnavagarwal.sound.ui.Words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.log10

/**
 * The app's own demo song (his ask, 22 Sep 22:03): with nothing else playing, "Hear the difference" must work on it.
 * Proven by what it DOES, not by what it announces:
 *  1. the engine sees it as the song playing now, has attached to its audio session, and plans for it;
 *  2. its sound really passes through our chain: the coverage dip (-30 dB for a second) shows on the output mix -
 *     a chain that was announced but not on the demo's audio would leave the level untouched;
 *  3. both challenge sides are published on it (untouched = flat, tuned = the plan);
 *  4. stopping it leaves nothing behind: session closed, nothing playing.
 * Loudness matching itself is measured with a steady tone in ChallengeDeviceTest; music is too uneven for that.
 */
@RunWith(AndroidJUnit4::class)
class DemoSongDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val brain get() = (ctx.applicationContext as SoundApp).brain

    private fun waitFor(what: String, sec: Int, ok: (EngineState) -> Boolean): EngineState {
        repeat(sec * 5) { val s = brain.state.value; if (ok(s)) return s; Thread.sleep(200) }
        val s = brain.state.value
        fail("$what: not reached in ${sec}s. now=${s.now?.title} playing=${s.isPlaying} announced=${s.announced.keys} demo=${DemoSong.audioSessionId} kind=${s.profile?.top}")
        error("unreachable")
    }

    /** Output level (dB, relative) averaged over [ms]. */
    private fun level(v: Visualizer, ms: Long): Double {
        val bytes = ByteArray(v.captureSize); var sum = 0.0; var n = 0
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (v.getWaveForm(bytes) == Visualizer.SUCCESS) { sum += bytes.sumOf { val x = (it.toInt() and 0xFF) - 128; (x * x).toDouble() } / bytes.size; n++ }
            Thread.sleep(40)
        }
        assertTrue("output meter returned no data", n > 3)
        return 10 * log10(sum / n + 1e-9)
    }

    @Test fun demoSongIsTunedAndTestable() {
        val ua = inst.uiAutomation
        for (p in listOf("android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS"))
            runCatching { ua.grantRuntimePermission(ctx.packageName, p) }
        ua.executeShellCommand("cmd notification allow_listener ${ctx.packageName}/com.arnavagarwal.sound.engine.MediaListenerService").close()
        val am = ctx.getSystemService(AudioManager::class.java)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        var viz: Visualizer? = null
        var id = 0
        try {
            EngineService.start(ctx)
            inst.runOnMainSync { DemoSong.start(ctx) }
            id = DemoSong.audioSessionId
            assertTrue("the demo song did not start", id > 0 && DemoSong.playing.value)

            // 1. seen, attached, planned - the same condition the test page waits on before showing A and B
            val s = waitFor("engine on the demo song", 20) {
                it.running && it.isPlaying && it.now?.title == Words.DEMO_TITLE && id in it.announced && it.profile != null
            }
            Log.i(TAG, "demo kind=${s.profile?.top} bands=${s.bandsDb.joinToString { "%.1f".format(it) }}")

            // 2. the dip must show on the output: proof the demo's audio goes through our chain
            viz = openOutputMeter()
            Thread.sleep(1_500)
            val before = level(viz, 1_500)
            brain.coverageDip(); Thread.sleep(150)
            val dipped = level(viz, 600)
            Thread.sleep(1_200)
            val after = level(viz, 1_500)
            val report = "before %.1f dB, during dip %.1f dB, after %.1f dB".format(before, dipped, after)
            Log.i(TAG, report); println("DEMO_RESULT $report")
            assertTrue("the dip did not reach the demo song's sound - our chain is not on it: $report", before - dipped >= 12.0)
            assertTrue("the sound did not come back after the dip: $report", before - after < 6.0)

            // 3. both sides of the challenge are published on it
            brain.challengePlay(false)
            waitFor("untouched side", 5) { it.challenge?.enhancedAudible == false && it.bandsDb.all { b -> b == 0.0 } }
            brain.challengePlay(true)
            waitFor("tuned side", 5) { it.challenge?.enhancedAudible == true }
            brain.challengeEnd()
        } finally {
            runCatching { viz?.enabled = false; viz?.release() }
            inst.runOnMainSync { DemoSong.stop() }
        }
        // 4. nothing left behind
        assertEquals("demo still says playing", false, DemoSong.playing.value)
        waitFor("demo session closed", 5) { id !in it.announced && it.now?.title != Words.DEMO_TITLE }
        EngineService.stop(ctx)
    }

    private companion object { const val TAG = "DemoSongTest" }
}
