package com.arnavagarwal.sound.desktop

import com.arnavagarwal.sound.core.Curve
import com.arnavagarwal.sound.core.FilterType
import com.arnavagarwal.sound.core.FilterType.HIGH_PASS
import com.arnavagarwal.sound.core.FilterType.PEAK
import com.arnavagarwal.sound.core.Loudness
import com.arnavagarwal.sound.core.ParametricFilter
import com.arnavagarwal.sound.core.Spectrum
import java.util.Locale

/**
 * The laptop-speaker setting he LIKED, applied as it was (23 Sep ~20:55: *"go back when i said it was good like before
 * the app take the same setting and apply"*). At Normal with no knobs moved, [text] is the spike file
 * `tools/windows-spike/strong-liked.txt` line for line (his Killie Killie test at 19:40; only the line ends differ),
 * so Equalizer APO runs the same filters.
 *
 * Why not the Mixer's own plan: four loudness-matched plans were all worse or inaudible to him (E-016, E-017).
 * Strength scales the gains and the preamp together (Normal = exact). His knobs, ear-level answers and the clip guard
 * are added on top only when they are not zero, so the untouched case stays exact.
 */
object LaptopSetting {
    const val PREAMP_DB = -3.8
    val FILTERS = listOf(
        ParametricFilter(HIGH_PASS, 120.0),
        ParametricFilter(PEAK, 350.0, 8.0, 1.0),
        ParametricFilter(PEAK, 3_000.0, 6.0, 0.9),
    )

    /** [safeDb] = speaker-safe back-off ([speakerBackOffDb]); the boosts give back [BOOST_SHARE] of it, never below 0. */
    private fun scaled(intensity: Double, safeDb: Double = 0.0) = FILTERS.map {
        if (it.type == HIGH_PASS) it else it.copy(gainDb = (it.gainDb * intensity - safeDb * BOOST_SHARE).coerceAtLeast(0.0))
    }

    /**
     * Speaker-safe loudness (his tap 29 Sep "Build it now"). His beep test (tools/windows-spike/speaker_limit.json,
     * 12:46): above Windows volume 0.75 = [SAFE_KNEE_DB] his speakers' own limiter holds the output (600 Hz / 1 kHz rose
     * only 0.04-0.31 dB per dB of volume) and 600 Hz / 3 kHz distortion rises. So above the knee density's cap comes
     * down 1 dB per dB of volume and the 350 Hz / 3 kHz boosts by half that; at or below the knee nothing changes.
     */
    const val SAFE_KNEE_DB = -4.3
    const val BOOST_SHARE = 0.5

    /** How far the volume is above the knee (0..4.3 dB); 0 when unknown or muted. */
    fun speakerBackOffDb(volumeDb: Double?, muted: Boolean = false): Double =
        if (volumeDb == null || muted) 0.0 else (volumeDb - SAFE_KNEE_DB).coerceIn(0.0, -SAFE_KNEE_DB)

    fun preampDb(intensity: Double, extraDb: Double) = PREAMP_DB * intensity + extraDb

    /** The whole curve as the app's own analysis sees it (for un-colouring the loopback and for the screen). */
    fun curve(intensity: Double, manual: Curve?, safeDb: Double = 0.0): Curve {
        val c = Curve.of(scaled(intensity, safeDb))
        return if (manual != null) c + manual else c
    }

    /** Equalizer APO text. [extraDb] = ear-level answers + clip trim; [manual] = his knobs (a GraphicEQ line);
     *  [densityDb] = the density lift into the limiter after APO (core DensityControl), added to the preamp only. */
    fun text(intensity: Double, extraDb: Double, manual: Curve?, densityDb: Double = 0.0, width: Double = 1.0, safeDb: Double = 0.0): String {
        val sb = StringBuilder("Preamp: ${num(preampDb(intensity, extraDb) + densityDb)} dB\r\n")
        for (f in scaled(intensity, safeDb)) sb.append(
            when (f.type) {
                HIGH_PASS -> "Filter: ON HP Fc ${num(f.fc)} Hz\r\n"
                else -> "Filter: ON PK Fc ${num(f.fc)} Hz Gain ${num(f.gainDb)} dB Q ${String.format(Locale.ROOT, "%.1f", f.q)}\r\n"
            })
        if (manual != null) sb.append(Apo.render(manual, 0.0).substringAfter("\r\n"))
        if (width > 1.0005) sb.append(widthText(width))
        return sb.toString()
    }

    /**
     * Study music (his ask 28 Sep: *"lower ... mainly the voice of lyrics itself and the bgm should remain"*). A sung
     * lead voice is mixed to the middle; most instruments are spread to the sides. So the middle part M = (L+R)/2 goes
     * into a virtual channel with the voice band (250 Hz - 5 kHz) turned down by [db], the side part S = (L-R)/2 is left
     * alone, and L = M + S, R = M - S rebuilds the song. The cut is two shelves on M only (down at 250 Hz, back up at
     * 5 kHz), not a band subtracted from the song: nothing has to cancel, so at 0 dB the song comes back exactly and
     * bass (kick, bass line) stays whole. Honest limit: anything else in the middle of that band (snare, a centred
     * instrument) goes down too; true separation needs a model (research/voice-down-denoise.md).
     */
    fun voiceDownText(db: Double): String {
        if (db <= 0.05) return ""
        return "Copy: VMID=0.5*L+0.5*R VSIDE=0.5*L+-0.5*R\r\n" +
            "Channel: VMID\r\n" +
            "Filter: ON HSC Fc $VOICE_LOW_HZ Hz Gain ${num(-db)} dB Q 0.707\r\n" +
            "Filter: ON HSC Fc $VOICE_HIGH_HZ Hz Gain ${num(db)} dB Q 0.707\r\n" +
            "Channel: all\r\n" +
            "Copy: L=VMID+VSIDE R=VMID+-1*VSIDE\r\n"
    }

