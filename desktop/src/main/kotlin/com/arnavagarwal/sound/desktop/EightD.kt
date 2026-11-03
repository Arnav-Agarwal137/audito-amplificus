package com.arnavagarwal.sound.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * 8D copy of a song FILE (his tap 29 Sep "Make 8D copies"; live 8D by rewriting the APO file clicks, ERRORS E-027).
 * The work is done by tools/eightd/eightd.py (MIT KEMAR head measurements, D:/dev/hrtf) in the Demucs venv. That
 * script's LAST line is "OK <file>" or "FAILED <reason>"; only that line is trusted, as in [Karaoke].
 */
object EightD {
    sealed class State {
        object Idle : State()
        data class Working(val song: String) : State()
        data class Done(val song: String, val file: File) : State()
        data class Failed(val why: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val PYTHON = ToolsRoot.python("demucs-venv")
    private val SCRIPT = File(System.getProperty("user.dir")).let { d ->
        listOf(File(d, "tools/eightd/eightd.py"), File(d, "../tools/eightd/eightd.py")).firstOrNull { it.isFile }
    }

    /** False on a laptop without this tool (the portable copy for other people): its card is not shown at all. */
    val available: Boolean get() = PYTHON.isFile && SCRIPT != null

    fun pickAndRun() {
        if (_state.value is State.Working) return
        val dlg = FileDialog(null as Frame?, "Pick a song file", FileDialog.LOAD)
        dlg.isVisible = true
        val name = dlg.file ?: return
        run(File(dlg.directory, name))
    }

    fun run(song: File) {
        val script = SCRIPT
        if (!PYTHON.isFile || script == null) { _state.value = State.Failed("the 8D helper is not installed on this laptop"); return }
        _state.value = State.Working(song.nameWithoutExtension)
        Thread {
            _state.value = try {
                val p = ProcessBuilder(PYTHON.path, script.path, song.path).redirectErrorStream(true).let(ToolsRoot::start)
                val last = p.inputStream.bufferedReader().readLines().lastOrNull { it.isNotBlank() } ?: ""
                p.waitFor()
                when {
                    last.startsWith("OK ") -> State.Done(song.nameWithoutExtension, File(last.removePrefix("OK ")))
                    last.startsWith("FAILED ") -> State.Failed(last.removePrefix("FAILED "))
                    else -> State.Failed("the helper stopped without saying why")
                }
            } catch (e: Exception) { State.Failed(e.message ?: e.javaClass.simpleName) }
        }.apply { isDaemon = true }.start()
    }

    /** Opens the folder with the new file in it. */
    fun openFolder() {
        val s = _state.value as? State.Done ?: return
        runCatching { Desktop.getDesktop().open(s.file.absoluteFile.parentFile) }
    }
}
