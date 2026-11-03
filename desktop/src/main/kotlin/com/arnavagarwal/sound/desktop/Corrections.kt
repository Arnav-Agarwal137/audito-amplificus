package com.arnavagarwal.sound.desktop

import com.arnavagarwal.sound.core.AutoEqProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

/**
 * "Your headphones' own fix" on the laptop (his tap, 1 Nov 2026): one AutoEq ParametricEQ.txt per output, the same
 * file the phone imports (core AutoEqProfile). Kept per Windows output id, so the OnePlus buds' file is never used
 * on the HyperX. [dir] null = nowhere to keep it (preview): reads find nothing and saving refuses.
 */
class Corrections(private val dir: File?) {
    class Loaded(val name: String, val profile: AutoEqProfile)

    /** A Windows output id is long and full of braces; the file name is its SHA-256, so any id is a safe name. */
    private fun fileFor(deviceId: String): File? = dir?.let {
        val h = MessageDigest.getInstance("SHA-256").digest(deviceId.toByteArray()).joinToString("") { b -> "%02x".format(b) }
        File(it, "$h.json")
    }

    fun load(deviceId: String): Loaded? = runCatching {
        val f = fileFor(deviceId)?.takeIf { it.isFile } ?: return null
        val o = Json.parseToJsonElement(f.readText()).jsonObject
        if (o["deviceId"]?.jsonPrimitive?.content != deviceId) return null // a hash clash or a hand-copied file: not ours
        val name = o["name"]!!.jsonPrimitive.content
        AutoEqProfile.parse(name, o["text"]!!.jsonPrimitive.content).profile?.let { Loaded(name, it) }
    }.getOrNull()

    /** Reads [text] and keeps it for this output. Returns null when it worked, else what to tell the person. */
    fun save(deviceId: String, deviceName: String, name: String, text: String): String? {
        val parsed = AutoEqProfile.parse(name, text)
        val p = parsed.profile ?: return when {
            parsed.problems.any { "no usable filters" in it } -> Words.FIX_NO_FILTERS // core's words are for developers
            else -> parsed.problems.firstOrNull() ?: Words.FIX_UNREADABLE
        }
        if (p.filters.isEmpty()) return Words.FIX_NO_FILTERS
        val f = fileFor(deviceId) ?: return Words.FIX_NOT_IN_PREVIEW
        return runCatching {
            f.parentFile.mkdirs()
            f.writeText(buildJsonObject {
                put("deviceId", JsonPrimitive(deviceId)); put("deviceName", JsonPrimitive(deviceName))
                put("name", JsonPrimitive(name)); put("text", JsonPrimitive(text))
            }.toString())
            null
        }.getOrElse { Words.FIX_COULD_NOT_SAVE }
    }

    fun remove(deviceId: String) { fileFor(deviceId)?.delete() }

    companion object {
        fun defaultDir(): File? = System.getenv("LOCALAPPDATA")?.let { File(it, "SoundApp/headphone-fixes") }

        /** "Sony WH-1000XM4 ParametricEQ.txt" -> "Sony WH-1000XM4". */
        fun nameFromFile(fileName: String): String =
            fileName.substringBeforeLast('.').replace(Regex("\\s*ParametricEQ$", RegexOption.IGNORE_CASE), "").trim().ifEmpty { "Imported" }
    }
}
