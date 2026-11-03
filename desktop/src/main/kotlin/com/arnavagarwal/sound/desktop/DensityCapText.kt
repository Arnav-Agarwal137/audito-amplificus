package com.arnavagarwal.sound.desktop

import com.arnavagarwal.sound.core.DensityControl
import java.util.prefs.Preferences

/**
 * The two density amounts for the per-song blind test (tools/windows-spike/density_song_ab.py), from the app's own
 * code so the test never re-types a rule: OLD = the cap before 28 Sep (an unsure song got the full amount), NEW = the
 * cap after "Lower density when unsure". Strength is his, read from the app's own preferences.
 *   gradle :desktop:densityCap --args="<unsure share 0..1>"   ->   "old <dB> new <dB> strength <x>"
 */
fun main(args: Array<String>) {
    val share = args[0].toDouble()
    val strength = Preferences.userRoot().node("com/arnavagarwal/sound").getDouble("intensity", 1.0)
    val old = DensityControl.cap(null, strength)
    val new = old * DensityControl.unsureFactor(share)
    println("old %.2f new %.2f strength %.2f".format(old, new, strength))
}
