package com.arnavagarwal.sound.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** What is playing, as reported by the playing app's media session (all fields optional). */
data class NowPlaying(
    val packageName: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val durationMs: Long? = null,
) {
    /** Stable key for per-song memory: same song from any app maps to the same key. */
    val songKey: String?
        get() {
            val t = title?.lowercase()?.replace(Regex("""\(.*?\)|\[.*?]|\b(official|video|lyrics?|lyrical|audio|hd|4k)\b"""), " ")
                ?.replace(Regex("[^\\p{L}\\p{N}]+"), " ")?.trim()
            if (t.isNullOrBlank()) return null
            val a = artist?.lowercase()?.replace(Regex("[^\\p{L}\\p{N}]+"), " ")?.trim().orEmpty()
            return "$t|$a"
        }
}

/**
 * The kinds of content the app tells apart. Each one maps to a starting sound (see [Presets]); the
 * [TasteLearner] then bends that starting point toward what this particular person prefers.
 */
enum class ContentKind(val label: String) {
    SPEECH("Speech / lecture"),
    BASS_HEAVY("Bass-heavy (EDM, hip-hop, Punjabi, phonk)"),
    SMOOTH("Smooth / acoustic / lo-fi"),
    VOCAL_POP("Vocal pop / Bollywood"),
    CLASSICAL("Classical / instrumental"),
    ENERGETIC("Rock / metal / energetic"),
    MOVIE("Film / video"),
}

/** Features computed from a few seconds of the output mix (Visualizer on the phone). */
data class SpectralFeatures(
    val bassFraction: Double,     // power share below 150 Hz
    val centroidHz: Double,       // brightness
    val flatness: Double,         // 0 tonal .. 1 noise-like
    val modulation4Hz: Double,    // share of loudness-envelope energy at 2-8 Hz: syllable rate, high for speech
    val pauseFraction: Double,    // share of frames near silence: speech pauses, music rarely does
    val levelDbfs: Double,        // average level, dB relative to full scale
) {
    companion object {
        /**
         * From a history of per-frame spectra and levels captured at [frameRateHz] (Visualizer gives ~10-20 Hz).
         * Needs >= 1.5 s of frames; returns null when there is not enough signal to judge (silence is not data).
         */
        fun from(spectra: List<Spectrum>, levelsDbfs: List<Double>, frameRateHz: Double): SpectralFeatures? {
            require(spectra.size == levelsDbfs.size)
            if (spectra.size < (1.5 * frameRateHz).toInt() || spectra.isEmpty()) return null
            val loud = levelsDbfs.count { it > -60 }
            if (loud < spectra.size / 3) return null
            val avg = Spectrum(DoubleArray(Grid.size) { g -> spectra.sumOf { it.power[g] } / spectra.size })
            val maxLevel = levelsDbfs.max()
            val pauses = levelsDbfs.count { it < maxLevel - 25 }.toDouble() / levelsDbfs.size
            return SpectralFeatures(
                bassFraction = avg.fractionBelow(150.0),
                centroidHz = avg.centroidHz(),
                flatness = avg.flatness(),
                modulation4Hz = envelopeModulation(levelsDbfs, frameRateHz),
                pauseFraction = pauses,
                levelDbfs = levelsDbfs.filter { it > -60 }.average(),
            )
        }

        /** Share of the loudness envelope's variation that sits at 2-8 Hz (DFT of the de-meaned envelope). */
        fun envelopeModulation(levelsDb: List<Double>, frameRateHz: Double): Double {
            val env = levelsDb.map { Math.pow(10.0, it.coerceAtLeast(-90.0) / 20) }
            val mean = env.average()
            val x = env.map { it - mean }
            val n = x.size
            var inBand = 0.0; var total = 0.0
            for (k in 1..n / 2) {
                val f = k * frameRateHz / n
                var re = 0.0; var im = 0.0
                for (t in 0 until n) { val a = 2 * PI * k * t / n; re += x[t] * cos(a); im -= x[t] * sin(a) }
                val p = re * re + im * im
                total += p
                if (f in 2.0..8.0) inBand += p
            }
            return if (total <= 0) 0.0 else inBand / total
        }
    }
}

