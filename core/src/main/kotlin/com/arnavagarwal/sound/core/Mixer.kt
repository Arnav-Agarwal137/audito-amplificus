package com.arnavagarwal.sound.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin

/** Every switch the user controls. Extras are OFF by default - his rule: "their wish". */
data class UserSwitches(
    val autoEnabled: Boolean = true,      // content-aware presets
    val learnEnabled: Boolean = true,     // apply learned taste
    val correctionEnabled: Boolean = true,// earbud correction, if a profile is loaded
    val loudnessCompEnabled: Boolean = true, // ISO 226 compensation at low volume
    val nightMode: Boolean = false,
    val eightD: Boolean = false,
    val eightDPeriodSec: Double = 8.0,
    val manualTone: Tone = Tone.ZERO,     // the person's knobs, applied on top (reset on song change)
    val manualFine: Fine = Fine.ZERO,     // "More controls": stay on for everything until moved back
    val intensity: Double = 1.0,          // 0..1.5 global "how much" slider
    val voiceDownDb: Double = 0.0,        // Study music: the singer's band down by this much (0 = off; StudyMusic)
    val strongOn: Boolean = false,        // Strong mode (StrongMode, his tap 9 Oct 2026): OFF by default, today's sound kept
    val strongStep: Int = StrongMode.BOLD,// Light / Bold / Max, used only while strongOn
)

/** Everything the engine needs to push to the phone's DynamicsProcessing for one moment. */
data class EnginePlan(
    val curve: Curve,
    val bandGainsDb: DoubleArray,
    val preampDb: Double,
    val dynamics: Dynamics,
    val limiterOn: Boolean,
    val allow8D: Boolean,
    /** What the app itself chose for this sound (presets + learned taste), in the four knobs' units - drawn on the sliders. */
    val appTone: Tone = Tone.ZERO,
    val explanation: List<String>,
    /** Planned for the phone's own speaker ([Speaker]): loudness is then matched on what the speaker can play. */
    val speaker: Boolean = false,
    /** Strong mode's allowed rise in loudness over Off (dB, already inside [preampDb]); 0 when off or not on the speaker. */
    val strongLouderDb: Double = 0.0,
)

/**
 * Combines the layers into one curve, in this order:
 *   content preset (blended by classifier certainty)  x intensity
 * + learned taste for this kind of content / this song
 * + the person's manual knobs
 * + earbud correction (if the user imported one)
 * + equal-loudness compensation for the current volume
 * then clamps to +-12 dB and sets a preamp that keeps the LOUDNESS unchanged: a tone change must not be a volume
 * change (his phone test, 22 Sep: Bass fully right "just sounds quieter", because the old preamp took 0.75 x the
 * largest boost off the whole song). The preamp never boosts; the limiter catches the peaks.
 */
object Mixer {
    const val MAX_BOOST = 12.0
    const val MAX_CUT = -12.0

