package com.arnavagarwal.sound.desktop

import com.arnavagarwal.sound.core.SongStats
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.OffsetDateTime

/**
 * One line per song he plays, in %LOCALAPPDATA%\SoundApp\songs.jsonl: what the app measured and what it decided
 * (core SongStats). The voice-led density rule, and later ones, are set from these lines (his choice, 25 Sep). Local
 * only; a look-only window writes nothing. A failure to write is swallowed: logging must never stop the tuning.
 */
class SongLog(private val file: File? = defaultFile()) {
    fun write(stats: SongStats?, device: String?) {
        val f = file ?: return
        val m = stats?.summary() ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val row = linkedMapOf<String, Any?>("at" to OffsetDateTime.now().withNano(0).toString(), "device" to device) + m
            f.appendText(json(row).toString() + "\n", Charsets.UTF_8)
        }
    }

    companion object {
        fun defaultFile(): File? = System.getenv("LOCALAPPDATA")?.let { File(it, "SoundApp/songs.jsonl") }

        fun json(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to json(x) })
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    }
}
