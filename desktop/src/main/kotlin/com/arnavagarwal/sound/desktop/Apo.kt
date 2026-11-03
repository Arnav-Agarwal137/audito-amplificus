package com.arnavagarwal.sound.desktop

import com.arnavagarwal.sound.core.Curve
import com.arnavagarwal.sound.core.Grid
import java.io.File
import java.util.Locale

/**
 * The hands: Equalizer APO reads `config.txt` and reloads it within a second of any change (measured, E-011).
 * We own config.txt (it only includes our file) and write our curve into `sound-app.txt`.
 * When the app is off or quits, sound-app.txt is NEUTRAL, so the laptop sounds exactly as before (E-012).
 */
class Apo(val dir: File = File("""C:\Program Files\EqualizerAPO\config"""), private val lookOnly: Boolean = false) {
    val main = File(dir, "config.txt")
    val ours = File(dir, "sound-app.txt")
    private var lastWritten: String? = null

    val installed get() = dir.isDirectory

    /** Makes config.txt include our file; keeps whatever was there before as config.before-sound-app.txt (once). */
    fun takeOver() {
        if (lookOnly) return // a preview must never be able to change what anyone hears
        val cur = if (main.exists()) main.readText() else ""
        if (MARK in cur) return
        val backup = File(dir, "config.before-sound-app.txt")
        if (main.exists() && !backup.exists()) main.copyTo(backup)
        main.writeText("$MARK\r\n# Written by the Sound app. It changes only sound-app.txt.\r\nInclude: sound-app.txt\r\n")
    }

    /** Writes the curve; returns false when the text is unchanged (nothing written, no reload). */
    fun write(curve: Curve, preampDb: Double): Boolean = put(render(curve, preampDb))

    fun neutral(): Boolean = put(NEUTRAL)

    /** Writes ready-made Equalizer APO text (the laptop setting he liked, [LaptopSetting]). */
    fun writeText(text: String): Boolean = put(text)

    private fun put(text: String): Boolean {
        if (lookOnly) return false
        if (text == lastWritten) return false
        ours.writeText(text) // one small write: Equalizer APO reloads on change
        lastWritten = text
        return true
    }

    companion object {
        const val MARK = "# sound-app: config"
        const val NEUTRAL = "Preamp: 0 dB\r\n"
        val DEVICE_SELECTOR = File("""C:\Program Files\EqualizerAPO\DeviceSelector.exe""")

        /** Opens Equalizer APO's own Device Selector. Its manifest says requireAdministrator, so Windows shows the
         *  permission prompt; ticking outputs cannot be scripted safely (DeviceAPOInfo.cpp wraps driver APOs). */
        fun openDeviceSelector(): Boolean = runCatching {
            ProcessBuilder("cmd", "/c", "start", "", DEVICE_SELECTOR.path).start(); true
        }.getOrDefault(false)

        /** Runs windows/apo-setup `setup` with administrator rights (one Windows permission prompt): Equalizer APO on
         *  for every output now, plus a task that does it for any headphone connected later. Nothing to tick. */
        fun switchOnEverywhere(setupDll: File = ToolsRoot.apoSetup): Boolean = runCatching {
            if (!setupDll.isFile) return false
            ProcessBuilder("powershell", "-NoProfile", "-Command", elevateCommand(setupDll)).start(); true
        }.getOrDefault(false)

        /** Portable copy only: downloads Equalizer APO 1.4.2 from SourceForge, checks its SHA-256, installs it silently and
         *  then runs ApoSetup setup, all in ONE elevated PowerShell (one Windows permission prompt). Never bundled: it is
         *  GPL and downloading it from its own home keeps the zip free of a second licence to carry. */
        fun installEverything(script: File? = ToolsRoot.installApo, setupExe: File = ToolsRoot.apoSetup): Boolean = runCatching {
            if (script == null || !script.isFile || !setupExe.isFile) return false
            ProcessBuilder("powershell", "-NoProfile", "-Command", installCommand(script, setupExe)).start(); true
        }.getOrDefault(false)

        /** Elevates ApoSetup.exe itself (`install <script>`), so the Windows prompt names "Audito Amplificus sound
         *  setup" instead of "Windows PowerShell" - a stranger's reason to say No (his ask, 11 Oct 2026). */
        fun installCommand(script: File, setupExe: File): String {
            val s = script.absolutePath.replace("'", "''").replace("\"", "")
            val e = setupExe.absolutePath.replace("'", "''").replace("\"", "")
            return "Start-Process -Verb RunAs -WindowStyle Hidden -FilePath '$e' -ArgumentList 'install \"$s\"'"
        }

        /** PowerShell single quotes: a ' inside the path is doubled; the inner double quotes keep a path with spaces whole. */
        fun elevateCommand(setupDll: File): String {
            val p = setupDll.absolutePath.replace("'", "''")
            // the portable copy's ApoSetup is a self-contained .exe (no .NET on a friend's laptop): it runs as itself
            if (setupDll.name.endsWith(".exe", ignoreCase = true))
                return "Start-Process -Verb RunAs -WindowStyle Hidden -FilePath '$p' -ArgumentList 'setup'"
            return "Start-Process -Verb RunAs -WindowStyle Hidden -FilePath 'dotnet' -ArgumentList '\"$p\" setup'"
        }

        /** Equalizer APO text for [curve] on the whole [Grid] plus [preampDb], one decimal (0.1 dB is below hearing). */
        fun render(curve: Curve, preampDb: Double): String {
            val pts = Grid.freqs.indices.joinToString("; ") { i ->
                String.format(Locale.ROOT, "%.1f %.1f", Grid.freqs[i], curve.db[i])
            }
            return String.format(Locale.ROOT, "Preamp: %.1f dB\r\nGraphicEQ: %s\r\n", preampDb, pts)
        }
    }
}