    /**
     * The gentler middle density learned from him (core DensityControl.softMiddleDb): [voiceDownText]'s two shelves on
     * the WHOLE song, which his blind test (29 Sep, Husn) could not tell from the centre-only cut. Nothing at 0.
     */
    fun softMiddleText(db: Double): String {
        if (db <= 0.05) return ""
        return "Filter: ON HSC Fc $VOICE_LOW_HZ Hz Gain ${num(-db)} dB Q 0.707\r\n" +
            "Filter: ON HSC Fc $VOICE_HIGH_HZ Hz Gain ${num(db)} dB Q 0.707\r\n"
    }

    /** What [voiceDownText] does to the middle, which is exactly the helper's mono (L+R)/2: for un-colouring. */
    fun voiceDownCurve(db: Double): Curve = if (db <= 0.05) Curve.FLAT else Curve.of(
        ParametricFilter(FilterType.HIGH_SHELF, VOICE_LOW_HZ.toDouble(), -db, 0.707),
        ParametricFilter(FilterType.HIGH_SHELF, VOICE_HIGH_HZ.toDouble(), db, 0.707))

    /**
     * Study music cuts only the MIDDLE copy, so its loudness loss is smaller than [wholeLiftDb] (the same cut priced
     * on the whole song). [sideDb] = the song's side against mid energy. Measured on 5 of his recordings
     * (tools/windows-spike/lift_check.py, lift_fix.log): whole-song pricing over-lifted by up to 0.9 dB; this is within 0.26.
     */
    fun centreOnlyLiftDb(wholeLiftDb: Double, sideDb: Double?): Double {
        if (sideDb == null || wholeLiftDb <= 0.0) return wholeLiftDb
        val mid = 1.0 / (1.0 + Math.pow(10.0, sideDb / 10))
        return -10 * kotlin.math.log10(1 - mid + mid * Math.pow(10.0, -wholeLiftDb / 10))
    }

    /**
     * The gain that gives back what Study music ([voiceDownDb]) takes off the loudness. [song] = the un-coloured song
     * spectrum; it is priced AFTER the liked EQ ([curve]), which is where the cut sits (29 Sep, see Engine.voiceMatch).
     */
    fun voiceLiftDb(voiceDownDb: Double, song: Spectrum, intensity: Double, manual: Curve?, sideDb: Double?, safeDb: Double = 0.0): Double =
        if (voiceDownDb <= 0.05) 0.0 else centreOnlyLiftDb(
            Loudness.matchGainDb(voiceDownCurve(voiceDownDb), song.through(curve(intensity, manual, safeDb))), sideDb)

    /**
     * The gain that takes back what Quiet listening ([quiet]) adds to the loudness (it must change the balance, not the
     * level). Priced AFTER the liked EQ ([curve] with his knobs [manual], quiet itself excluded), where it plays - the
     * same class of fix as [voiceLiftDb] (E-025): on the raw song the voice band looked tiny and the sub-bass inflated.
     */
    fun quietMatchDb(quiet: Curve, song: Spectrum, intensity: Double, manual: Curve?, safeDb: Double = 0.0): Double =
        if (quiet.db.all { it == 0.0 }) 0.0 else Loudness.matchGainDb(quiet, song.through(curve(intensity, manual, safeDb)))

    const val VOICE_LOW_HZ = 250
    const val VOICE_HIGH_HZ = 5000

    /**
     * Auto width (core AutoWidth) in Equalizer APO's own commands: the side part S = (L-R)/2 is copied into a virtual
     * channel, high-passed so the bass stays in the middle, and added back: L + k*S, R - k*S with k = width - 1.
     * Copy only knows "+" (CopyFilterFactory.cpp splits on '+'), so a minus is written "+-". The mid part (L+R) is
     * untouched, which is also what the helper's mono spectrum is: widening cannot move the app's own analysis.
     */
    fun widthText(width: Double): String {
        val k = String.format(Locale.ROOT, "%.3f", width - 1)
        return "Copy: WIDE=0.5*L+-0.5*R\r\n" +
            "Channel: WIDE\r\n" +
            "Filter: ON HP Fc ${num(com.arnavagarwal.sound.core.AutoWidth.BASS_HZ)} Hz\r\n" +
            "Channel: all\r\n" +
            "Copy: L=L+$k*WIDE R=R+-$k*WIDE\r\n"
    }

    /** "8" not "8.0", "-3.8" as is: the same spelling as the file he heard. */
    private fun num(x: Double): String {
        val r = Math.round(x * 10) / 10.0
        return if (r == Math.rint(r)) r.toLong().toString() else String.format(Locale.ROOT, "%.1f", r)
    }
}
