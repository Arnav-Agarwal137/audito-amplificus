package com.arnavagarwal.sound

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.Visualizer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.arnavagarwal.sound.core.Curve
import com.arnavagarwal.sound.core.ContentKind
import com.arnavagarwal.sound.core.ContentProfile
import com.arnavagarwal.sound.core.Mixer
import com.arnavagarwal.sound.core.Tone
import com.arnavagarwal.sound.core.UserSwitches
import com.arnavagarwal.sound.core.DEFAULT_BAND_CUTOFFS
import com.arnavagarwal.sound.core.FilterType
import com.arnavagarwal.sound.core.ParametricFilter
import com.arnavagarwal.sound.engine.EffectChain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

/**
 * PROOF THE SOUND CHANGES. Claude cannot hear, so the DSP path is checked by measurement:
 * three tones play in this test's own audio session; the real [EffectChain] (the one the app ships)
 * is attached; a Visualizer on the output mix (session 0) reads the processed spectrum.
 *
 * Levels are always compared RELATIVE to the 1 kHz tone, so a change in overall volume or in the
 * Visualizer's scaling cannot pass as an EQ effect.
 */
@RunWith(AndroidJUnit4::class)
class DspPathTest {
    @get:Rule val perms: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS)

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val fs = 48_000
    private lateinit var track: AudioTrack
    private var session = 0

    @Before fun play() {
        am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        session = am.generateAudioSessionId()
        val n = fs // one second, looped; every tone completes whole cycles so the loop has no click
        val pcm = ShortArray(n * 2)
        for (i in 0 until n) {
            val t = i.toDouble() / fs
            val v = 0.08 * sin(2 * PI * 200 * t) + 0.08 * sin(2 * PI * 1_000 * t) + 0.08 * sin(2 * PI * 5_000 * t)
            val s = (v * 32767).toInt().toShort()
            pcm[2 * i] = s; pcm[2 * i + 1] = s
        }
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(fs).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).setSessionId(session).build()
        track.write(pcm, 0, pcm.size)
        track.setLoopPoints(0, n, -1)
        track.play()
    }

    @After fun end() { runCatching { track.stop(); track.release() } }

    /** Average magnitude (dB) at 200 Hz and 5 kHz relative to 1 kHz, over ~1.5 s of snapshots. */
    private fun measure(v: Visualizer): Pair<Double, Double> {
        Thread.sleep(700) // let the new settings reach the audio
        val size = v.captureSize
        val bytes = ByteArray(size)
        val binHz = fs.toDouble() / size
        fun mag(f: Double): Double {
            val k = Math.round(f / binHz).toInt()
            return (k - 1..k + 1).maxOf { hypot(bytes[2 * it].toDouble(), bytes[2 * it + 1].toDouble()) }
        }
        var a = 0.0; var b = 0.0; var c = 0.0; var got = 0
        repeat(30) {
            if (v.getFft(bytes) == Visualizer.SUCCESS) { a += mag(200.0); b += mag(1_000.0); c += mag(5_000.0); got++ }
            Thread.sleep(50)
        }
        assertTrue("Visualizer returned no data", got > 10 && b > 0)
        return 20 * log10((a + 1e-3) / b) to 20 * log10((c + 1e-3) / b)
    }

    @Test fun eqChainBoostsTheBandItShouldAndLetsGoCleanly() {
        val chain = EffectChain.attach(session)
        assertNotNull("DynamicsProcessing refused the session", chain)
        chain!!
        chain.setBands(DoubleArray(DEFAULT_BAND_CUTOFFS.size))
        chain.setInputGains(0.0, 0.0)
        // the OUTPUT MIX, not the session: on a session the Visualizer is inserted first and hears the sound before our effect (E-003)
        val viz = openOutputMeter()
        try {
            val (flat200, flat5k) = measure(viz)

            // the real code path: a parametric filter -> Curve -> 20 bands -> DynamicsProcessing
            val boost = Curve.of(listOf(ParametricFilter(FilterType.PEAK, 200.0, 12.0, 1.0))).toBands(DEFAULT_BAND_CUTOFFS)
            chain.setBands(boost)
            val (eq200, eq5k) = measure(viz)

            // the same filter as a CUT: if a cut reads accurately while the boost reads short, the 8-bit
            // Visualizer FFT is clipping the louder tone - an instrument limit, not an effect error
            val cut = Curve.of(listOf(ParametricFilter(FilterType.PEAK, 200.0, -12.0, 1.0))).toBands(DEFAULT_BAND_CUTOFFS)
            chain.setBands(cut)
            val (cut200, _) = measure(viz)

            chain.release()
            val (off200, off5k) = measure(viz)

            val report = ("200Hz rel 1k: flat %.1f, boosted %.1f, cut %.1f, released %.1f dB | designed band gain %.1f dB | " +
                "5kHz rel 1k: flat %.1f, boosted %.1f, released %.1f dB")
                .format(flat200, eq200, cut200, off200, boost[4], flat5k, eq5k, off5k)
            Log.i("DspPathTest", report)
            println("DSP_RESULT $report")

            assertTrue("200 Hz not boosted enough: $report", eq200 - flat200 >= 6.0)
            assertTrue("5 kHz moved although it should not: $report", kotlin.math.abs(eq5k - flat5k) <= 3.0)
            assertTrue("200 Hz not cut enough: $report", flat200 - cut200 >= 6.0)
            assertTrue("sound did not return after release: $report", kotlin.math.abs(off200 - flat200) <= 2.0)
        } finally {
            runCatching { viz.enabled = false; viz.release() }
            runCatching { chain.release() }
        }
    }

    /** Average absolute magnitude (dB, Visualizer units) at 200 Hz and 1 kHz: for checking the VOLUME, not only the balance. */
    private fun measureAbs(v: Visualizer): Pair<Double, Double> {
        Thread.sleep(900)
        val size = v.captureSize; val bytes = ByteArray(size); val binHz = fs.toDouble() / size
        fun mag(f: Double): Double { val k = Math.round(f / binHz).toInt(); return (k - 1..k + 1).maxOf { hypot(bytes[2 * it].toDouble(), bytes[2 * it + 1].toDouble()) } }
        var a = 0.0; var b = 0.0; var got = 0
        repeat(30) { if (v.getFft(bytes) == Visualizer.SUCCESS) { a += mag(200.0); b += mag(1_000.0); got++ }; Thread.sleep(50) }
        assertTrue("Visualizer returned no data", got > 10 && b > 0)
        return 20 * log10(a / got + 1e-3) to 20 * log10(b / got + 1e-3)
    }

    // his phone test, 22 Sep: Bass fully right "just sounds quieter". The plan the app really makes for Bass +8 must
    // leave the rest of the song (1 kHz) at about the same level, not ~6 dB down as the old preamp did.
    @Test fun bassSliderDoesNotTurnTheSongDown() {
        val chain = EffectChain.attach(session)
        assertNotNull("DynamicsProcessing refused the session", chain)
        chain!!
        chain.setBands(DoubleArray(DEFAULT_BAND_CUTOFFS.size)); chain.setInputGains(0.0, 0.0)
        val viz = openOutputMeter()
        try {
            val (flat200, flat1k) = measureAbs(viz)
            val plan = Mixer.plan(ContentProfile(ContentKind.entries.associateWith { if (it == ContentKind.BASS_HEAVY) 1.0 else 0.0 }, "test"),
                null, UserSwitches(autoEnabled = false, loudnessCompEnabled = false, manualTone = Tone(bass = 8.0)), null, null)
            chain.setBands(plan.bandGainsDb); chain.setInputGains(plan.preampDb, plan.preampDb)
            val (b200, b1k) = measureAbs(viz)
            // 200 Hz sits above the bass shelf (105 Hz), so it only rises a little by design: compare with what was designed
            val designed200 = plan.curve.at(200.0) + plan.preampDb
            val report = "Bass +8: preamp %.1f dB | 1 kHz %.1f dB | 200 Hz %.1f dB, designed %.1f (all vs flat)"
                .format(plan.preampDb, b1k - flat1k, b200 - flat200, designed200)
            Log.i("DspPathTest", report); println("DSP_RESULT $report")
            assertTrue("the song got quieter: $report", b1k - flat1k >= -2.5)
            assertTrue("200 Hz is not where it was designed to be: $report", kotlin.math.abs((b200 - flat200) - designed200) <= 1.5)
            assertTrue("bass did not rise against the rest of the song: $report", (b200 - flat200) - (b1k - flat1k) >= 1.0)
        } finally {
            runCatching { viz.enabled = false; viz.release() }
            runCatching { chain.release() }
        }
    }
}

/**
 * A Visualizer on the output mix. Android shares ONE such effect between everyone on session 0, so when the
 * app's own analyzer already has it running, its size cannot be changed ("wrong state: 2"). Then the
 * settings the analyzer chose are used - after checking they are the ones this measurement needs.
 */
internal fun openOutputMeter(): Visualizer = Visualizer(0).apply {
    runCatching { captureSize = Visualizer.getCaptureSizeRange()[1] }
    scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
    enabled = true
    check(captureSize >= 512) { "output meter too coarse: capture size $captureSize" }
    check(scalingMode == Visualizer.SCALING_MODE_AS_PLAYED) { "output meter normalises its level, so loudness cannot be compared" }
}
