package com.arnavagarwal.sound.desktop

import java.io.File

/**
 * The one folder the laptop app looks in for things it does not ship: the Windows helper, the Python environments for
 * karaoke / clean a talk / 8D copy, and the head measurements. Set `-Daudito.tools=<folder>` or the environment
 * variable AUDITO_TOOLS; with neither set it is D:/dev, where the owner's laptop keeps them. (Before 8 Oct 2026 each
 * file wrote D:/dev itself; one place now, so a public copy runs on any machine.)
 *
 * The PORTABLE copy (a zip for other people, 10 Oct 2026) carries its own helper and ApoSetup as self-contained .exe
 * files in a folder named portable beside the launcher, and that folder wins over every setting: the launcher still bakes in the
 * owner's D:/dev paths from build.gradle.kts, and on a friend's laptop those point at nothing.
 */
object ToolsRoot {
    /** The portable copy's own folder, <app>/portable beside the launcher (jpackage sets jpackage.app-path to the
     *  launcher .exe; gradle run and his javaw copy never set it), only if it holds the helper. */
    val portable: File? get() = System.getProperty("jpackage.app-path")?.let { File(it).absoluteFile.parentFile }
        ?.let { File(it, "portable") }?.takeIf { File(it, "helper/SoundHelper.exe").isFile }

    val dir: File get() = portable ?: File(System.getProperty("audito.tools") ?: System.getenv("AUDITO_TOOLS") ?: "D:/dev")

    /** python.exe inside the venv folder [venv] (e.g. "demucs-venv"). */
    fun python(venv: String): File = File(dir, "$venv/Scripts/python.exe")

    /** Starts a Python tool with AUDITO_TOOLS set to [dir], so the script finds the same folder the app was given. */
    fun start(pb: ProcessBuilder): Process { pb.environment()["AUDITO_TOOLS"] = dir.path; return pb.start() }

    /** The helper: the portable copy's own .exe, else `-Dsound.helper` (the installed copy passes it), else <tools>/sound-builds/helper. */
    val helper: File get() = portable?.let { File(it, "helper/SoundHelper.exe") }
        ?: File(System.getProperty("sound.helper") ?: File(dir, "sound-builds/helper/SoundHelper.dll").path)

    /** ApoSetup sits beside the helper's folder: <builds>/apo-setup/ApoSetup.dll (windows/apo-setup), or .exe when portable. */
    val apoSetup: File get() = File(helper.absoluteFile.parentFile.parentFile, if (portable != null) "apo-setup/ApoSetup.exe" else "apo-setup/ApoSetup.dll")

    /** The portable copy's Equalizer APO installer script (tools/portable/install-apo.ps1); null on his laptop. */
    val installApo: File? get() = portable?.let { File(it, "install-apo.ps1") }?.takeIf { it.isFile }

    /** A .NET part: a self-contained .exe runs as itself, a .dll through dotnet (Defender on his laptop, E-013). */
    fun dotnetCommand(part: File): List<String> =
        if (part.name.endsWith(".exe", ignoreCase = true)) listOf(part.absolutePath) else listOf("dotnet", part.absolutePath)
}
