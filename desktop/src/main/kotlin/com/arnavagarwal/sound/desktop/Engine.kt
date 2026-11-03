package com.arnavagarwal.sound.desktop

import com.arnavagarwal.sound.core.ContentClassifier
import com.arnavagarwal.sound.core.ContentProfile
import com.arnavagarwal.sound.core.KindVote
import com.arnavagarwal.sound.core.Curve
import com.arnavagarwal.sound.core.DensityControl
import com.arnavagarwal.sound.core.EarLevel
import com.arnavagarwal.sound.core.Glide
import com.arnavagarwal.sound.core.Grid
import com.arnavagarwal.sound.core.Loudness
import com.arnavagarwal.sound.core.Mixer
import com.arnavagarwal.sound.core.NowPlaying
import com.arnavagarwal.sound.core.SpectralFeatures
import com.arnavagarwal.sound.core.SongStats
import com.arnavagarwal.sound.core.Spectrum
import com.arnavagarwal.sound.core.Speaker
import com.arnavagarwal.sound.core.StrongMode
import com.arnavagarwal.sound.core.Tone
import com.arnavagarwal.sound.core.UserSwitches
import com.arnavagarwal.sound.core.AutoWidth
import com.arnavagarwal.sound.core.ListenLevel
import com.arnavagarwal.sound.core.QuietFix
import com.arnavagarwal.sound.core.VoiceClarity
import com.arnavagarwal.sound.core.VolumeCoach
import com.arnavagarwal.sound.core.WidthControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import kotlin.math.pow

/** Everything the window shows. One immutable snapshot, published about once a second. */
data class DesktopState(
    val running: Boolean = false,
    val helperAlive: Boolean = false,
    val apoInstalled: Boolean = false,
    val device: HelperEvent.Device? = null,
    /** Your headphones' own fix loaded for this output (AutoEq model name), or null. */
    val fixName: String? = null,
    val playing: HelperEvent.Playing? = null,
    val profile: ContentProfile? = null,
    val explanation: List<String> = emptyList(),
    /** The curve and preamp Equalizer APO is playing right now (after the glide, the ear level and the clip guard). */
    val curveDb: DoubleArray = DoubleArray(Grid.size),
    val preampDb: Double = 0.0,
    /** Taken off by the clip guard: Equalizer APO has no limiter, so we lower the whole song when the output nears 0 dBFS. */
    val clipTrimDb: Double = 0.0,
    val tuningOn: Boolean = true,
    /** Density: the preamp lift into the clean limiter after Equalizer APO (laptop speakers only). */
    val densityOn: Boolean = true,
    /** Density is actually playing now (on, tuning live, and the output is the laptop speakers). */
    val densityLive: Boolean = false,
    /** The lift the app chose for this song right now, and the most this kind of content may get. */
    val densityDb: Double = 0.0,
    val densityCapDb: Double = 0.0,
    val earLevelDb: Double = 0.0,
    val appTone: Tone = Tone.ZERO,
    /** The person's own slider moves, on top of [appTone]. */
    val manualTone: Tone = Tone.ZERO,
    val intensity: Double = 1.0,
    val clarityBefore: Double? = null,
    val clarityAfter: Double? = null,
    val problem: String? = null,
    /** His A/B answers so far: with tuning better / without better / same. */
    val tally: Triple<Int, Int, Int> = Triple(0, 0, 0),
    /** The Windows volume slider, as the helper last reported it. */
    val volume: HelperEvent.Volume? = null,
    /** Recommended volume for this song (core VolumeCoach); null while it is still learning his level. */
    val advice: VolumeCoach.Advice? = null,
    val songsLearned: Int = 0,
    /** Low-volume fix (core QuietFix): on/off, and the biggest lift it is adding right now. */
    val quietOn: Boolean = false,
    val quietDb: Double = 0.0,
    /** Auto width (core AutoWidth): on/off, the width playing (1 = none) and the song's own stereo once heard. */
    val widthOn: Boolean = true,
    val width: Double = 1.0,
    val songSideDb: Double? = null,
    /** Study music: the sung voice turned down by this many dB (0 = off). */
    val voiceDownDb: Double = 0.0,
    /** The gentler middle density learned from him (dB off 250 Hz - 5 kHz, whole song), 0 when not playing. */
    val softMiddleDb: Double = 0.0,
    /** Speaker-safe back-off playing now (LaptopSetting.speakerBackOffDb); 0 at or below the knee or when not live. */
    val safeDb: Double = 0.0,
    /** Strong mode for earbuds / headphones (his tap 31 Oct 2026); the speakers always have the liked setting. */
    val strongOn: Boolean = false,
    val strongStep: Int = StrongMode.BOLD,
) {
    /** Equalizer APO processes the current output: tuning can actually be heard there. */
    val reachesOutput get() = apoInstalled && device?.apo == true
}

