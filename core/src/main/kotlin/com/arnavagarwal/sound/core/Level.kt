package com.arnavagarwal.sound.core

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Recommended volume WITHOUT a mic (his choice, 25 Sep: the laptop mic's echo canceller erased the test beeps, E-022).
 *
 * Nothing here knows how loud the speakers are in the room. What it knows is where HE likes a song to sit: the song's
 * level where the slider meets it (loopback, which is before the slider) plus the slider's dB. That sum moves with the
 * sound in the room one-for-one, so it is his listening level up to an unknown constant, and the constant cancels:
 * the advice is "the slider level that puts THIS song where you usually put songs", nudged by the kind of content
 * (speech needs less level than bass-heavy music - the same offsets as the phone's [VolumeAdvisor]).
 *
 * Two limits, both from his own evidence rather than a measurement we could not make:
 *  - never above the loudest level he has chosen himself - the speakers are proven there, beyond it is unknown;
 *  - "about right" within [GOOD_DB]: a smaller step is not worth asking him to make.
 */
class VolumeCoach(levels: List<Double> = emptyList()) {
    private val levels = ArrayDeque(levels.takeLast(KEEP))

    /** One settled listen: song level (dBFS at the loopback) + slider dB, at a slider he left alone for a while. */
    fun learn(listenDb: Double) {
        levels.addLast(listenDb)
        while (levels.size > KEEP) levels.removeFirst()
    }

    val saved: List<Double> get() = levels.toList()
    val ready get() = levels.size >= MIN_SONGS
    /** His usual listening level (median: one very loud or quiet evening does not move it). */
    val usualDb: Double? get() = if (!ready) null else levels.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    val loudestDb: Double? get() = levels.maxOrNull()

    enum class Verdict { UP, GOOD, DOWN }

    /** [wantSliderDb] is where the slider should go (inside the device's range); [changeDb] = how far from now. */
    data class Advice(val verdict: Verdict, val wantSliderDb: Double, val changeDb: Double)

    /** null while it is still learning his level. */
    fun advise(songLevelDb: Double, sliderDb: Double, kind: ContentKind?, minDb: Double, maxDb: Double): Advice? {
        val usual = usualDb ?: return null
        val target = (usual + kindOffsetDb(kind)).coerceAtMost(loudestDb ?: usual)
        val want = (target - songLevelDb).coerceIn(minDb, maxDb)
        val change = want - sliderDb
        val v = when {
            abs(change) < GOOD_DB -> Verdict.GOOD
            change > 0 -> Verdict.UP
            else -> Verdict.DOWN
        }
        return Advice(v, want, change)
    }

    /**
     * The phone (29 Sep): its volume moves in steps of 3-4 dB, wider than [GOOD_DB], so a want between two steps made
     * the laptop verdict flip: "up a little" -> tap -> one step past it -> "down a little" -> ... (seen on the emulator).
     * Here the verdict is about STEPS: GOOD when the step he is on is already the nearest one to the want, else UP/DOWN
     * to that step; [Advice.wantSliderDb] / [Advice.changeDb] are that step's dB and the distance to it.
     * [stepDb] = dB of each step (index 0 = mute, never advised); no step above [maxDb] is advised (the safe cap).
     * A want near the midpoint of two steps must not flip with each second's level either: another step is advised only
     * when it is nearer by more than [STEP_HYST_DB] (never when the current step is above the safe cap).
     */
    fun adviseSteps(songLevelDb: Double, stepDb: DoubleArray, current: Int, kind: ContentKind?, maxDb: Double): Advice? {
        if (stepDb.size < 2 || current !in stepDb.indices) return null
        val raw = advise(songLevelDb, stepDb[current], kind, stepDb[1], maxDb) ?: return null
        val want = raw.wantSliderDb
        var best = nearestStep(stepDb, want)
        while (best > 1 && stepDb[best] > maxDb + 1e-9) best--
        val currentAllowed = current >= 1 && stepDb[current] <= maxDb + 1e-9
        if (best != current && currentAllowed && abs(want - stepDb[current]) - abs(want - stepDb[best]) <= STEP_HYST_DB) best = current
        val v = when {
            best == current -> Verdict.GOOD
            best > current -> Verdict.UP
            else -> Verdict.DOWN
        }
        return Advice(v, stepDb[best], stepDb[best] - stepDb[current])
    }

    companion object {
        const val MIN_SONGS = 3
        const val KEEP = 40
        const val GOOD_DB = 2.0
        /** [adviseSteps]: how much nearer another phone step must be before it is advised. */
        const val STEP_HYST_DB = GOOD_DB
        private val MEAN_TARGET = VolumeAdvisor.targetDb.values.average()
        /** Speech -6 dB against the average, bass-heavy +3 ... Unknown kind: 0. */
        fun kindOffsetDb(kind: ContentKind?): Double = kind?.let { VolumeAdvisor.targetDb.getValue(it) - MEAN_TARGET } ?: 0.0

        /**
         * The phone (29 Sep): its Visualizer may hear the sound AFTER the volume ([includesVolume], LevelCalibrator),
         * while the coach wants the song's level before it, as the laptop's loopback gives. Frames at the Visualizer's
         * floor are silence and are dropped FIRST: taking the volume out of them first turned -96 into a song (E-029).
         */
        fun beforeVolume(measuredDb: List<Double>, sliderDb: Double, includesVolume: Boolean): List<Double> =
            measuredDb.filter { it > MEASURED_FLOOR_DB }.map { if (includesVolume) it - sliderDb else it }

        /** The Visualizer reports -96 dBFS for silence. */
        const val MEASURED_FLOOR_DB = -95.0

        /** "Set it for me" on the phone: the volume step nearest [wantDb]; never step 0 (mute). */
        fun nearestStep(stepDb: DoubleArray, wantDb: Double): Int =
            (1 until stepDb.size).minByOrNull { abs(stepDb[it] - wantDb) } ?: 0
    }
}

/**
 * The kind [VolumeCoach] nudges by, held per song (29 Sep, emulator log): the classifier's sure/unsure answer changed every
 * second or two on one song, so the target moved by the kind offset (3 dB) and the card flipped up / good / up with the
 * song level steady within 1 dB. Each call is one vote (null = not sure); the held kind changes only when another kind
 * leads it by [LEAD] votes, and a new song starts over.
 */
class KindVote {
    private var songKey: String? = null
    private val votes = HashMap<ContentKind?, Int>()
    var held: ContentKind? = null
        private set

    fun update(songKey: String?, kind: ContentKind?): ContentKind? {
        if (songKey != this.songKey) { this.songKey = songKey; votes.clear(); held = kind }
        votes[kind] = (votes[kind] ?: 0) + 1
        val (leader, n) = votes.maxBy { it.value }.toPair()
        if (leader != held && n >= (votes[held] ?: 0) + LEAD) held = leader
        return held
    }

    companion object { const val LEAD = 5 }
}

/**
 * Collects one settled listen per song for [VolumeCoach]: the song must have played [SETTLE_MS] with the slider left
 * alone (a slider he keeps touching is not a choice yet), not muted, with sound. Level = power average of the frames
 * above [SILENT_DB], so pauses and fades do not pull it down.
 */
class ListenLevel {
    private var song: String? = null
    private var sliderDb = Double.NaN
    private var steadySinceMs = 0L
    private var sumPow = 0.0
    private var n = 0
    private var taken = false

    /** Feed once a second. Returns a level to learn exactly once per song, when it has settled. */
    fun update(nowMs: Long, songKey: String?, slider: Double, muted: Boolean, frameLevelsDb: List<Double>): Double? {
        if (songKey != song) { song = songKey; taken = false; restart(nowMs, slider) }
        if (abs(slider - sliderDb) > 0.01 || muted) restart(nowMs, slider)
        if (muted || songKey == null || taken) return null
        for (l in frameLevelsDb) if (l > SILENT_DB) { sumPow += 10.0.pow(l / 10); n++ }
        if (nowMs - steadySinceMs < SETTLE_MS || n < MIN_FRAMES) return null
        taken = true
        val level = 10 * log10(sumPow / n) + slider
        return level.takeIf { it >= MIN_LISTEN_DB } // below this nobody is listening (a test at the slider's floor)
    }

    private fun restart(nowMs: Long, slider: Double) { sliderDb = slider; steadySinceMs = nowMs; sumPow = 0.0; n = 0 }

    companion object {
        const val SETTLE_MS = 30_000L
        const val SILENT_DB = -60.0
        const val MIN_FRAMES = 200 // 10 s of sound at 20 frames a second
        /** 40 dB under his usual (~-20): inaudible on these speakers, so never a listening choice (25 Sep, -83.45). */
        const val MIN_LISTEN_DB = -60.0
    }
}

/**
 * The low-volume fix (his choice, 25 Sep). Ears lose bass first, and a little treble, as sound gets quieter
 * (ISO 226, [EqualLoudness]); so when he listens below his usual level, add back what the ear stopped hearing.
 * At or above his usual level it does nothing. Laptop speakers cannot play below about [LOWEST_HZ] (his liked setting
 * already high-passes at 120 Hz), so the lift there is not added - it would only spend headroom on sound nobody hears.
 */
object QuietFix {
    /** His usual level is taken as a normal listening level: the curve depends on the drop, far less on this guess. */
    const val USUAL_PHON = 75.0
    const val STRENGTH = 0.6
    const val MAX_DB = 6.0
    const val LOWEST_HZ = 150.0
    /** Smaller drops are not worth a change. */
    const val MIN_DROP_DB = 2.0

    /** [dropDb] = how far below his usual level he is listening now (positive = quieter). */
    fun curve(dropDb: Double, strength: Double = 1.0): Curve {
        if (dropDb < MIN_DROP_DB || strength <= 0) return Curve.FLAT
        val c = EqualLoudness.compensation(USUAL_PHON - dropDb, USUAL_PHON, STRENGTH * strength, MAX_DB)
        return Curve(DoubleArray(Grid.size) { g -> if (Grid.freqs[g] < LOWEST_HZ) 0.0 else c.db[g] })
    }
}

/**
 * Auto stereo width (his choice, 25 Sep). A laptop's two speakers sit a hand's width apart, so a song's stereo
 * collapses toward the middle. When a song HAS stereo in it, its side part (L-R) is raised; a mono-ish song, or talk,
 * is left alone - there is nothing to widen, only noise. Bass is never widened (the side part is high-passed first
 * in Equalizer APO), and the loudness the extra side adds is taken back so "wider" is never secretly "louder".
 *
 * [sideDb] = side energy against mid energy, as the helper measures it (the song's own, before our widening).
 */
object AutoWidth {
    /** At or above this the song is already wide: leave it. */
    const val WIDE_DB = -4.0
    /** Below this it is (nearly) mono: nothing to widen. */
    const val MONO_DB = -22.0
    /** The most the side part is raised: 1.6x = +4.1 dB. */
    const val MAX_WIDTH = 1.6
    const val STEP_DB = 4.0
    const val BASS_HZ = 250.0

    /** 1.0 = unchanged. Talk and classical get none (a centred voice gains nothing; classical's hall is its own). */
    fun width(sideDb: Double?, kind: ContentKind?, strength: Double = 1.0): Double {
        if (sideDb == null || sideDb < MONO_DB || sideDb >= WIDE_DB) return 1.0
        if (kind == ContentKind.SPEECH || kind == ContentKind.CLASSICAL) return 1.0
        val targetDb = minOf(sideDb + STEP_DB * strength, WIDE_DB)
        return 10.0.pow((targetDb - sideDb) / 20).coerceIn(1.0, MAX_WIDTH)
    }

    /** How much louder the widened song is (whole band, an upper bound since the bass is not widened), in dB. */
    fun loudnessDb(sideDb: Double, width: Double): Double {
        val s = 10.0.pow(sideDb / 10)
        return 10 * log10((1 + width * width * s) / (1 + s))
    }
}

/**
 * Decides one width per song. The helper hears AFTER Equalizer APO, so once widening plays, what it reports includes
 * our own widening (and only above [AutoWidth.BASS_HZ], so it cannot simply be divided back out). So each song starts
 * unwidened, is listened to for [LISTEN_MS] of sound, and the width chosen then is held for the rest of the song -
 * no feedback loop is possible. (Same shape as density: settles per song, then holds.)
 */
class WidthControl {
    private var song: String? = null
    private val side = ArrayList<Double>()
    private var soundMs = 0L
    /** The width playing now; 1.0 until this song has been heard. */
    var width = 1.0; private set
    /** The song's own side level once decided (for the screen), else null. */
    var songSideDb: Double? = null; private set

    /** Feed once a second with the frames since the last call (side dB of frames that had sound). */
    fun update(songKey: String?, frameSideDb: List<Double>, frameMs: Long, kind: ContentKind?, strength: Double): Double {
        if (songKey != song) { song = songKey; side.clear(); soundMs = 0; width = 1.0; songSideDb = null }
        if (songSideDb != null || songKey == null) return width
        side.addAll(frameSideDb); soundMs += frameSideDb.size * frameMs
        if (soundMs >= LISTEN_MS && side.size >= 20) {
            val s = side.sorted()[side.size / 2]
            songSideDb = s
            width = AutoWidth.width(s, kind, strength)
        }
        return width
    }

    companion object { const val LISTEN_MS = 8_000L }
}
