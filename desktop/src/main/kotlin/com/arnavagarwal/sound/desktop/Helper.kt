package com.arnavagarwal.sound.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** One message from SoundHelper (windows/helper/Program.cs). */
sealed interface HelperEvent {
    /** The default output. [form] is Core Audio's form factor ("speakers", "headphones", ...); [apo] = Equalizer APO is on it. */
    data class Device(val id: String, val name: String, val form: String, val bluetooth: Boolean, val apo: Boolean) : HelperEvent {
        val isSpeaker get() = form == "speakers" && !bluetooth
        val isHeadphones get() = form == "headphones" || form == "headset" || bluetooth
    }
    data class Playing(val app: String?, val title: String?, val artist: String?, val album: String?,
                       val genre: String?, val playing: Boolean) : HelperEvent
    /** One spectrum frame of the output mix AFTER Equalizer APO (loopback sits behind it - E-011). */
    class Frame(val fs: Double, val rmsDbfs: Double, val peak: Double, val mags: DoubleArray?, val sideDb: Double? = null) : HelperEvent
    /** The Windows volume slider of the default output: [db] (what the ear follows), [scalar] 0..1 (the % shown). */
    data class Volume(val db: Double, val scalar: Double, val mute: Boolean, val minDb: Double, val maxDb: Double) : HelperEvent
    data class Problem(val where: String, val msg: String) : HelperEvent
}

object HelperParse {
    private val json = Json { ignoreUnknownKeys = true }

    /** Parses one line; null for anything that is not a message we know (never throws). */
    fun line(s: String): HelperEvent? = runCatching {
        val o = json.parseToJsonElement(s).jsonObject
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        fun num(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
        when (str("t")) {
            "dev" -> HelperEvent.Device(str("id") ?: "", str("name") ?: "", str("form") ?: "unknown",
                (o["bt"] as? JsonPrimitive)?.booleanOrNull ?: false, (o["apo"] as? JsonPrimitive)?.booleanOrNull ?: false)
            "np" -> HelperEvent.Playing(str("app"), str("title")?.ifBlank { null }, str("artist")?.ifBlank { null },
                str("album")?.ifBlank { null },
                (o["genres"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull,
                (o["playing"] as? JsonPrimitive)?.booleanOrNull ?: false)
            "fft" -> HelperEvent.Frame(num("fs") ?: 48000.0, num("rms") ?: -96.0, num("peak") ?: 0.0,
                o["m"]!!.jsonArray.map { it.jsonPrimitive.doubleOrNull ?: 0.0 }.toDoubleArray(), num("sd"))
            "lvl" -> HelperEvent.Frame(0.0, num("rms") ?: -96.0, num("peak") ?: 0.0, null, num("sd"))
            "vol" -> HelperEvent.Volume(num("db") ?: return@runCatching null, num("s") ?: 0.0,
                (o["mute"] as? JsonPrimitive)?.booleanOrNull ?: false, num("min") ?: -65.25, num("max") ?: 0.0)
            "err" -> HelperEvent.Problem(str("where") ?: "?", str("msg") ?: "")
            else -> null
        }
    }.getOrNull()
}

/**
 * Runs SoundHelper as `dotnet SoundHelper.dll` (a fresh .exe is blocked by Defender on his laptop - E-013) and hands
 * every message to [onEvent] on the reader thread. Closing stdin makes the helper exit, so it cannot outlive us.
 */
class HelperProcess(private val dll: File, private val onEvent: (HelperEvent) -> Unit) {
    @Volatile private var proc: Process? = null
    @Volatile var lastEventMs = 0L; private set

    fun start() {
        if (proc?.isAlive == true) return
        val p = ProcessBuilder(ToolsRoot.dotnetCommand(dll)).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        proc = p
        Thread({
            runCatching {
                p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { l ->
                    lastEventMs = System.currentTimeMillis()
                    HelperParse.line(l)?.let(onEvent)
                }
            }
            onEvent(HelperEvent.Problem("helper", "stopped (exit ${runCatching { p.waitFor() }.getOrNull()})"))
        }, "sound-helper-reader").apply { isDaemon = true }.start()
    }

    val alive get() = proc?.isAlive == true

    /** One command line to the helper ("vol -12.5"). Never throws: a dead helper is restarted by the engine. */
    fun send(line: String) {
        runCatching { proc?.outputStream?.let { it.write((line + "\n").toByteArray()); it.flush() } }
    }

    fun stop() {
        val p = proc ?: return
        proc = null
        runCatching { p.outputStream.write("quit\n".toByteArray()); p.outputStream.close() }
        if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
    }
}
