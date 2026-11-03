package com.arnavagarwal.sound.engine

import android.content.Context
import com.arnavagarwal.sound.core.AutoEqProfile
import com.arnavagarwal.sound.core.DoseLedger
import com.arnavagarwal.sound.core.TasteLearner
import com.arnavagarwal.sound.core.Fine
import com.arnavagarwal.sound.core.Tone
import com.arnavagarwal.sound.core.UserSwitches
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small settings. SharedPreferences keys are part of the app's contract: never rename one. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("sound", Context.MODE_PRIVATE)

    var engineOn: Boolean
        get() = sp.getBoolean("engine_on", false)
        set(v) = sp.edit().putBoolean("engine_on", v).apply()

    /** Calls [onChange] whenever [engineOn] changes from anywhere (the notification button included); returns the undo. */
    fun watchEngine(onChange: (Boolean) -> Unit): () -> Unit {
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == "engine_on") onChange(p.getBoolean("engine_on", false))
        }
        sp.registerOnSharedPreferenceChangeListener(l) // SharedPreferences holds listeners weakly: `l` lives in the returned undo
        return { sp.unregisterOnSharedPreferenceChangeListener(l) }
    }

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    /** Vivo / iQOO's own battery switch: Android cannot tell whether it is on, so the card asks until he taps "Done". */
    var makerSwitchDone: Boolean
        get() = sp.getBoolean("maker_switch_done", false)
        set(v) = sp.edit().putBoolean("maker_switch_done", v).apply()

    /** Whether a runtime permission has been requested before: needed to tell "never asked" from "Android will not ask again". */
    fun asked(permission: String): Boolean = sp.getBoolean("asked_$permission", false)
    fun markAsked(permission: String) = sp.edit().putBoolean("asked_$permission", true).apply()

    /** Visualizer level includes the volume setting? Default TRUE = overestimates level = the safe side. */
    var levelIncludesVolume: Boolean
        get() = sp.getBoolean("level_includes_volume", true)
        set(v) = sp.edit().putBoolean("level_includes_volume", v).apply()

    init {
        // Quiet listening OFF by default like the laptop (his tap 29 Sep 2026 "OFF, like laptop"). Every save wrote
        // sw_loud=true under the old default, so an install that never chose it is reset ONCE; later choices stand.
        if (!sp.getBoolean("sw_loud_off_once", false))
            sp.edit().putBoolean("sw_loud", false).putBoolean("sw_loud_off_once", true).apply()
    }

    /** Density (core DensityControl): on by default, like the laptop. */
    var densityOn: Boolean
        get() = sp.getBoolean("density_on", true)
        set(v) = sp.edit().putBoolean("density_on", v).apply()

    /** The volume coach's learned listening levels, per output (a speaker level and an earbud level are not comparable). */
    fun listenLevels(outputKey: String): List<Double> =
        sp.getString("listen_$outputKey", "").orEmpty().split(",").mapNotNull { it.toDoubleOrNull() }
    fun setListenLevels(outputKey: String, levels: List<Double>) =
        sp.edit().putString("listen_$outputKey", levels.joinToString(",") { "%.2f".format(java.util.Locale.ROOT, it) }).apply()

    var switches: UserSwitches
        get() = UserSwitches(
            autoEnabled = sp.getBoolean("sw_auto", true),
            learnEnabled = sp.getBoolean("sw_learn", true),
            correctionEnabled = sp.getBoolean("sw_corr", true),
            loudnessCompEnabled = sp.getBoolean("sw_loud", false),
            nightMode = sp.getBoolean("sw_night", false),
            eightD = sp.getBoolean("sw_8d", false),
            eightDPeriodSec = sp.getFloat("sw_8d_period", 8f).toDouble(),
            manualTone = Tone(sp.getFloat("t_bass", 0f).toDouble(), sp.getFloat("t_warm", 0f).toDouble(),
                sp.getFloat("t_clar", 0f).toDouble(), sp.getFloat("t_treb", 0f).toDouble()),
            intensity = sp.getFloat("sw_intensity", 1f).toDouble(),
            manualFine = Fine(sp.getFloat("f_rumble", 0f).toDouble(), sp.getFloat("f_presence", 0f).toDouble(),
                sp.getFloat("f_air", 0f).toDouble()),
            voiceDownDb = sp.getFloat("sw_study", 0f).toDouble(),
            // Strong mode (his tap 9 Oct 2026): absent on every older install, so it reads OFF and today's sound is kept
            strongOn = sp.getBoolean("sw_strong", false),
            strongStep = sp.getInt("sw_strong_step", com.arnavagarwal.sound.core.StrongMode.BOLD),
        )
        set(s) = sp.edit()
            .putBoolean("sw_auto", s.autoEnabled).putBoolean("sw_learn", s.learnEnabled)
            .putBoolean("sw_corr", s.correctionEnabled).putBoolean("sw_loud", s.loudnessCompEnabled)
            .putBoolean("sw_night", s.nightMode).putBoolean("sw_8d", s.eightD)
            .putFloat("sw_8d_period", s.eightDPeriodSec.toFloat()).putFloat("sw_study", s.voiceDownDb.toFloat())
            .putFloat("t_bass", s.manualTone.bass.toFloat()).putFloat("t_warm", s.manualTone.warmth.toFloat())
            .putFloat("t_clar", s.manualTone.clarity.toFloat()).putFloat("t_treb", s.manualTone.treble.toFloat())
            .putFloat("sw_intensity", s.intensity.toFloat())
            .putFloat("f_rumble", s.manualFine.rumble.toFloat()).putFloat("f_presence", s.manualFine.presence.toFloat())
            .putFloat("f_air", s.manualFine.air.toFloat())
            .putBoolean("sw_strong", s.strongOn).putInt("sw_strong_step", s.strongStep)
            .apply()

    var seenPackages: Set<String>
        get() = sp.getStringSet("seen_pkgs", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("seen_pkgs", v).apply()
}

