package com.arnavagarwal.sound.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.arnavagarwal.sound.core.RestPolicy
import com.arnavagarwal.sound.core.AlertLimiter
import com.arnavagarwal.sound.core.ContentClassifier
import com.arnavagarwal.sound.core.ContentProfile
import com.arnavagarwal.sound.core.KindVote
import com.arnavagarwal.sound.core.Curve
import com.arnavagarwal.sound.core.DEFAULT_BAND_CUTOFFS
import com.arnavagarwal.sound.core.DensityControl
import com.arnavagarwal.sound.core.Dynamics
import com.arnavagarwal.sound.core.EightD
import com.arnavagarwal.sound.core.EnginePlan
import com.arnavagarwal.sound.core.Glide
import com.arnavagarwal.sound.core.KnobLearning
import com.arnavagarwal.sound.core.ListenLevel
import com.arnavagarwal.sound.core.VolumeCoach
import com.arnavagarwal.sound.core.LevelCalibrator
import com.arnavagarwal.sound.core.Loudness
import com.arnavagarwal.sound.core.Speaker
import com.arnavagarwal.sound.core.Mixer
import com.arnavagarwal.sound.core.NowPlaying
import com.arnavagarwal.sound.core.SessionPolicy
import com.arnavagarwal.sound.core.SoundDose
import com.arnavagarwal.sound.core.Spectrum
import com.arnavagarwal.sound.core.TasteLearner
import com.arnavagarwal.sound.core.Tone
import com.arnavagarwal.sound.core.UserSwitches
import com.arnavagarwal.sound.core.VoiceClarity
import com.arnavagarwal.sound.core.VolumeAdvisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Everything the screen and the notification show. One immutable snapshot, published about once a second. */
data class EngineState(
    val running: Boolean = false,
    val now: NowPlaying? = null,
    val isPlaying: Boolean = false,
    val profile: ContentProfile? = null,
    val explanation: List<String> = emptyList(),
    val bandsDb: DoubleArray = DoubleArray(DEFAULT_BAND_CUTOFFS.size),
    val preampDb: Double = 0.0,
    val output: Output = Output(Output.Kind.SPEAKER, "Phone speaker"),
    val advice: VolumeAdvisor.Advice? = null,
    val weekFraction: Double = 0.0,
    val estimatedSplDb: Double? = null,
    val globalAttached: Boolean = false,
    val globalActive: Boolean = false,
    val announced: Map<Int, AnnouncedSession> = emptyMap(),
    val seenPackages: Set<String> = emptySet(),
    val analyzerOn: Boolean = false,
    /** A phone or internet call is on and the engine is standing down (CallGuard). */
    val inCall: Boolean = false,
    val hasNowPlayingAccess: Boolean = false,
    val eightDActive: Boolean = false,
    val correctionName: String? = null,
    val challenge: ChallengeState? = null,
    val knobsHeldSec: Double = 0.0,
    val tasteSamples: Int = 0,
    /** Voice clarity 0..1 of the sound as it arrives, and as the app plays it; null = nothing measured yet. */
    val clarityBefore: Double? = null,
    val clarityAfter: Double? = null,
    /** What the app itself chose for this sound, in the four knobs' units; the sliders start here. */
    val appTone: Tone = Tone.ZERO,
    /** Density: switched on; actually playing now (phone speaker, a chain live); the lift and this song's cap in dB. */
    val densityOn: Boolean = true,
    val densityLive: Boolean = false,
    val densityDb: Double = 0.0,
    val densityCapDb: Double = 0.0,
    /** The volume coach (core VolumeCoach, the laptop's): its advice for this song, and how many songs it has learned. */
    val volumeAdvice: VolumeCoach.Advice? = null,
    val songsLearned: Int = 0,
)

/** The blind A/B: which side is audible now. Which of A/B is enhanced is hidden in [com.arnavagarwal.sound.core.Challenge]. */
data class ChallengeState(val enhancedAudible: Boolean, val matchGainDb: Double)

/**
 * The loop. About 20 times a second it glides the sound toward the current plan and pushes it to every
 * live effect chain; once a second it re-reads what is playing, re-plans, counts hearing dose and
 * refreshes the advice. All engine work runs on ONE thread ([loop]), so no state here needs a lock.
 */