    fun plan(
        profile: ContentProfile,
        taste: TasteLearner.Prediction?,
        sw: UserSwitches,
        correction: Curve?,
        listenPhon: Double?,
        cutoffs: DoubleArray = DEFAULT_BAND_CUTOFFS,
        spectrum: Spectrum = Spectrum.PINK,
        speaker: Boolean = false,
        speakerProfile: Speaker.Profile = Speaker.PHONE,
    ): EnginePlan {
        val why = mutableListOf<String>()
        var c = Curve.FLAT
        val certainty = ContentClassifier.certainty(profile)
        if (sw.autoEnabled) {
            c += Presets.blend(profile, certainty) * sw.intensity.coerceIn(0.0, 1.5)
            why += "${profile.top.label} (${Math.round(profile.confidence * 100)}% sure: ${profile.reason})"
        }
        if (sw.learnEnabled && taste != null && taste.evidence > 0) {
            c += taste.tone.curve()
            why += if (taste.fromSongMemory) "your saved sound for this song" else "your taste for this kind of sound"
        }
        val appTone = Tone.fit(c)
        if (sw.manualTone != Tone.ZERO) { c += sw.manualTone.curve(); why += "your knobs" }
        if (sw.manualFine != Fine.ZERO) { c += sw.manualFine.curve(); why += "your extra controls" }
        if (sw.correctionEnabled && correction != null) { c += correction; why += "earbud correction" }
        if (sw.loudnessCompEnabled && listenPhon != null) {
            val comp = EqualLoudness.compensation(listenPhon)
            if (comp.max() > 0.5) { c += comp; why += "low-volume bass/treble compensation" }
        }
        // Strong mode on the speaker: the laptop's peaks REPLACE the speaker's own gentler lifts (amount 0); the high-pass
        // and the moved bass stay, and they exist only here, on the speaker - never on earbuds
        val strong = sw.strongOn
        if (speaker) { c = Speaker.adapt(c, if (sw.autoEnabled && !strong) sw.intensity else 0.0, speakerProfile); why += "tuned for the ${speakerProfile.label}" }
        if (strong) { c += StrongMode.curve(sw.strongStep); why += "strong mode" }
        c = c.clamp(MAX_CUT, MAX_BOOST)
        // equal loudness, never a boost; the limiter (-1 dBFS) catches the peaks
        val heard = if (speaker) spectrum.through(speakerProfile.response) else spectrum
        var preamp = Loudness.matchGainDb(c, heard).coerceAtMost(0.0)
        // Strong mode: NOT loudness-matched - the song may get louder by the step's amount, on the speaker AND in the
        // ears (his tap 9 Oct 2026 "Louder in earbuds too", told the hearing trade first); the -1 dBFS limiter catches
        // the peaks, and the earbud safe cap / dose ledger (Level) still apply on top.
        val strongLouder = if (strong) StrongMode.louderDb(sw.strongStep) else 0.0
        preamp += strongLouder
        // Study music: after everything else (the speaker shaping must not reshape the cut), and its loudness given back
        // - soft must never mean quieter; the -1 dBFS limiter catches the peaks, as for density
        if (sw.voiceDownDb > 0.05) {
            val before = c
            // the cut goes BELOW the tuning's floor by its own depth: clamped at MAX_CUT, "Voice almost gone" (24) would be 12
            c = (c + StudyMusic.curve(sw.voiceDownDb)).clamp(MAX_CUT - sw.voiceDownDb, MAX_BOOST)
            preamp += (Loudness.matchGainDb(c, heard) - Loudness.matchGainDb(before, heard)).coerceAtLeast(0.0)
            why += "study music: the singer softer"
        }
        val dynamics = when {
            sw.nightMode -> Dynamics.NIGHT
            sw.autoEnabled -> Presets.all.getValue(profile.top).dynamics.takeIf { certainty > 0.25 } ?: Dynamics.OFF
            else -> Dynamics.OFF
        }
        if (sw.nightMode) why += "night mode"
        val allow8D = sw.eightD && Presets.all.getValue(profile.top).allow8D
        return EnginePlan(c, c.toBands(cutoffs), preamp, dynamics, limiterOn = true, allow8D = allow8D, explanation = why, appTone = appTone, speaker = speaker, strongLouderDb = strongLouder)
    }
}

/**
 * Glides band gains toward their target so a change of song or setting never jumps (an audible click
 * or lurch). Exponential approach with time constant [tauSec]: ~63% of the way in tau, ~95% in 3 tau.
 */
class Glide(bands: Int, private val tauSec: Double = 0.6) {
    val current = DoubleArray(bands)
    private var initialised = false

    fun step(target: DoubleArray, dtSec: Double): DoubleArray {
        require(target.size == current.size)
        if (!initialised) { target.copyInto(current); initialised = true; return current.copyOf() }
        val a = 1 - exp(-dtSec / tauSec)
        for (i in current.indices) current[i] += (target[i] - current[i]) * a
        return current.copyOf()
    }

    fun snap(target: DoubleArray) { target.copyInto(current); initialised = true }
}

/**
 * "8D" audio: the sound circles slowly around the head. Done as a constant-power pan whose position
 * follows a sine; constant power means the total loudness stays steady while it moves.
 */
object EightD {
    /** Left/right gains in dB at time [tSec] for a full circle every [periodSec]. [depth] 0..1. */
    fun gainsDb(tSec: Double, periodSec: Double, depth: Double = 0.85): Pair<Double, Double> {
        val pos = sin(2 * PI * tSec / periodSec) * depth.coerceIn(0.0, 1.0) // -1 left .. +1 right
        val angle = (pos + 1) * PI / 4
        val l = cos(angle); val r = sin(angle)
        // normalise so the centre is 0 dB on both sides (cos(pi/4) = 0.707)
        val norm = 1 / cos(PI / 4)
        return 20 * log10(l * norm + 1e-9) to 20 * log10(r * norm + 1e-9)
    }
}