/**
 * The desktop loop, the Windows twin of the phone's Brain. Ears = SoundHelper (loopback spectrum, now playing,
 * output device); hands = Equalizer APO's config file ([Apo]). Every 250 ms it reads the helper's frames and runs the
 * clip guard; once a second it classifies, plans and writes. All engine state lives on ONE thread ([exec]).
 *
 * Two things differ from the phone:
 *  - the loopback hears the sound AFTER Equalizer APO (E-011), so every frame is divided by the curve that was playing
 *    when it was captured, or a bass boost would look like a bass-heavy song and ask for more (same class as E-003);
 *  - Equalizer APO has no limiter, so a boost can clip. The helper reports the output's peak; above [CLIP_PEAK] the
 *    whole song goes down 1 dB at once, and comes back slowly once the peaks are safe again.
 * Writes happen at most once a second (plus an immediate one for the clip guard and the on/off button); Equalizer APO
 * cross-fades each reload over 10 ms (FilterEngine.cpp: transitionLength = sampleRate / 100), so a rewrite does not click.
 */
class Engine(
    private val apo: Apo, private val helperDll: File,
    private val prefs: Preferences = Preferences.userRoot().node("com/arnavagarwal/sound"),
    /** Preview: shows the screen with the tuning off and never saves the switch (his real setting stays as it was). */
    private val lookOnly: Boolean = false,
    /** Your headphones' own fix (AutoEq file per output, 1 Nov 2026). Preview: nowhere to keep one. */
    private val corrections: Corrections = Corrections(if (lookOnly) null else Corrections.defaultDir()),
) {
    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "sound-engine").apply { isDaemon = true } }
    private val inbox = ConcurrentLinkedQueue<Pair<Long, HelperEvent>>()
    private val helper = HelperProcess(helperDll) { inbox.add(System.currentTimeMillis() to it) }

    private val _state = MutableStateFlow(DesktopState())
    val state: StateFlow<DesktopState> = _state

    // ---- engine thread only ----
    private var running = false
    private var device: HelperEvent.Device? = null
    private var playing: HelperEvent.Playing? = null
    private val window = ArrayDeque<Captured>()
    /** [level] = the song's own level (our EQ divided out); [raw] = the level as it reaches the volume slider (after
     *  Equalizer APO, before the slider - volume_position.py); [side] = side/mid energy in dB, after Equalizer APO. */
    private class Captured(val atMs: Long, val spectrum: Spectrum, val level: Double, val peak: Double,
                           val raw: Double = level, val side: Double? = null)
    /** What Equalizer APO was playing from when: (effective from ms, total dB per grid point incl. preamp). */
    private val applied = Applied()
    private val glide = Glide(Grid.size + 1, tauSec = 1.5) // last slot = preamp
    // Strong mode starts OFF on every laptop that never switched it on (his 31 Oct tap: his sound must not change by itself)
    private var switches = UserSwitches(intensity = prefs.getDouble("intensity", 1.0),
        strongOn = !lookOnly && prefs.getBoolean("strongOn", false),
        strongStep = prefs.getInt("strongStep", StrongMode.BOLD).coerceIn(StrongMode.LIGHT, StrongMode.MAX))
    private val ear = EarLevel(prefs.getDouble("earLevelDb", 0.0))
    private var tuningOn = !lookOnly && prefs.getBoolean("tuningOn", true)
    private var densityOn = prefs.getBoolean("densityOn", true)
    private val density = DensityControl()
    private var lastDensityWriteMs = 0L
    private var densityCap = 0.0
    private var densitySong: String? = null
    /** The gentler middle playing now (core DensityControl.softMiddleDb), glided 0.5 dB a second toward its target. */
    private var softMiddleDb = 0.0
    /** This song's measurements and decisions, written as one line when the song changes (core SongStats). */
    private var songStats: SongStats? = null
    private var statsKey: String? = null
    private val songLog = SongLog(if (lookOnly) null else SongLog.defaultFile())
    private var trimDb = 0.0
    /** true while the output is the laptop's own speakers: we play [LaptopSetting], not the Mixer's plan. */
    private var likedSetting = false
    private var lastClipMs = 0L
    private var peakSinceTick = 0.0
    private var lastSecond = 0L
    private var lastHelperStart = 0L
    private var profile: ContentProfile? = null
    private var explanation: List<String> = emptyList()
    private var appTone = Tone.ZERO
    private var target: DoubleArray? = null
    private var clarityBefore: Double? = null
    private var clarityAfter: Double? = null
    private var problem: String? = null
    // recommended volume, low-volume fix, auto width (25 Sep, his plan "All three, in this order")
    private var volume: HelperEvent.Volume? = null
    /** Speaker-safe back-off for the current Windows volume (LaptopSetting.speakerBackOffDb), set once a plan. */
    private var safeDb = 0.0
    /** The kind the coach nudges by, held per song (core KindVote). */
    private val coachKind = KindVote()
    private val coach = VolumeCoach(prefs.get("listenLevels", "").split(',').mapNotNull { it.toDoubleOrNull() })
    private val listen = ListenLevel()
    private var advice: VolumeCoach.Advice? = null
    private var quietOn = prefs.getBoolean("quietOn", false) // off by default: blind test 25 Sep 20:50 "Same" (RULES.md)
    private var quietCurve = Curve.FLAT
    private var quietDropDb = 0.0
    private var widthOn = prefs.getBoolean("widthOn", false)
    /** Study music: how far the sung voice is turned down, dB (0 = off). His ask 28 Sep. */
    private var voiceDownDb = prefs.getDouble("voiceDownDb", 0.0)
    private val widthCtl = WidthControl()
    /** Raw frame levels of the last [LEVEL_MS], for the song's level where the slider meets it. */
    private val rawLevels = ArrayDeque<Pair<Long, Double>>()
    private var lastEverySecondMs = 0L
    private var extraCurve: Curve? = null
    private var quietMatchDb = 0.0
    private var widthMatchDb = 0.0
    /** Study music's lost loudness, given back (his tap 29 Sep "Soft + lift it back up"): soft must never mean quieter. */
    private var voiceMatchDb = 0.0
    private var songSpectrum: Spectrum? = null

    fun start() = exec.execute {
        if (running) return@execute
        running = true
        if (apo.installed) runCatching { apo.takeOver(); apo.neutral() }.onFailure { problem = "$WRITE_PROBLEM ${it.message}" }
        startHelper()
        exec.scheduleWithFixedDelay({ runCatching { tick() }.onFailure { problem = "Engine: ${it.message}" } }, 0, TICK_MS, TimeUnit.MILLISECONDS)
    }

    /** The laptop must sound exactly as before the app once this returns. */
    fun stop() {
        val done = exec.submit {
            songLog.write(songStats, device?.name); songStats = null
            running = false
            runCatching { apo.neutral() }
            helper.stop()
        }
        runCatching { done.get(3, TimeUnit.SECONDS) }
        exec.shutdownNow()
        runCatching { apo.neutral() } // again from this thread, in case the engine thread was stuck
    }

    // ---------------------------------------------------------------- commands from the window

    /** His test protocol (23 Sep): he replays the passage; this one button switches the tuning between passes. */
    fun setTuning(on: Boolean) = exec.execute {
        tuningOn = on; if (!lookOnly) prefs.putBoolean("tuningOn", on)
        target?.let { glide.snap(it) } // a switch between passes must be a clean switch, not a slow glide
        write(System.currentTimeMillis()); publish()
    }

    /** Density (automatic amount) or off, so it can be heard on its own with his protocol (E-017). A clean switch. */
    fun setDensity(on: Boolean) = exec.execute {
        densityOn = on; if (!lookOnly) prefs.putBoolean("densityOn", on)
        trimDb = 0.0; write(System.currentTimeMillis()); publish()
    }

    /** Low-volume fix on/off - a clean switch, so it can be heard alone (E-017). */
    fun setQuiet(on: Boolean) = exec.execute {
        quietOn = on; if (!lookOnly) prefs.putBoolean("quietOn", on)
        if (!on) { quietCurve = Curve.FLAT; quietDropDb = 0.0 }
        write(System.currentTimeMillis()); publish()
    }

    /** Study music: turn the sung voice down by [db] (0 = off). Works whether tuning is on or off. */
    fun setVoiceDown(db: Double) = exec.execute {
        voiceDownDb = db; if (!lookOnly) prefs.putDouble("voiceDownDb", db)
        voiceMatchDb = voiceMatch()
        write(System.currentTimeMillis()); publish()
    }

    /** Auto width on/off - a clean switch. The width for the song is already decided, so "on" is instant. */
    fun setWidth(on: Boolean) = exec.execute {
        widthOn = on; if (!lookOnly) prefs.putBoolean("widthOn", on)
        write(System.currentTimeMillis()); publish()
    }

    /** His tap on "Set it for me": the only way the app ever moves his volume. */
    fun useAdvice() = exec.execute {
        val a = advice ?: return@execute
        if (!lookOnly) helper.send(String.format(java.util.Locale.ROOT, "vol %.2f", a.wantSliderDb))
    }

    fun setIntensity(x: Double) = exec.execute {
        switches = switches.copy(intensity = x); prefs.putDouble("intensity", x); lastSecond = 0
    }

    /**
     * Strong mode (his tap 31 Oct 2026 "Build Strong mode", after "it didnt make a diff" on his OnePlus buds): the phone's
     * Strong mode (core StrongMode via Mixer) on earbuds and headphones - the laptop speakers' liked peaks on top of the
     * automatic tune. The speakers are untouched: they always get LaptopSetting. A clean switch, heard at once.
     */
    fun setStrong(on: Boolean) = exec.execute {
        switches = switches.copy(strongOn = on); if (!lookOnly) prefs.putBoolean("strongOn", on); lastSecond = 0; publish()
    }

    fun setStrongStep(step: Int) = exec.execute {
        val s = step.coerceIn(StrongMode.LIGHT, StrongMode.MAX)
        switches = switches.copy(strongStep = s); if (!lookOnly) prefs.putInt("strongStep", s); lastSecond = 0; publish()
    }

    /** The loaded headphone fix for the output playing now; the speakers never get one. */
    private var fix: Corrections.Loaded? = null
    private fun fixCurve(speaker: Boolean) = fixFor(speaker, fix)

    /**
     * Your headphones' own fix (his tap 1 Nov 2026; the phone has had it since September): an AutoEq ParametricEQ.txt
     * for the exact model, kept for THIS output only. [onDone] gets what to tell the person.
     */
    fun importFix(file: java.io.File, onDone: (String) -> Unit) = exec.execute {
        val d = device
        val msg = when {
            d == null -> Words.FIX_NO_OUTPUT
            d.isSpeaker -> Words.FIX_SPEAKERS
            else -> runCatching { file.readText() }.getOrNull()?.let { text ->
                val name = Corrections.nameFromFile(file.name)
                corrections.save(d.id, d.name, name, text) ?: run { fix = corrections.load(d.id); Words.fixDone(name, d.name) }
            } ?: Words.FIX_UNREADABLE
        }
        lastSecond = 0; publish(); onDone(msg)
    }

    fun removeFix() = exec.execute {
        device?.let { corrections.remove(it.id) }; fix = null; lastSecond = 0; publish()
    }

    fun setManualTone(t: Tone) = exec.execute { switches = switches.copy(manualTone = t); lastSecond = 0; publish() }

    /** After a pass: which side sounded louder. Moves the tuned side's level (core EarLevel). */
    fun heard(h: EarLevel.Heard) = exec.execute {
        ear.answer(h); prefs.putDouble("earLevelDb", ear.offsetDb); lastSecond = 0; publish()
    }

    /** His answer to "which sounded better?": true = with tuning, false = without, null = same. Counted, never sent anywhere. */
    fun judged(tunedBetter: Boolean?) = exec.execute {
        val k = when (tunedBetter) { true -> "wonTuned"; false -> "wonPlain"; null -> "wonSame" }
        prefs.putInt(k, prefs.getInt(k, 0) + 1); publish()
    }

    // ---------------------------------------------------------------- the loop

    private fun startHelper() {
        lastHelperStart = System.currentTimeMillis()
        if (!helperDll.isFile) { problem = "Sound helper not found at ${helperDll.path}"; return }
        runCatching { helper.start() }.onFailure { problem = "Could not start the sound helper: ${it.message}" }
    }

    private fun tick() {
        if (!running) return
        val now = System.currentTimeMillis()
        drain()
        if (!helper.alive && now - lastHelperStart > 5_000) startHelper()

        // clip guard: Equalizer APO has no limiter. With density on, reaching 0 dBFS is the point (the limiter after
        // APO holds it there), so only a peak ABOVE the ceiling counts: it means nothing downstream is limiting.
        if (peakSinceTick > clipPeak(densityLive()) && isLive()) {
            lastClipMs = now
            trimDb = (trimDb - 1.0).coerceAtLeast(-12.0)
            write(now)
        }
        peakSinceTick = 0.0
        blindSwitch(now)

        if (now / 1000 != lastSecond) {
            lastSecond = now / 1000
            if (trimDb < 0 && now - lastClipMs > 5_000) trimDb = (trimDb + 0.25).coerceAtMost(0.0)
            everySecond(now)
        }
    }

    /**
     * Live blind test (29 Sep): Claude writes a Study music amount (dB) into [BLIND_FILE]; the engine applies it and
     * deletes the file. Not saved to prefs, so his own setting is untouched; Claude writes his old value back after.
     */
    private fun blindSwitch(now: Long) {
        val f = BLIND_FILE ?: return
        if (!f.exists()) return
        val db = runCatching { f.readText().trim().toDouble() }.getOrNull()
        runCatching { f.delete() }
        if (db == null || db < 0.0 || db > 12.0) return
        voiceDownDb = db; voiceMatchDb = voiceMatch()
        write(now); publish()
    }

    /**
     * What Study music takes off the song's loudness, as a gain that gives it back (0 when off). Priced on the song AS IT
     * REACHES the cut, i.e. after the liked EQ (29 Sep): on the raw song it lifted ~0.5 dB live on Jo Tum against a true
     * 3.08 dB, because the liked EQ puts more of the loudness in the voice band and because un-colouring the loopback
     * through the 120 Hz high-pass inflates the sub-bass; multiplying the curve back in cancels that (LiftFormulaProbe).
     */
    private fun voiceMatch(): Double =
        LaptopSetting.voiceLiftDb(voiceDownDb, songSpectrum ?: Spectrum.PINK, switches.intensity, extraCurve, widthCtl.songSideDb, safeDb)

    private fun drain() {
        while (true) {
            val (at, e) = inbox.poll() ?: break
            when (e) {
                is HelperEvent.Device -> { if (e.id != device?.id) { lastSecond = 0; fix = corrections.load(e.id) }; device = e }
                is HelperEvent.Playing -> playing = e
                is HelperEvent.Frame -> {
                    peakSinceTick = maxOf(peakSinceTick, e.peak)
                    val raw = e.mags?.let { Spectrum.fromFft(it, e.fs) } ?: Spectrum(DoubleArray(Grid.size))
                    val (spec, level) = uncolour(raw, e.rmsDbfs, at)
                    window.addLast(Captured(at, spec, level, e.peak, e.rmsDbfs, e.sideDb))
                    songStats?.frame(spec, level)
                    while (window.isNotEmpty() && at - window.first().atMs > WINDOW_MS) window.removeFirst()
                    rawLevels.addLast(at to e.rmsDbfs)
                    while (rawLevels.isNotEmpty() && at - rawLevels.first().first > LEVEL_MS) rawLevels.removeFirst()
                }
                is HelperEvent.Volume -> volume = e
                is HelperEvent.Problem -> if (e.where == "helper") problem = "Sound helper ${e.msg}" else problem = "${e.where}: ${e.msg}"
            }
        }
    }

    private fun uncolour(raw: Spectrum, rmsDbfs: Double, atMs: Long) = applied.uncolour(raw, rmsDbfs, atMs)

    /**
     * Density's amount for this song (core DensityControl): capped by the kind of content when the classifier is sure
     * of it, steered by how often the limiter after APO holds the output at its ceiling. Frames from before the last
     * write are skipped: they were made with the old lift and would steer twice.
     */
    private fun steerDensity(prof: ContentProfile, songKey: String?) {
        val kind = prof.top.takeIf { prof.confidence >= KIND_SURE }
        if (songKey != densitySong) { densitySong = songKey; density.reset(DensityControl.cap(kind, switches.intensity) * DensityControl.UNSURE_FLOOR) }
        // one vote a second, only while something sounds (the window's peaks, whatever the lift)
        if (DensityControl.ceilingShare(window.map { it.peak }) != null) density.vote(kind != null)
        densityCap = (density.songCap(kind, switches.intensity) - safeDb).coerceAtLeast(0.0)
        val before = density.liftDb
        val peaks = if (densityLive()) window.filter { it.atMs >= lastDensityWriteMs + RELOAD_MS }.map { it.peak } else emptyList()
        density.update(DensityControl.ceilingShare(peaks), densityCap)
        if (density.liftDb != before) lastDensityWriteMs = System.currentTimeMillis()
        // his liked "density + Voice softer" learned as a tone (29 Sep); his own Study music choice wins, no double cut;
        // nothing until the song has shown what it is, so a beat song's opening is never dulled
        val soft = if (SOFT_MIDDLE_ON && densityOn && voiceDownDb <= 0.0 && density.judged()) DensityControl.softMiddleDb(density.unsureShare()) else 0.0
        softMiddleDb += (soft - softMiddleDb).coerceIn(-SOFT_STEP_DB, SOFT_STEP_DB)
    }

    /** One second of this song's log: every decision the app made (his ask 25 Sep 21:03: "learn all things"). */
    private fun trackSong(np: NowPlaying?, prof: ContentProfile, speaker: Boolean, rate: Double) {
        val key = np?.songKey
        if (key != statsKey) {
            songLog.write(songStats, device?.name)
            statsKey = key
            songStats = if (key == null) null else SongStats(listOfNotNull(np.artist, np.title).joinToString(" - "), np.packageName ?: "")
        }
        val s = songStats ?: return
        s.frameRateHz = rate
        val live = densityLive()
        s.second(prof.top.takeIf { prof.confidence >= KIND_SURE }, if (live) density.liftDb else null,
            if (live) DensityControl.ceilingShare(window.map { it.peak }) else null)
        fun b(x: Boolean) = if (x) 1.0 else 0.0
        s.note("tuningOn", b(isLive())); s.note("speaker", b(speaker)); s.note("strength", switches.intensity)
        s.note("kindConfidence", prof.confidence)
        s.note("strongOn", b(switches.strongOn)); s.note("strongStep", switches.strongStep.toDouble())
        s.note("densityOn", b(densityOn)); s.note("densityCapDb", densityCap)
        s.note("sliderDb", volume?.db); s.note("safeDb", safeDb); s.note("muted", volume?.mute?.let(::b)); s.note("adviceChangeDb", advice?.changeDb)
        s.note("quietOn", b(quietOn)); s.note("quietLiftDb", quietCurve.db.maxOrNull())
        s.note("widthOn", b(widthOn)); s.note("width", widthNow())
        s.note("voiceDownDb", voiceDownDb); s.note("voiceMatchDb", voiceMatchDb)
        s.note("earOffsetDb", ear.offsetDb); s.note("clipTrimDb", trimDb)
        val k = switches.manualTone
        s.note("knobBass", k.bass); s.note("knobWarmth", k.warmth); s.note("knobClarity", k.clarity); s.note("knobTreble", k.treble)
    }

    private fun everySecond(now: Long) {
        val w = window.toList()
        val rate = if (w.size >= 2) (w.size - 1) * 1000.0 / (w.last().atMs - w.first().atMs).coerceAtLeast(1) else 20.0
        val features = SpectralFeatures.from(w.map { it.spectrum }, w.map { it.level }, rate)
        val avg = w.filter { it.level > -60 }.takeIf { it.size >= 5 }?.let { l ->
            Spectrum(DoubleArray(Grid.size) { g -> l.sumOf { it.spectrum.power[g] } / l.size })
        }
        val np = playing?.let { NowPlaying(packageName = it.app, title = it.title, artist = it.artist, album = it.album, genre = it.genre) }
        val prof = ContentClassifier.classify(np, features)
        val speaker = device?.isSpeaker ?: true
        // Strong reaches the Mixer only for headphones: on the speakers the liked setting below replaces the plan anyway
        // the headphones' own fix goes in only off the speakers (the speakers play LaptopSetting, never this plan)
        val plan = Mixer.plan(prof, null, mixerSwitches(switches, speaker), fixCurve(speaker), null, spectrum = avg ?: Spectrum.PINK, speaker = speaker,
            speakerProfile = Speaker.LAPTOP)
        profile = prof; explanation = plan.explanation; appTone = plan.appTone
        likedSetting = speaker
        val applied: Curve
        if (speaker) {
            // his liked setting, exactly (23 Sep ~20:55); the song is still recognised and shown
            safeDb = LaptopSetting.speakerBackOffDb(volume?.db, volume?.mute == true)
            steerDensity(prof, np?.songKey)
            levelWork(now, prof, np?.songKey)
            val manual = switches.manualTone.takeIf { it != Tone.ZERO }?.curve()
            extraCurve = extras(manual)
            // loudness-matched: the low-volume fix changes the balance, not the level; wider must not mean louder
            quietMatchDb = LaptopSetting.quietMatchDb(quietCurve, avg ?: songSpectrum ?: Spectrum.PINK, switches.intensity, manual, safeDb)
            widthMatchDb = widthCtl.songSideDb?.let { -AutoWidth.loudnessDb(it, widthNow()) } ?: 0.0
            songSpectrum = avg ?: songSpectrum; voiceMatchDb = voiceMatch()
            applied = LaptopSetting.curve(switches.intensity, extraCurve, safeDb)
            appTone = Tone.fit(LaptopSetting.curve(switches.intensity, null, safeDb))
            explanation = listOf("the laptop setting you liked") + (if (manual != null) listOf("your knobs") else emptyList())
            target = (applied.db + LaptopSetting.preampDb(switches.intensity, ear.offsetDb + quietMatchDb + widthMatchDb + voiceMatchDb)).also { glide.snap(it) }
        } else {
            advice = null; quietCurve = Curve.FLAT
            applied = plan.curve
            val pre = headphonePreampDb(plan.preampDb, ear.offsetDb)
            target = (plan.curve.db + pre).also { glide.step(it, 1.0) }
        }
        trackSong(np, prof, speaker, rate)
        clarityBefore = avg?.let { VoiceClarity.score(it) }
        clarityAfter = if (avg != null && isLive()) VoiceClarity.score(avg, applied) else null
        write(now)
        publish()
    }

    /**
     * Once a second, laptop speakers only: learn his listening level, advise the slider, set the low-volume fix and
     * decide this song's width. All three read the level where the slider meets the song (the RAW frame level:
     * after Equalizer APO, before the slider). The low-volume fix is loudness-matched in [write], so it does not move
     * that level itself - no loop between the fix and the level it reacts to.
     */
    private fun levelWork(now: Long, prof: ContentProfile, songKey: String?) {
        val fresh = window.filter { it.atMs > lastEverySecondMs }
        lastEverySecondMs = now
        val v = volume
        val kind = prof.top.takeIf { prof.confidence >= KIND_SURE }
        val sounding = rawLevels.map { it.second }.filter { it > ListenLevel.SILENT_DB }
        val songLevel = if (sounding.size < 40) null
            else 10 * kotlin.math.log10(sounding.sumOf { 10.0.pow(it / 10) } / sounding.size)

        if (v != null) listen.update(now, songKey, v.db, v.mute, fresh.map { it.raw })?.let { l ->
            coach.learn(l); if (!lookOnly) prefs.put("listenLevels", coach.saved.joinToString(",") { "%.2f".format(java.util.Locale.ROOT, it) })
        }
        advice = if (v == null || v.mute || songLevel == null) null
            else coach.advise(songLevel, v.db, coachKind.update(songKey, kind), v.minDb, v.maxDb)

        val usual = coach.usualDb
        if (quietOn && usual != null && v != null && !v.mute && songLevel != null) {
            val drop = usual - (songLevel + v.db)
            quietDropDb += 0.3 * (drop - quietDropDb) // a volume change settles over a few seconds, not in one jump
            quietCurve = QuietFix.curve(quietDropDb, switches.intensity)
        } else if (!quietOn) quietCurve = Curve.FLAT

        widthCtl.update(songKey, fresh.filter { it.raw > ListenLevel.SILENT_DB }.mapNotNull { it.side }, FRAME_MS, kind, switches.intensity)
    }

    /** His knobs plus the low-volume fix, as one extra curve on top of the liked setting; null when both are zero. */
    private fun extras(manual: Curve?): Curve? {
        val q = quietCurve.takeIf { quietOn && it.db.any { d -> d != 0.0 } }
        return when { manual == null -> q; q == null -> manual; else -> manual + q }
    }

    private fun widthNow() =if (widthOn && likedSetting) widthCtl.width else 1.0

    private fun isLive() = tuningOn && apo.installed && device?.apo != false
    private fun densityLive() = densityOn && likedSetting && isLive()
    /** The gentler middle to write: Density Off takes it away at once, not by the glide. */
    private fun softNow() = if (densityOn) softMiddleDb else 0.0

    /** Glides one step toward the target and writes it (or neutral), only when the text changes. */
    private fun write(now: Long) {
        val t = target
        val total: DoubleArray
        try {
            if (t == null || !isLive()) {
                // study music works with tuning off too: only the voice-down lines, else neutral
                if (voiceDownDb > 0 && apo.installed) apo.writeText(Apo.NEUTRAL + LaptopSetting.voiceDownText(voiceDownDb)) else apo.neutral()
                total = LaptopSetting.voiceDownCurve(voiceDownDb).db.copyOf()
            } else {
                val g = glide.current
                val curve = Curve(g.copyOfRange(0, Grid.size))
                val pre = g[Grid.size] + trimDb
                if (likedSetting) {
                    val w = widthNow()
                    apo.writeText(LaptopSetting.text(switches.intensity,
                        ear.offsetDb + trimDb + (if (quietOn) quietMatchDb else 0.0) + (if (w > 1.0) widthMatchDb else 0.0) + voiceMatchDb,
                        extras(switches.manualTone.takeIf { it != Tone.ZERO }?.curve()),
                        densityDb = if (densityOn) density.liftDb else 0.0, width = w, safeDb = safeDb) + LaptopSetting.voiceDownText(voiceDownDb) +
                        LaptopSetting.softMiddleText(softNow()))
                }
                else apo.writeText(Apo.render(curve, pre) + LaptopSetting.voiceDownText(voiceDownDb))
                // the density lift is NOT recorded for un-colouring: the limiter after APO takes it back off the peaks,
                // so the loopback level no longer carries it cleanly; the song's shape (what classify uses) is unchanged
                val vd = LaptopSetting.voiceDownCurve(voiceDownDb).db
                val sm = LaptopSetting.voiceDownCurve(if (likedSetting) softNow() else 0.0).db   // same shelves, whole song
                total = DoubleArray(Grid.size) { curve.db[it] + pre + vd[it] + sm[it] }
            }
            if (problem?.startsWith(WRITE_PROBLEM) == true) problem = null
        } catch (e: Exception) {
            problem = "$WRITE_PROBLEM ${e.message}"; return
        }
        // Equalizer APO reloads within about a second (E-011); frames before that still carry the old curve
        applied.record(now + RELOAD_MS, total)
    }

    private fun publish() {
        val g = glide.current
        _state.value = DesktopState(
            running = running, helperAlive = helper.alive, apoInstalled = apo.installed, device = device, playing = playing,
            fixName = fix?.name,
            profile = profile, explanation = explanation,
            curveDb = if (isLive()) g.copyOfRange(0, Grid.size) else DoubleArray(Grid.size),
            preampDb = if (isLive()) g[Grid.size] + trimDb else 0.0, clipTrimDb = trimDb,
            tuningOn = tuningOn, densityOn = densityOn, densityLive = densityLive(),
            densityDb = if (densityLive()) density.liftDb else 0.0, densityCapDb = densityCap, earLevelDb = ear.offsetDb, appTone = appTone, manualTone = switches.manualTone, intensity = switches.intensity,
            clarityBefore = clarityBefore, clarityAfter = clarityAfter, problem = problem,
            tally = Triple(prefs.getInt("wonTuned", 0), prefs.getInt("wonPlain", 0), prefs.getInt("wonSame", 0)),
            volume = volume, advice = if (likedSetting) advice else null, songsLearned = coach.saved.size,
            quietOn = quietOn, quietDb = if (isLive() && likedSetting && quietOn) quietCurve.db.maxOrNull() ?: 0.0 else 0.0,
            widthOn = widthOn, width = if (isLive()) widthNow() else 1.0, songSideDb = widthCtl.songSideDb,
            voiceDownDb = voiceDownDb,
            softMiddleDb = if (densityLive()) softNow() else 0.0,
            safeDb = if (isLive() && likedSetting) safeDb else 0.0,
            strongOn = switches.strongOn, strongStep = switches.strongStep,
        )
    }

    companion object {
        /** What the Mixer is told: Strong mode only for headphones; the speakers' liked setting replaces the plan anyway. */
        fun mixerSwitches(sw: UserSwitches, speaker: Boolean): UserSwitches = if (speaker) sw.copy(strongOn = false) else sw

        /** The headphones' own fix the Mixer gets: none on the speakers, whatever is loaded elsewhere. */
        fun fixFor(speaker: Boolean, fix: Corrections.Loaded?): com.arnavagarwal.sound.core.Curve? =
            if (speaker) null else fix?.profile?.curve()

        /** Headphones never get a preamp above 0 dB: there is no limiter after Equalizer APO there, so Strong's
         *  allowed rise is used only where the tune left headroom (the clip guard trimDb still sits on top). */
        fun headphonePreampDb(planPreampDb: Double, earOffsetDb: Double): Double = (planPreampDb + earOffsetDb).coerceAtMost(0.0)

        const val TICK_MS = 250L
        /** Live blind test switch, see blindSwitch. */
        val BLIND_FILE: java.io.File? = System.getenv("LOCALAPPDATA")?.let { java.io.File(it, "SoundApp/blind-voice.txt") }
        /** Also density's steering window: it needs 2 s of frames after the last change has reloaded (RELOAD_MS). */
        const val WINDOW_MS = 3_000L
        const val RELOAD_MS = 500L
        /** The gentler middle moves at most this much a second, so it is never a jump. */
        const val SOFT_STEP_DB = 0.5
        /** OFF (his words 29 Sep ~00:20): he wants it per MOMENT the voice leads, not per song; a blind test decides. */
        const val SOFT_MIDDLE_ON = false
        /** The song's level for the volume advice and the low-volume fix: the last 10 s of frames. */
        const val LEVEL_MS = 10_000L
        /** The helper sends a frame every 50 ms (fs / 20). */
        const val FRAME_MS = 50L
        const val CLIP_PEAK = 0.98
        /** With density on, the limiter after APO holds peaks at 0 dBFS; a peak above this means nothing is limiting. */
        const val CEILING_PEAK = 1.02
        fun clipPeak(density: Boolean) = if (density) CEILING_PEAK else CLIP_PEAK
        /** Below this classifier confidence the kind does not cap density (a wrong "classical" would switch it off). */
        const val KIND_SURE = 0.5
        const val WRITE_PROBLEM = "Could not write Equalizer APO's settings:"
    }
}