/** Classifier output: a probability per kind, plus the feature vector the taste learner uses. */
data class ContentProfile(val probs: Map<ContentKind, Double>, val reason: String) {
    val top: ContentKind get() = probs.maxBy { it.value }.key
    val confidence: Double get() = probs.getValue(top)
    /** Fixed-order vector of probabilities: the learner's notion of "what kind of thing this is". */
    fun vector(): DoubleArray = DoubleArray(ContentKind.entries.size) { probs[ContentKind.entries[it]] ?: 0.0 }
}

/**
 * Decides what is playing from (1) the app it comes from, (2) words in the title/genre, and (3) how it
 * sounds. Each source adds evidence as log-odds; nothing is a hard rule, so a lecture on Spotify or a
 * song on YouTube still lands right when the other evidence is strong.
 *
 * No web lookups: the free metadata services forbid commercial use (ARCHITECTURE.md), and on-device
 * keeps listening habits private.
 */
object ContentClassifier {
    private val musicApps = setOf("com.spotify.music", "com.google.android.apps.youtube.music", "com.jio.media.jiobeats",
        "com.gaana", "com.bsbportal.music", "com.maxmpz.audioplayer", "com.apple.android.music", "com.amazon.mp3",
        "com.miui.player", "com.oneplus.music", "com.sec.android.app.music", "in.startv.hotstar.music", "com.soundcloud.android")
    private val speechApps = setOf("com.google.android.apps.podcasts", "com.audible.application", "fm.castbox.audiobook.radio.podcast",
        "com.pocketcasts", "au.com.shiftyjelly.pocketcasts", "com.kukufm", "com.pocketfm.novel", "com.storytel",
        "org.coursera.android", "com.duolingo", "com.unacademy.consumption.unacademyapp", "co.khanacademy.android")
    private val videoApps = setOf("com.google.android.youtube", "com.netflix.mediaclient", "in.startv.hotstar",
        "com.amazon.avod.thirdpartyclient", "com.jio.jioplay.tv", "com.instagram.android", "com.mxtech.videoplayer.ad", "org.videolan.vlc")

    private data class Kw(val words: List<String>, val kind: ContentKind, val weight: Double)
    private val keywords = listOf(
        Kw(listOf("lecture", "lesson", "chapter", "class ", "podcast", "episode", "ep.", "interview", "explained", "tutorial",
            "neet", "jee", "upsc", "biology", "physics", "chemistry", "anatomy", "physiology", "course", "audiobook", "talk", "ted"), ContentKind.SPEECH, 2.5),
        Kw(listOf("bass boosted", "phonk", "trap", "edm", "dj ", "remix", "drill", "hip hop", "hip-hop", "rap", "dubstep", "house mix",
            "punjabi", "bhangra"), ContentKind.BASS_HEAVY, 1.8),
        Kw(listOf("lofi", "lo-fi", "lo fi", "acoustic", "unplugged", "slowed", "reverb", "chill", "sleep", "calm", "piano cover",
            "ghazal", "sufi", "soft", "romantic", "jazz"), ContentKind.SMOOTH, 1.8),
        Kw(listOf("classical", "symphony", "concerto", "sonata", "raag", "raga", "instrumental", "orchestra", "sitar", "violin",
            "flute", "carnatic", "hindustani"), ContentKind.CLASSICAL, 2.0),
        Kw(listOf("rock", "metal", "punk", "grunge", "live concert", "guitar solo"), ContentKind.ENERGETIC, 1.8),
        Kw(listOf("trailer", "full movie", "movie", "scene", "episode 1", "web series", "teaser"), ContentKind.MOVIE, 1.6),
        Kw(listOf("official video", "full song", "lyrical", "bollywood", "arijit", "shreya", "pop"), ContentKind.VOCAL_POP, 1.2),
    )

