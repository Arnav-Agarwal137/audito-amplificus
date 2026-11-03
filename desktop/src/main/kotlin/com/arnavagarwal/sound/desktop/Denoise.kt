package com.arnavagarwal.sound.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Clean the noise out of a TALK file - lecture, video, voice note (his tap 28 Sep: "Clean a file"). The work is done by
 * tools/denoise/denoise.py (DeepFilterNet3 + Demucs, both MIT) in its own venv on D:. It writes a cleaned copy beside
 * the file. Whatever he picks is cleaned - no music check (his words 29 Sep: "if anybody has put it there, that means
 * that they want it cleaned"). Its LAST line is "OK <file>" or "FAILED <reason>"; only that line is trusted, never the
 * exit code alone.
 */
object Denoise {
    sealed class State {
        object Idle : State()
        /** A file is picked and the strength is being asked (his tap 28 Sep: no choice shown while idle). */
        data class Chosen(val file: File) : State()
        data class Working(val name: String) : State()
        data class Done(val name: String, val file: File) : State()
        data class Failed(val why: String) : State()
    }

    /** Gentle / Stronger: noise is turned down by at most this much, never deleted (his warning about intended sounds). */
    const val GENTLE_DB = 12
    const val STRONG_DB = 24
    /** Voice only (his tap 29 Sep): everything that is not the voice is taken out. Passed as --voice-only. */
    const val VOICE_ONLY = -1

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val PYTHON = ToolsRoot.python("denoise-venv")
    private val SCRIPT = File(System.getProperty("user.dir")).let { d ->
        listOf(File(d, "tools/denoise/denoise.py"), File(d, "../tools/denoise/denoise.py")).firstOrNull { it.isFile }
    }

    /** False on a laptop without this tool (the portable copy for other people): its card is not shown at all. */
    val available: Boolean get() = PYTHON.isFile && SCRIPT != null

    /** Step 1: pick the file. The strength is asked next ([choose]), so nothing looks switched on while idle. */
    fun pick() {
        if (_state.value is State.Working) return
        val dlg = FileDialog(null as Frame?, "Pick a lecture, video or voice note", FileDialog.LOAD)
        dlg.isVisible = true
        val name = dlg.file ?: return
        _state.value = State.Chosen(File(dlg.directory, name))
    }

    /** Step 2: the strength for the picked file; null = cancel. */
    fun choose(limitDb: Int?) {
        val s = _state.value as? State.Chosen ?: return
        if (limitDb == null) _state.value = State.Idle else run(s.file, limitDb)
    }

    fun run(file: File, limitDb: Int) {
        if (!Edition.researchModelsAllowed()) { _state.value = State.Failed(Edition.NOT_IN_PAID); return }
        val script = SCRIPT
        if (!PYTHON.isFile || script == null) { _state.value = State.Failed("the noise cleaner is not installed on this laptop"); return }
        _state.value = State.Working(file.nameWithoutExtension)
        Thread {
            _state.value = try {
                val how = if (limitDb == VOICE_ONLY) listOf("--voice-only") else listOf("--limit", limitDb.toString())
                val p = ProcessBuilder(listOf(PYTHON.path, script.path, file.path) + how)
                    .redirectErrorStream(true).let(ToolsRoot::start)
                val last = p.inputStream.bufferedReader().readLines().lastOrNull { it.isNotBlank() } ?: ""
                p.waitFor()
                when {
                    last.startsWith("OK ") -> State.Done(file.nameWithoutExtension, File(last.removePrefix("OK ")))
                    last.startsWith("FAILED ") -> State.Failed(last.removePrefix("FAILED "))
                    else -> State.Failed("the cleaner stopped without saying why")
                }
            } catch (e: Exception) { State.Failed(e.message ?: e.javaClass.simpleName) }
        }.apply { isDaemon = true }.start()
    }

    fun openFolder() {
        val s = _state.value as? State.Done ?: return
        runCatching { Desktop.getDesktop().open(s.file.parentFile) }
    }
}
