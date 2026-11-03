package com.arnavagarwal.sound.desktop

import java.io.File
import java.util.prefs.Preferences

/**
 * Headless check of the real engine against the real helper and Equalizer APO: `gradle :desktop:smoke [--args="20 tune"]`.
 * Uses its own settings node, starts with the tuning OFF, and with "tune" switches it on only for the middle third.
 * Ends by stopping the engine and reading sound-app.txt back: it must be neutral.
 */
fun main(args: Array<String>) {
    val secs = args.getOrNull(0)?.toIntOrNull() ?: 12
    val tune = "tune" in args
    val apo = Apo()
    val prefs = Preferences.userRoot().node("com/arnavagarwal/sound-smoke")
    val engine = Engine(apo, ToolsRoot.helper, prefs)
    engine.setTuning(false)
    engine.start()
    for (s in 1..secs) {
        Thread.sleep(1_000)
        if (tune && s == secs / 3) engine.setTuning(true)
        if (tune && s == 2 * secs / 3) engine.setTuning(false)
        val st = engine.state.value
        val c = st.curveDb
        println(String.format(java.util.Locale.ROOT,
            "t=%2d helper=%s apo=%s dev=%s speaker=%s playing=%s kind=%s tuning=%s pre=%.1f trim=%.1f 350Hz=%.1f 3k=%.1f problem=%s",
            s, st.helperAlive, st.reachesOutput, st.device?.name, st.device?.isSpeaker, st.playing?.let { "${it.app}|${it.title}|${it.playing}" },
            st.profile?.top, st.tuningOn, st.preampDb, st.clipTrimDb,
            c[com.arnavagarwal.sound.core.Grid.freqs.indexOfFirst { it >= 350 }], c[com.arnavagarwal.sound.core.Grid.freqs.indexOfFirst { it >= 3_000 }],
            st.problem))
        if (tune && s == secs / 3 + 2) println("  sound-app.txt: " + apo.ours.readText().take(60).replace("\r\n", " | "))
    }
    engine.stop()
    val end = apo.ours.readText()
    println("after stop: sound-app.txt = ${end.replace("\r\n", " | ")} -> ${if (end == Apo.NEUTRAL) "NEUTRAL" else "NOT NEUTRAL"}")
    prefs.removeNode()
}