/**
 * Durable data as JSON files in the app's private storage: taste samples, dose ledger, earbud profiles.
 * Writes go to a temp file then rename, so a crash mid-write never leaves a half file.
 */
class DataStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "data").apply { mkdirs() }

    private fun write(name: String, text: String) {
        val tmp = File(dir, "$name.tmp"); tmp.writeText(text)
        if (!tmp.renameTo(File(dir, name))) { File(dir, name).delete(); tmp.renameTo(File(dir, name)) }
    }
    private fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()

    fun saveTaste(samples: List<TasteLearner.Sample>) {
        val arr = JSONArray()
        for (s in samples) arr.put(JSONObject().apply {
            put("c", JSONArray(s.content.toList())); put("t", JSONArray(s.tone.toArray().toList()))
            put("k", s.songKey ?: JSONObject.NULL); put("ms", s.timeMs)
        })
        write("taste.json", arr.toString())
    }

    fun loadTaste(): List<TasteLearner.Sample> = runCatching {
        val arr = JSONArray(read("taste.json") ?: return emptyList())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val c = o.getJSONArray("c"); val t = o.getJSONArray("t")
            TasteLearner.Sample(
                DoubleArray(c.length()) { c.getDouble(it) },
                Tone.of(DoubleArray(4) { t.getDouble(it) }),
                if (o.isNull("k")) null else o.getString("k"),
                o.getLong("ms"),
            )
        }
    }.getOrDefault(emptyList())

    fun saveDose(l: DoseLedger) {
        val o = JSONObject(); for ((h, d) in l.snapshot()) o.put(h.toString(), d)
        write("dose.json", o.toString())
    }

    fun loadDose(): DoseLedger = runCatching {
        val o = JSONObject(read("dose.json") ?: return DoseLedger())
        val m = HashMap<Long, Double>(); o.keys().forEach { m[it.toLong()] = o.getDouble(it) }
        DoseLedger(m)
    }.getOrDefault(DoseLedger())

    /** Earbud correction files, one per output key (the user imports them; see ARCHITECTURE.md licence note). */
    fun saveCorrection(outputKey: String, name: String, text: String) {
        val o = JSONObject(read("corrections.json") ?: "{}")
        o.put(outputKey, JSONObject().put("name", name).put("text", text))
        write("corrections.json", o.toString())
    }

    fun removeCorrection(outputKey: String) {
        val o = JSONObject(read("corrections.json") ?: "{}"); o.remove(outputKey)
        write("corrections.json", o.toString())
    }

    fun loadCorrection(outputKey: String): AutoEqProfile? = runCatching {
        val o = JSONObject(read("corrections.json") ?: return null).optJSONObject(outputKey) ?: return null
        AutoEqProfile.parse(o.getString("name"), o.getString("text")).profile
    }.getOrNull()
}