    fun classify(np: NowPlaying?, sf: SpectralFeatures?): ContentProfile {
        val score = ContentKind.entries.associateWith { 0.0 }.toMutableMap()
        val why = mutableListOf<String>()
        fun add(k: ContentKind, w: Double) { score[k] = score.getValue(k) + w }

        // mild prior: most listening is songs
        add(ContentKind.VOCAL_POP, 0.4)

        val pkg = np?.packageName
        when {
            pkg in musicApps -> { ContentKind.entries.filter { it != ContentKind.SPEECH && it != ContentKind.MOVIE }.forEach { add(it, 0.6) }; why += "music app" }
            pkg in speechApps -> { add(ContentKind.SPEECH, 2.5); why += "speech app" }
            pkg in videoApps -> { add(ContentKind.MOVIE, 0.6); add(ContentKind.SPEECH, 0.4); why += "video app" }
        }

        val text = listOfNotNull(np?.title, np?.album, np?.genre, np?.artist).joinToString(" ").lowercase().let { " $it " }
        for (kw in keywords) {
            val hit = kw.words.firstOrNull { wordRegex(it).containsMatchIn(text) }
            if (hit != null) { add(kw.kind, kw.weight); why += "\"${hit.trim()}\"" }
        }
        // long tracks are more often talks/lectures/films than songs
        np?.durationMs?.let { d ->
            if (d > 20 * 60_000) { add(ContentKind.SPEECH, 0.8); add(ContentKind.MOVIE, 0.6); why += "long (${d / 60_000} min)" }
            else if (d in 90_000..420_000) { ContentKind.entries.filter { it != ContentKind.SPEECH && it != ContentKind.MOVIE }.forEach { add(it, 0.3) } }
        }

        if (sf != null) {
            // speech: syllable-rate modulation + pauses + little bass
            val speechEv = 3.0 * (sf.modulation4Hz - 0.3) + 2.5 * (sf.pauseFraction - 0.1) - 4.0 * (sf.bassFraction - 0.15)
            add(ContentKind.SPEECH, speechEv.coerceIn(-2.5, 3.0))
            add(ContentKind.BASS_HEAVY, (8.0 * (sf.bassFraction - 0.35)).coerceIn(-2.0, 2.5))
            add(ContentKind.SMOOTH, (1.5 * (1 - sf.flatness * 3) - 1.0 * (sf.bassFraction - 0.3) * 3).coerceIn(-1.5, 1.5))
            add(ContentKind.ENERGETIC, (4.0 * (sf.flatness - 0.25)).coerceIn(-1.5, 1.5))
            why += "sound: bass ${"%.0f".format(sf.bassFraction * 100)}%, speech-rhythm ${"%.2f".format(sf.modulation4Hz)}"
        }

        val m = score.values.max()
        val exps = score.mapValues { exp(it.value - m) }
        val z = exps.values.sum()
        return ContentProfile(exps.mapValues { it.value / z }, why.joinToString(", ").ifEmpty { "no clues yet" })
    }

    /** Entropy-based certainty 0..1 (1 = sure), used to decide how strongly to apply a preset. */
    fun certainty(p: ContentProfile): Double {
        val h = -p.probs.values.filter { it > 0 }.sumOf { it * ln(it) }
        return (1 - h / ln(ContentKind.entries.size.toDouble())).coerceIn(0.0, 1.0)
    }

    /** Whole-word match, so "rap" does not fire on "trap" or "grapes", nor "class" on "classical". */
    private val wordCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private fun wordRegex(w: String) = wordCache.getOrPut(w) {
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(w.trim()) + "(?![\\p{L}\\p{N}])")
    }

    internal fun l2(a: DoubleArray, b: DoubleArray) = sqrt(a.indices.sumOf { (a[it] - b[it]).let { d -> d * d } })
}