/**
 * What Equalizer APO was playing, and from when. The loopback hears the sound AFTER Equalizer APO (E-011), so a frame
 * is divided by the curve that was live when it was captured; otherwise our own bass boost would look like a
 * bass-heavy song and the plan would ask for still more (the same feedback loop as E-003 on the phone).
 */
class Applied {
    private val list = ArrayDeque<Pair<Long, DoubleArray>>()

    /** [totalDb] (curve + preamp per grid point) is live from [fromMs]. */
    fun record(fromMs: Long, totalDb: DoubleArray) {
        list.addLast(fromMs to totalDb)
        while (list.size > 8) list.removeFirst()
    }

    /** The curve live at [atMs], or null when nothing of ours was (frames then are the song itself). Times must not go back. */
    fun at(atMs: Long): DoubleArray? {
        while (list.size > 1 && list[1].first <= atMs) list.removeFirst()
        return list.firstOrNull()?.takeIf { it.first <= atMs }?.second
    }

    /** The frame as the song was before Equalizer APO: spectrum and level. */
    fun uncolour(raw: Spectrum, rmsDbfs: Double, atMs: Long): Pair<Spectrum, Double> {
        val total = at(atMs) ?: return raw to rmsDbfs
        val spec = Spectrum(DoubleArray(Grid.size) { raw.power[it] / 10.0.pow(total[it] / 10) })
        return spec to (rmsDbfs - Loudness.changeDb(Curve(total), spec))
    }
}