class Brain(private val ctx: Context) {
    private val prefs = Prefs(ctx)
    private val store = DataStore(ctx)
    private val learner = TasteLearner().apply { load(store.loadTaste()) }
    private val ledger = store.loadDose()
    private val analyzer = Analyzer()
    private val outputs = OutputWatcher(ctx)
    private val volume = VolumeReader(ctx)
    private val calibrator = LevelCalibrator()
    private val alerts = AlertLimiter()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val loop = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + loop)
    private var job: Job? = null
    private var stopJob: Job? = null

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state

    /** Called (on the engine thread) when the person should be told something right now. */
    var onAlert: (String) -> Unit = {}

    // ---- live engine state (engine thread only) ----
    private val chains = HashMap<Int, EffectChain>()
    private var global: EffectChain? = null
    private var globalActive = false
    private var inCall = false
    private var inPrivate = false
    private var plan: EnginePlan? = null
    private var profile: ContentProfile? = null
    private val bands = DEFAULT_BAND_CUTOFFS.size
    private val glide = Glide(bands + 1) // last slot = preamp, so it glides with the curve
    private var pushedBands = DoubleArray(bands) { Double.NaN }
    private var pushedGains = Double.NaN to Double.NaN
    private var pushedDynamics: Dynamics? = null
    /** The plan's limiter switch as last pushed (Strong mode relies on the -1 dBFS limiter staying on). */
    private var pushedLimiter: Boolean? = null
    private var switches: UserSwitches = prefs.switches
    private var correction: Curve? = null
    private var correctionName: String? = null
    private var output: Output = outputs.output.value
    private var lastSongKey: String? = null
    private var knobsSinceMs: Long? = null
    private var dipUntilMs = 0L
    // battery: rest while nothing plays (RestPolicy); the tuning stays in place, only checking and measuring slow down
    private val rest = RestPolicy()
    private val audio = ctx.getSystemService(android.media.AudioManager::class.java)
    @Volatile var resting = false; private set
    private var challenge: ChallengeState? = null
    private var startedMs = 0L
    private var lastSecond = 0L
    private var lastVolumePoll = 0L
    private var lastVolumeIndex = -1
    /** When the volume step last changed: frames before it were measured at the old volume (volume coach). */
    private var volumeChangedMs = 0L
    private val levelHistory = ArrayDeque<Pair<Long, Double>>() // (time, level) for the last few seconds
    private var pendingCalibration: Triple<Long, Double, Double>? = null // (evaluate at, level before, volume dB before)
    private var lastSaveMs = 0L
    private var lastGlobalTry = 0L
    private var clarityBefore: Double? = null
    private var clarityAfter: Double? = null
    private var densityOn = prefs.densityOn
    private val density = DensityControl()
    private var densitySong: String? = "\u0000" // never a real key, so the first song resets
    private var densityCap = 0.0
    private var lastDensityChangeMs = 0L
    private val listen = ListenLevel()
    private var coach = VolumeCoach()
    /** The kind the coach nudges by, held per song (core KindVote). */
    private val coachKind = KindVote()
    private var coachAdvice: VolumeCoach.Advice? = null
    private var lastLevelMs = 0L

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            stopJob?.join() // a quick off-then-on must not have its new effects released by the old stop
            startedMs = System.currentTimeMillis()
            SessionRegistry.restoreSeen(prefs.seenPackages)
            outputs.start()
            onOutputChanged(outputs.output.value)
            // created switched OFF: syncChains decides within a second whether the global mix is ours to process
            global = EffectChain.attach(0)?.also { it.setEnabled(false) }; globalActive = false
            if (hasRecordAudio()) analyzer.start()
            var prev = System.nanoTime()
            while (isActive) {
                val t = System.nanoTime()
                val dt = ((t - prev) / 1e9).coerceIn(0.0, 1.5); prev = t
                runCatching { tick(dt) }.onFailure { android.util.Log.e("Brain", "tick failed", it) }
                delay(if (resting) REST_MS else TICK_MS)
            }
        }
    }

    /** Releases every effect: the phone must sound exactly as before the app once this returns. */
    fun stop() {
        val j = job ?: return
        job = null
        stopJob = scope.launch {
            j.cancel(); j.join()
            chains.values.forEach { it.release() }; chains.clear()
            global?.release(); global = null; globalActive = false
            analyzer.stop()
            runCatching { outputs.stop() }
            saveAll()
            pushedBands = DoubleArray(bands) { Double.NaN }; pushedGains = Double.NaN to Double.NaN; pushedDynamics = null; pushedLimiter = null
            _state.value = _state.value.copy(running = false, globalAttached = false, globalActive = false)
        }
    }

    // ---------------------------------------------------------------- the loop

    private fun tick(dt: Double) {
        val now = System.currentTimeMillis()
        // a call is never heard or shaped (CallGuard, research/legal-check.md): stand down until it ends
        // and private playback (incognito, PrivatePlayback) the same way
        val call = CallGuard.isCall(runCatching { audio.mode }.getOrDefault(android.media.AudioManager.MODE_NORMAL))
        val priv = NowPlayingSource.state.value.isPrivate
        if (call != inCall || priv != inPrivate) {
            val was = inCall || inPrivate
            inCall = call; inPrivate = priv
            if ((call || priv) && !was) { analyzer.stop(); global?.setEnabled(false); globalActive = false }
            else if (!call && !priv && was && !resting && hasRecordAudio()) { analyzer.start(); forceRepush() }
            // published here, not by the usual end-of-tick publish, which a call never reaches
            _state.value = _state.value.copy(inCall = call, analyzerOn = analyzer.running, globalActive = globalActive)
            // private: forget what the screen was showing, so no app name or guess stays up while it plays
            if (priv) _state.value = _state.value.copy(now = null, isPlaying = false, profile = null)
        }
        if (inCall || inPrivate) return
        val soundActive = runCatching { audio.isMusicActive }.getOrDefault(true) || NowPlayingSource.state.value.isPlaying ||
            challenge != null || now < dipUntilMs
        val restNow = rest.update(now, soundActive)
        if (restNow != resting) {
            resting = restNow
            if (restNow) analyzer.stop() else if (hasRecordAudio()) analyzer.start()
        }
        // resting: nothing plays, so there is nothing to plan or glide; the effects stay exactly as they are.
        // Only the wake check above runs (measured E-009: the full once-a-second plan cost ~1% of a core at rest).
        if (resting) return
        if (now - lastVolumePoll >= 250) { lastVolumePoll = now; pollVolume(now) }
        if (now / 1000 != lastSecond) { lastSecond = now / 1000; everySecond(now) }
        val p = plan ?: return

        // target: the plan, or flat/enhanced for the blind challenge
        val ch = challenge
        val targetBands: DoubleArray
        val targetPre: Double
        if (ch != null && !ch.enhancedAudible) { targetBands = DoubleArray(bands); targetPre = 0.0 }
        else if (ch != null) { targetBands = p.bandGainsDb; targetPre = ch.matchGainDb }
        else { targetBands = p.bandGainsDb; targetPre = p.preampDb }
        val target = targetBands + targetPre
        val cur = if (ch != null) { glide.snap(target); target } else glide.step(target, dt)
        val curBands = cur.copyOfRange(0, bands)
        var pre = cur[bands]
        // density: a lift into the -1 dBFS limiter at the end of every chain (never during the blind challenge)
        if (densityLive()) pre += density.liftDb

        if (now < dipUntilMs) pre -= 30.0 // coverage test: the sound dips if (and only if) we process it
        var l = pre; var r = pre
        val eightD = p.allow8D && output.isHeadphones && ch == null
        if (eightD) {
            val (gl, gr) = EightD.gainsDb((now - startedMs) / 1000.0, switches.eightDPeriodSec)
            l += gl; r += gr
        }

        val live = liveChains()
        if (live.isEmpty()) return
        if (differs(curBands, pushedBands)) { live.forEach { it.setBands(curBands) }; pushedBands = curBands }
        if (abs(l - pushedGains.first) > 0.05 || abs(r - pushedGains.second) > 0.05 || pushedGains.first.isNaN()) {
            live.forEach { it.setInputGains(l, r) }; pushedGains = l to r
        }
        val dyn = if (ch != null) Dynamics.OFF else p.dynamics
        if (dyn != pushedDynamics) { live.forEach { it.setDynamics(dyn) }; pushedDynamics = dyn }
        if (p.limiterOn != pushedLimiter) { live.forEach { it.setLimiter(p.limiterOn) }; pushedLimiter = p.limiterOn }
    }

    private fun everySecond(now: Long) {
        syncChains()
        val ps = NowPlayingSource.state.value
        val np = ps.now
        val o = outputs.output.value
        if (o.key != output.key) onOutputChanged(o)

        // song changed: teach the learner what the knobs said, then return the knobs to zero
        val key = np?.songKey
        if (key != lastSongKey) {
            val prof = profile
            val held = knobsSinceMs?.let { (now - it) / 1000.0 } ?: 0.0
            if (switches.learnEnabled && prof != null && KnobLearning.worthLearning(switches.manualTone, held)) {
                val base = learner.predict(prof, lastSongKey).tone
                learner.record(prof, null, base + switches.manualTone, now)
                setSwitchesInternal(switches.copy(manualTone = Tone.ZERO))
                store.saveTaste(learner.all())
            }
            lastSongKey = key
        }

        // classify + plan
        val features = if (analyzer.running) analyzer.features() else null
        val prof = ContentClassifier.classify(np, features)
        profile = prof
        val level = features?.levelDbfs ?: analyzer.lastLevelDbfs.takeIf { analyzer.running && it > -90 }
        val volDbs = runCatching { volume.dbByIndex(o.deviceType) }.getOrNull()
        val idx = volume.index
        // route B: the analyzer hears the mix BEFORE our global effect, so add what our processing adds (E-003)
        val ourGainDb = if (globalActive) (plan?.let { Loudness.changeDb(it.curve, analyzer.averageSpectrum() ?: Spectrum.PINK) + it.preampDb } ?: 0.0) +
            (if (densityLive()) density.liftDb else 0.0) else 0.0
        val spl = if (level != null && volDbs != null) SoundDose.estimateSpl(level + ourGainDb, if (prefs.levelIncludesVolume) 0.0 else volDbs[idx]) else null
        val taste = if (switches.learnEnabled) learner.predict(prof, key) else null
        val newPlan = Mixer.plan(prof, taste, switches, correction, listenPhon = spl ?: volDbs?.let { SoundDose.estimateSpl(-18.0, it[idx]) },
            spectrum = analyzer.averageSpectrum() ?: Spectrum.PINK, speaker = output.kind == Output.Kind.SPEAKER)
        plan = newPlan
        steerDensity(prof, key, volDbs?.getOrNull(idx))
        analyzer.compensationDb = compensationFor(newPlan)

        // hearing: count the dose only through headphones (WHO H.870 is about personal listening devices)
        if (ps.isPlaying && o.isHeadphones && spl != null) ledger.add(now, spl, 1.0)
        val week = ledger.weekFraction(now)
        val advice = if (volDbs != null && volDbs.size >= 2 && o.isHeadphones)
            VolumeAdvisor.advise(prof.top, level ?: -18.0, if (prefs.levelIncludesVolume && level != null) DoubleArray(volDbs.size) { i -> volDbs[i] - volDbs[idx] } else volDbs, idx, week)
        else null
        if (advice != null && ps.isPlaying && alerts.update(now, advice.currentDb > advice.targetDb + 6)) onAlert(advice.message)
        // on earbuds the hearing-safe volume is the ceiling of the coach's advice: safety wins over taste
        coachVolume(now, prof, key, volDbs, idx, advice?.let { volDbs?.getOrNull(it.bestIndex) })

        // voice clarity: the analyzer's spectrum is the sound BEFORE our processing on both routes (E-003), so
        // "after" = that spectrum through the plan. "After" is only claimed while a chain is really processing.
        val spec = if (analyzer.running) analyzer.averageSpectrum() else null
        clarityBefore = spec?.let { VoiceClarity.score(it) }
        clarityAfter = if (spec != null && liveChains().isNotEmpty()) VoiceClarity.score(spec, newPlan.curve) else null

        if (now - lastSaveMs > 60_000) { lastSaveMs = now; saveAll() }
        publish(ps, prof, advice, week, spl)
    }

    // ---------------------------------------------------------------- effect chains

    /** Attach to newly announced sessions, release closed ones, decide whether the global chain is live. */
    private fun syncChains() {
        val announced = SessionRegistry.sessions.value
        for (id in chains.keys - announced.keys) chains.remove(id)?.release()
        for (id in announced.keys - chains.keys) {
            EffectChain.attach(id)?.let { chains[id] = it; forceRepush() }
        }
        val np = NowPlayingSource.state.value.now?.packageName
        val want = SessionPolicy.useGlobal(announced.values.map { it.packageName }, np)
        val g = global
        if (g == null) {
            // attach() switches it on; retried at most every 30 s on phones that refuse session 0
            if (want && System.currentTimeMillis() - lastGlobalTry > 30_000) {
                lastGlobalTry = System.currentTimeMillis()
                global = EffectChain.attach(0)?.also { globalActive = true; forceRepush() }
            }
        } else if (want != globalActive) {
            g.setEnabled(want); globalActive = want
            if (want) forceRepush()
        }
    }

    /**
     * What the analyzer's sound has already been through. Only announced-session chains sit before the
     * output mix the analyzer reads; with the global chain live, the analyzer hears the unprocessed mix.
     */
    private fun compensationFor(p: EnginePlan): DoubleArray? {
        if (globalActive || chains.isEmpty()) return null
        val ch = challenge
        if (ch != null && !ch.enhancedAudible) return null
        val pre = (ch?.matchGainDb ?: p.preampDb) + if (densityLive()) density.liftDb else 0.0
        return DoubleArray(p.curve.db.size) { p.curve.db[it] + pre }
    }

    // ---------------------------------------------------------------- density

    /** Phone speaker only (as on the laptop), a chain really processing, and never while the blind challenge plays. */
    private fun densityLive() = densityOn && output.kind == Output.Kind.SPEAKER && challenge == null && liveChains().isNotEmpty()

    /**
     * Density's amount for this song (core DensityControl, as desktop Engine.steerDensity): capped by the kind of
     * content, steered by how often the phone's limiter is predicted to hold the peaks ([DensityControl.phoneShare]).
     * Global route: the Visualizer hears BEFORE our effect (E-003), so the lift is added to the peaks by arithmetic.
     * Per-app route: it hears after our chain, so the peaks already carry the lift, and frames made with an older
     * lift are skipped. The volume is taken back out of the peaks when the calibrator says the Visualizer includes
     * it, so turning the volume down never makes the app add density to make up for it.
     */
    private fun steerDensity(prof: ContentProfile, songKey: String?, volumeDb: Double?) {
        val kind = prof.top.takeIf { prof.confidence >= KIND_SURE }
        if (songKey != densitySong) { densitySong = songKey; density.reset(DensityControl.cap(kind, switches.intensity) * DensityControl.UNSURE_FLOOR) }
        val unvolume = if (prefs.levelIncludesVolume && volumeDb != null) -volumeDb else 0.0
        // one vote a second, only while something sounds
        if (DensityControl.ceilingShare(analyzer.peaks()) != null) density.vote(kind != null)
        densityCap = density.songCap(kind, switches.intensity)
        val before = density.liftDb
        val share = if (!densityLive()) null
            else if (globalActive) DensityControl.phoneShare(analyzer.peaks(), density.liftDb + unvolume)
            else DensityControl.phoneShare(analyzer.peaks(lastDensityChangeMs + RELOAD_MS), unvolume - (plan?.strongLouderDb ?: 0.0))
        density.update(share, densityCap)
        if (density.liftDb != before) lastDensityChangeMs = System.currentTimeMillis()
        if (density.liftDb != before) android.util.Log.i("Density", "lift=%.2f share=%.3f cap=%.2f unsure=%.2f".format(density.liftDb, share, densityCap, density.unsureShare()))
    }

    // ---------------------------------------------------------------- volume coach

    /**
     * The laptop's volume coach (desktop Engine.levelWork), per output: learns where he leaves songs (song level before
     * the volume + the volume), then says whether THIS song wants the volume up or down. No mic.
     */
    private fun coachVolume(now: Long, prof: ContentProfile, songKey: String?, dbs: DoubleArray?, idx: Int, safeMaxDb: Double?) {
        if (dbs == null || dbs.size < 2 || !analyzer.running) { coachAdvice = null; return }
        val slider = dbs[idx]
        val muted = idx == 0
        val incl = prefs.levelIncludesVolume
        listen.update(now, songKey, slider, muted, VolumeCoach.beforeVolume(analyzer.levels(maxOf(lastLevelMs, volumeChangedMs + VOLUME_SETTLE_MS)), slider, incl))?.let {
            coach.learn(it); prefs.setListenLevels(output.key, coach.saved)
        }
        lastLevelMs = now
        val sounding = VolumeCoach.beforeVolume(analyzer.levels(volumeChangedMs + VOLUME_SETTLE_MS), slider, incl).filter { it > ListenLevel.SILENT_DB }
        val songLevel = if (sounding.size < 40) null else 10 * kotlin.math.log10(sounding.sumOf { Math.pow(10.0, it / 10) } / sounding.size)
        val kind = coachKind.update(songKey, prof.top.takeIf { prof.confidence >= KIND_SURE })
        val maxDb = minOf(dbs.last(), safeMaxDb ?: dbs.last())
        val before = coachAdvice?.verdict
        coachAdvice = if (muted || songLevel == null) null else coach.adviseSteps(songLevel, dbs, idx, kind, maxDb)
        coachAdvice?.let { a -> if (a.verdict != before) android.util.Log.i("Volume", "%s step=%d at=%.1f to=%.1f want=%.1f song=%.1f usual=%.1f kind=%s".format(a.verdict, idx, slider, a.wantSliderDb,
            coach.advise(songLevel!!, slider, kind, dbs[1], maxDb)?.wantSliderDb, songLevel, coach.usualDb, kind)) }
    }

    /** His tap on "Set it for me": the only way the app moves his volume (as on the laptop). */
    fun useVolumeAdvice() = scope.launch {
        val a = coachAdvice ?: return@launch
        val dbs = runCatching { volume.dbByIndex(output.deviceType) }.getOrNull() ?: return@launch
        volume.set(VolumeCoach.nearestStep(dbs, a.wantSliderDb))
    }

    fun setDensity(on: Boolean) = scope.launch { densityOn = on; prefs.densityOn = on; lastSecond = 0 }

    private fun liveChains(): List<EffectChain> = buildList {
        addAll(chains.values)
        if (globalActive) global?.let { add(it) }
    }

    private fun forceRepush() { pushedBands = DoubleArray(bands) { Double.NaN }; pushedGains = Double.NaN to Double.NaN; pushedDynamics = null; pushedLimiter = null }

    private fun differs(a: DoubleArray, b: DoubleArray) = a.indices.any { b[it].isNaN() || abs(a[it] - b[it]) > 0.05 }

    // ---------------------------------------------------------------- volume + calibration

    private fun pollVolume(now: Long) {
        val level = analyzer.lastLevelDbfs
        if (analyzer.running) {
            levelHistory.addLast(now to level)
            while (levelHistory.isNotEmpty() && now - levelHistory.first().first > 4_000) levelHistory.removeFirst()
        }
        val idx = volume.index
        val dbs = runCatching { volume.dbByIndex(output.deviceType) }.getOrNull()
        if (lastVolumeIndex >= 0 && idx != lastVolumeIndex && abs(idx - lastVolumeIndex) >= 2 && dbs != null && analyzer.running) {
            val before = levelHistory.filter { now - it.first in 250..1_250 }.map { it.second }
            if (before.size >= 2) pendingCalibration = Triple(now + 1_500, before.average(), dbs[lastVolumeIndex])
        }
        pendingCalibration?.let { (at, lvBefore, volBefore) ->
            if (now >= at && dbs != null) {
                val after = levelHistory.filter { now - it.first <= 1_000 }.map { it.second }
                if (after.size >= 2 && calibrator.observe(lvBefore, after.average(), volBefore, dbs[idx])) {
                    calibrator.verdict?.let { prefs.levelIncludesVolume = it }
                }
                pendingCalibration = null
            }
        }
        if (idx != lastVolumeIndex) volumeChangedMs = now
        lastVolumeIndex = idx
    }

    // ---------------------------------------------------------------- output device

    private fun onOutputChanged(o: Output) {
        output = o
        coach = VolumeCoach(prefs.listenLevels(o.key)); coachAdvice = null
        val prof = store.loadCorrection(o.key)
        correction = prof?.curve(); correctionName = prof?.name
        forceRepush()
    }

    // ---------------------------------------------------------------- commands from the screen

    fun setSwitches(s: UserSwitches) = scope.launch { setSwitchesInternal(s) }

    private fun setSwitchesInternal(s: UserSwitches) {
        if (s.manualTone != switches.manualTone) knobsSinceMs = if (s.manualTone == Tone.ZERO) null else (knobsSinceMs ?: System.currentTimeMillis())
        switches = s; prefs.switches = s
        lastSecond = 0 // re-plan on the next tick
    }

    /** "Save for this song": the current knobs, on top of what was already learned, become this song's sound. */
    fun saveForThisSong() = scope.launch {
        val prof = profile ?: return@launch
        val key = NowPlayingSource.state.value.now?.songKey ?: return@launch
        val base = learner.predict(prof, key).tone
        learner.record(prof, key, base + switches.manualTone, System.currentTimeMillis())
        store.saveTaste(learner.all())
        setSwitchesInternal(switches.copy(manualTone = Tone.ZERO))
    }

    fun forgetThisSong() = scope.launch {
        val key = NowPlayingSource.state.value.now?.songKey ?: return@launch
        learner.load(learner.all().filter { it.songKey != key })
        store.saveTaste(learner.all()); lastSecond = 0
    }

    /** The coverage check: every chain we are driving dips for one second. If the person hears it, it works in that app. */
    fun coverageDip() = scope.launch { dipUntilMs = System.currentTimeMillis() + 1_000 }

    fun importCorrection(name: String, text: String): String? {
        val parsed = com.arnavagarwal.sound.core.AutoEqProfile.parse(name, text)
        val p = parsed.profile ?: return parsed.problems.firstOrNull() ?: "Could not read that file."
        store.saveCorrection(output.key, name, text)
        scope.launch { onOutputChanged(output); lastSecond = 0 }
        return if (p.filters.isEmpty()) "No filters found in that file." else null
    }

    fun removeCorrection() = scope.launch { store.removeCorrection(output.key); onOutputChanged(output); lastSecond = 0 }

    /** Blind challenge: show A or B. Loudness-matched on THIS song's spectrum, so louder cannot win. */
    fun challengePlay(enhanced: Boolean) = scope.launch {
        val p = plan ?: return@launch
        val spec: Spectrum = analyzer.averageSpectrum() ?: Spectrum.PINK
        // on the speaker, both sides are matched on what the speaker can play (Speaker.response)
        challenge = ChallengeState(enhanced, Loudness.matchGainDb(p.curve, if (p.speaker) spec.through(Speaker.response) else spec))
        forceRepush()
    }

    fun challengeEnd() = scope.launch { challenge = null; forceRepush() }

    fun setVolumeIndex(i: Int) = volume.set(i)

    // ---------------------------------------------------------------- state

    private fun publish(ps: PlayingState, prof: ContentProfile, advice: VolumeAdvisor.Advice?, week: Double, spl: Double?) {
        val seen = SessionRegistry.seenPackages.value
        if (seen != prefs.seenPackages) prefs.seenPackages = seen
        val p = plan
        _state.value = EngineState(
            running = true, now = ps.now, isPlaying = ps.isPlaying, profile = prof,
            explanation = p?.explanation.orEmpty(), bandsDb = glide.current.copyOfRange(0, bands), preampDb = glide.current[bands],
            output = output, advice = advice, weekFraction = week, estimatedSplDb = spl,
            globalAttached = global != null, globalActive = globalActive,
            announced = SessionRegistry.sessions.value, seenPackages = seen,
            analyzerOn = analyzer.running, hasNowPlayingAccess = NowPlayingSource.hasAccess(ctx),
            eightDActive = p?.allow8D == true && output.isHeadphones, correctionName = correctionName,
            challenge = challenge, knobsHeldSec = knobsSinceMs?.let { (System.currentTimeMillis() - it) / 1000.0 } ?: 0.0,
            tasteSamples = learner.size,
            clarityBefore = clarityBefore, clarityAfter = clarityAfter,
            appTone = p?.appTone ?: Tone.ZERO,
            densityOn = densityOn, densityLive = densityLive(), densityDb = density.liftDb, densityCapDb = densityCap,
            volumeAdvice = coachAdvice, songsLearned = coach.saved.size,
        )
    }

    val currentSwitches: UserSwitches get() = switches

    private fun saveAll() { runCatching { store.saveDose(ledger); store.saveTaste(learner.all()) } }

    private fun hasRecordAudio() = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Call after the person grants RECORD_AUDIO while the engine is already running. */
    fun permissionsChanged() = scope.launch { if (!analyzer.running && hasRecordAudio() && job?.isActive == true) analyzer.start() }

    companion object {
        const val TICK_MS = 50L; const val REST_MS = 1_000L
        /** The classifier's confidence above which the kind's own density cap applies (desktop Engine.KIND_SURE). */
        const val KIND_SURE = 0.5
        /** Per-app route: frames this soon after a lift change may still carry the old lift. */
        const val RELOAD_MS = 500L
        /** Frames this soon after a volume step may still be at the old volume. */
        const val VOLUME_SETTLE_MS = 300L
    }
}
