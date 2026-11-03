package com.arnavagarwal.sound.desktop

import com.arnavagarwal.sound.core.Spectrum
import com.arnavagarwal.sound.core.QuietFix
import java.io.File

/**
 * For the quiet-listening blind test (tools/windows-spike/quiet_ab.py): writes the two Equalizer APO texts the engine
 * would write at Normal with density and width off - [plain] = the liked setting, [quiet] = the same plus the low-volume
 * fix for a drop of `dropDb` below his usual level, loudness-matched exactly as Engine.write does it (the curve as a
 * GraphicEQ line, its match gain added to the preamp; Spectrum.PINK = the engine's fallback before it has a spectrum).
 * Printed by the app's own code so the test cannot drift from what ships.
 *   gradle :desktop:quietText --args="<dropDb> <outDir>"
 */
fun main(args: Array<String>) {
    val drop = args[0].toDouble()
    val dir = File(args[1])
    val curve = QuietFix.curve(drop)
    val match = LaptopSetting.quietMatchDb(curve, Spectrum.PINK, 1.0, null)
    File(dir, "quiet_plain.txt").writeText(LaptopSetting.text(1.0, 0.0, null))
    File(dir, "quiet_fix.txt").writeText(LaptopSetting.text(1.0, match, curve))
    println(String.format(java.util.Locale.ROOT, "drop %.1f dB: biggest lift %.2f dB, match %.2f dB", drop, curve.db.max(), match))
}

/** Study music's lines, printed by the app's own code for tools/windows-spike/voice_check.py.
 *    gradle :desktop:voiceText --args="<dB> <outFile>" */
object VoiceText {
    @JvmStatic fun main(args: Array<String>) = File(args[1]).writeText(LaptopSetting.voiceDownText(args[0].toDouble()))
}
