package com.arnavagarwal.sound.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.arnavagarwal.sound.SoundApp
import com.arnavagarwal.sound.core.Challenge
import com.arnavagarwal.sound.core.Fine
import com.arnavagarwal.sound.core.Strength
import com.arnavagarwal.sound.core.Tone
import com.arnavagarwal.sound.core.UserSwitches
import com.arnavagarwal.sound.engine.Brain
import com.arnavagarwal.sound.engine.DemoSong
import com.arnavagarwal.sound.engine.EngineService
import com.arnavagarwal.sound.engine.EngineState
import com.arnavagarwal.sound.engine.NowPlayingSource
import com.arnavagarwal.sound.engine.Output
import com.arnavagarwal.sound.engine.Prefs
import kotlinx.coroutines.delay

/**
 * The "Aurora" screen (his pick, 22 Sep 2026). Three rules shape it, all in RULES.md:
 * every button says in plain words what it does; the UI stays simple (extras live on the settings page);
 * and no brand list - only the app playing NOW is named, so it never reads as a one-app tool.
 * All engine work stays in [Brain]; this file only shows state and sends commands.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val brain = (application as SoundApp).brain
        // test builds only: pretend to be another maker's phone, to photograph the Vivo battery picture on the emulator
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) intent?.getStringExtra("fake_maker")?.let { makerOverride = it }
        setContent { AuroraTheme { Root(brain) } }
    }

    // the demo song never keeps looping once the app is out of sight
    override fun onStop() { super.onStop(); DemoSong.stop() }
}

/** Set only in a debuggable build, from the "fake_maker" launch extra. */
private var makerOverride: String? = null

/** The runtime permission the tuning cannot work without; the optional ones are asked by their own home cards. */
private val runtimePermissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)


private enum class Page { HOME, SETTINGS, TEST }

// ------------------------------------------------------------------ permissions, shared by the setup and the home card

private fun permissionFor(step: Setup.Step): String? = when (step) {
    Setup.Step.MEASURE -> Manifest.permission.RECORD_AUDIO
    Setup.Step.EARBUDS -> Manifest.permission.BLUETOOTH_CONNECT
    Setup.Step.WARN -> Manifest.permission.POST_NOTIFICATIONS
    else -> null
}

private fun Context.granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

private fun haveNow(ctx: Context) = Setup.Have(
    see = NowPlayingSource.hasAccess(ctx),
    measure = ctx.granted(Manifest.permission.RECORD_AUDIO),
    earbuds = Build.VERSION.SDK_INT < 31 || ctx.granted(Manifest.permission.BLUETOOTH_CONNECT),
    warn = Build.VERSION.SDK_INT < 33 || ctx.granted(Manifest.permission.POST_NOTIFICATIONS),
    background = ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true,
)

/** Asked before, still refused, and Android will not show its dialog again: only the app's settings page is left. */
private fun blocked(ctx: Context, prefs: Prefs, p: String): Boolean {
    val act = ctx as? Activity ?: return false
    return prefs.asked(p) && !ctx.granted(p) && !act.shouldShowRequestPermissionRationale(p)
}

private fun openAppInfo(ctx: Context) = runCatching {
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
}

/** Straight to this app's own switch where Android allows it (11+), else the list of apps. */
private fun openListenerSetting(ctx: Context) {
    val direct = if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(ctx, "com.arnavagarwal.sound.engine.MediaListenerService").flattenToString()) else null
    if (direct == null || runCatching { ctx.startActivity(direct) }.isFailure)
        runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
}

/** One box with "Allow" (his test, 22 Sep: the list of every app was confusing); else the list; else App info. */
@android.annotation.SuppressLint("BatteryLife")
private fun openBatterySetting(ctx: Context) {
    // Play build: the permission is removed from its manifest (src/play), so go straight to the list (31 Oct 2026)
    if (com.arnavagarwal.sound.BuildConfig.PLAY_STORE) {
        if (runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }.isFailure) openAppInfo(ctx)
        return
    }
    val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
    if (runCatching { ctx.startActivity(ask) }.isSuccess) return
    if (runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }.isFailure) openAppInfo(ctx)
}

/** The phone's own Battery page, for the second switch some makers add. */
private fun openBatteryPage(ctx: Context) {
    if (runCatching { ctx.startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }.isFailure)
        runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS)) }
}

// ------------------------------------------------------------------ first-run setup

/**
 * The ONE Allow button (his words, 30 Sep: "make the allow button as easy as possible ... make it at once he need not
 * commit again and again"). One tap runs Setup.chain to the end by itself: Android's boxes all together, then the
 * battery box, then the notification-access page. Each step is tried once per tap, so a "Don't allow" never loops,
 * and an ask Android has stopped showing (two refusals) is skipped here and left to App info.
 */
@Composable
private fun rememberAllowAll(prefs: Prefs, done: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val sdk = Build.VERSION.SDK_INT
    val tried = remember { mutableSetOf<Setup.Step>() }
    var waiting by remember { mutableStateOf<Setup.Step?>(null) } // a system page the person was sent to
    var resumed by remember { mutableIntStateOf(0) }
    var sentAt by remember { mutableIntStateOf(0) }
    val holder = remember { arrayOf<() -> Unit>({}) }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { holder[0]() }

    fun step() {
        val left = Setup.chain(haveNow(ctx), sdk).filter { it !in tried }
        val perms = left.mapNotNull { s -> permissionFor(s)?.takeIf { !blocked(ctx, prefs, it) }?.let { s to it } }
        left.filter { permissionFor(it) != null }.forEach { tried += it }
        if (perms.isNotEmpty()) {
            perms.forEach { prefs.markAsked(it.second) }
            runtime.launch(perms.map { it.second }.toTypedArray()); return
        }
        val page = left.firstOrNull { permissionFor(it) == null }
        if (page != null) {
            tried += page; waiting = page; sentAt = resumed
            if (page == Setup.Step.BACKGROUND) openBatterySetting(ctx) else openListenerSetting(ctx)
            return
        }
        waiting = null; tried.clear(); done()
    }
    holder[0] = ::step

    LifecycleResumeEffect(Unit) { resumed++; onPauseOrDispose { } }
    // carry on once the page's switch is on; if the person came back without it, carry on after a short grace
    // (Android can save the switch a moment after the return - seen on the emulator)
    LaunchedEffect(waiting) {
        val w = waiting ?: return@LaunchedEffect
        var grace = 0
        var total = 0
        while (true) {
            delay(500); total += 500
            if (Setup.granted(w, haveNow(ctx))) {
                // switched on and still on Android's page: come back by ourselves, no "back" to find (his ask, 30 Sep)
                if (resumed <= sentAt) runCatching {
                    ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
                break
            }
            if (resumed > sentAt) { grace += 500; if (grace >= 2_000) break }
            if (total >= 120_000) break
        }
        waiting = null; step()
    }
    return { tried.clear(); step() }
}

/** Big promise, then the plain way out, right above an Allow button (his ask, 30 Sep). */
@Composable
private fun PrivacyPromise() {
    val a = LocalAurora.current
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(Setup.PRIVACY_BIG, color = a.ink, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, lineHeight = 36.sp, letterSpacing = (-0.5).sp)
        Text(Setup.PRIVACY_BODY, color = a.ink, fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp)
        Hint(Setup.PRIVACY_SMALL)
    }
}

/** A big green tick and the three safety facts, in a softly green-edged box (his ask, 8 Oct). */
@Composable
private fun SafetyBadge() {
    val a = LocalAurora.current
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(18.dp))
            .background(a.good.copy(alpha = .10f)).border(1.dp, a.good.copy(alpha = .55f), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(a.good), contentAlignment = Alignment.Center) {
            Text("✓", color = Color.Black, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(Setup.SAFE_TITLE, color = a.good, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            for (l in Setup.SAFE_LINES) Text(l, color = a.ink, fontSize = 13.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun SetupFlow(prefs: Prefs, finish: () -> Unit) {
    val a = LocalAurora.current
    val ctx = LocalContext.current
    val sdk = Build.VERSION.SDK_INT
    var step by remember { mutableStateOf(Setup.Step.WELCOME) }
    var have by remember { mutableStateOf(haveNow(ctx)) }
    val allowAll = rememberAllowAll(prefs) { have = haveNow(ctx); step = Setup.Step.DONE }
    // the battery box answers a moment before Android records it: read again on return, or "Done" calls it skipped (7 Oct 2026)
    LifecycleResumeEffect(Unit) { have = haveNow(ctx); onPauseOrDispose { } }
    fun fromWelcome() { have = haveNow(ctx); step = if (Setup.chain(have, sdk).isEmpty()) Setup.Step.DONE else Setup.Step.SEE }

    // three pages: welcome, the one Allow page (drawn from Setup.ALL, kept on the SEE slot), done
    val asking = step == Setup.Step.SEE
    val lean = when (step) {
        Setup.Step.SEE -> Words.Parts(-5.0, 6.0, -5.0)
        Setup.Step.DONE -> Words.Parts(3.0, 3.0, 3.0)
        else -> Words.Parts(0.0, 0.0, 0.0)
    }
    val p = if (asking) Setup.ALL else Setup.page(step)

    Box(Modifier.fillMaxSize().background(a.bg)) {
        AuroraCloud(lean, active = true, Modifier.fillMaxWidth().height(460.dp).padding(top = 40.dp))
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(" ", color = a.dim, fontSize = 13.sp)
            Spacer(Modifier.height(if (asking) 20.dp else 250.dp)) // the Allow page is long: keep its button on the first screen
            Text(p.title, color = a.ink, fontSize = 38.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp, lineHeight = 42.sp)
            Text(p.body, color = a.ink.copy(alpha = .8f), fontSize = 16.sp, lineHeight = 23.sp)
            if (p.promises.isNotEmpty()) Ticks(p.promises)

            if (step == Setup.Step.DONE) {
                val lost = Setup.chain(have, sdk).mapNotNull { Setup.missingSays(it) }
                if (lost.isNotEmpty()) Card {
                    Label("Skipped for now (one tap on the home screen allows them)")
                    lost.forEach { Hint(it) }
                }
            }

            if (asking) PrivacyPromise()
            Primary(p.allow) {
                when (step) {
                    Setup.Step.WELCOME -> fromWelcome()
                    Setup.Step.DONE -> finish()
                    else -> allowAll()
                }
            }
            if (asking) {
                p.help?.let { Hint(it) }
                Secondary(Setup.OPEN_APP_SETTINGS, Modifier.fillMaxWidth()) { openAppInfo(ctx) }
                Quiet(Setup.NOT_NOW) { have = haveNow(ctx); step = Setup.Step.DONE }
            }
        }
    }
}

@Composable
private fun Root(brain: Brain) {
    val a = LocalAurora.current
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val s by brain.state.collectAsState()
    var page by remember { mutableStateOf(Page.HOME) }
    var engineOn by remember { mutableStateOf(prefs.engineOn) }
    var sw by remember { mutableStateOf(brain.currentSwitches) }
    var missing by remember { mutableStateOf(listOf<String>()) }
    var listenerOk by remember { mutableStateOf(true) }
    var needs by remember { mutableStateOf(listOf<Setup.Step>()) }
    var setupDone by remember { mutableStateOf(prefs.onboarded) }
    var demoNow by remember { mutableStateOf(false) } // the home demo button starts the demo at once: one tap, not two

    fun refreshPermissions() {
        missing = runtimePermissions.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        listenerOk = NowPlayingSource.hasAccess(ctx)
        needs = Setup.chain(haveNow(ctx), Build.VERSION.SDK_INT)
    }
    LifecycleResumeEffect(Unit) { refreshPermissions(); brain.permissionsChanged(); onPauseOrDispose { } }
    val allowAll = rememberAllowAll(prefs) {
        refreshPermissions(); brain.permissionsChanged()
        // after two refusals Android shows no box; the switch is still in App info
        if (runtimePermissions.any { blocked(ctx, prefs, it) }) openAppInfo(ctx)
    }
    LaunchedEffect(engineOn) { if (engineOn) EngineService.start(ctx) }
    // the notification's Turn off / Turn on writes the same setting: follow it, so the screen and the shade always agree
    DisposableEffect(prefs) { engineOn = prefs.engineOn; val undo = prefs.watchEngine { engineOn = it }; onDispose { undo() } }
    // the sliders go back to zero after "keep" or a song change: follow the engine
    LaunchedEffect(s.tasteSamples) { sw = brain.currentSwitches }

    fun update(n: UserSwitches) { sw = n; brain.setSwitches(n) }
    fun setEngine(on: Boolean) {
        engineOn = on; prefs.engineOn = on
        if (on) EngineService.start(ctx) else EngineService.stop(ctx)
    }

    if (!setupDone) {
        SetupFlow(prefs) { prefs.onboarded = true; setupDone = true; setEngine(true); refreshPermissions(); brain.permissionsChanged() }
        return
    }

    val playing = engineOn && s.isPlaying && s.now != null
    val parts = Words.parts(s.bandsDb, speaker = s.output.kind == Output.Kind.SPEAKER)

    Box(Modifier.fillMaxSize().background(a.bg)) {
        if (page == Page.HOME) AuroraCloud(parts, playing, Modifier.fillMaxWidth().height(520.dp).padding(top = 90.dp))
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (page) {
                Page.HOME -> Home(s, sw, engineOn, playing, parts, missing, listenerOk, brain,
                    ::setEngine, ::update,
                    needs = needs, allowAll = allowAll,
                    openSettings = { page = Page.SETTINGS }, openTest = { demoNow = false; page = Page.TEST },
                    openDemo = { demoNow = true; page = Page.TEST })
                Page.SETTINGS -> SettingsPage(s, sw, brain, ::update) { page = Page.HOME }
                Page.TEST -> TestPage(s, sw, brain, ::update, startDemo = demoNow) { page = Page.HOME }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    BackHandler(page != Page.HOME) { if (page == Page.TEST) brain.challengeEnd(); page = Page.HOME }
}

// ------------------------------------------------------------------ home

@Composable
private fun Home(
    s: EngineState, sw: UserSwitches, engineOn: Boolean, playing: Boolean, parts: Words.Parts,
    missing: List<String>, listenerOk: Boolean, brain: Brain,
    setEngine: (Boolean) -> Unit, update: (UserSwitches) -> Unit,
    openSettings: () -> Unit, openTest: () -> Unit, openDemo: () -> Unit,
    needs: List<Setup.Step> = emptyList(), allowAll: () -> Unit = {},
) {
    val a = LocalAurora.current

    // top row: on/off in words, and where the sound is going
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(if (engineOn) a.good else a.dim)
        Text(if (engineOn) "  Magic is on" else "  Magic is off", Modifier.weight(1f), color = a.ink, fontWeight = FontWeight.Medium)
        Pill(s.output.name)
    }

    // everything still to allow, in one card with one button (his ask, 30 Sep: "make it at once")
    if (needs.isNotEmpty()) Card {
        Text("One tap to finish setting up", color = a.ink, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        needs.mapNotNull { Setup.missingSays(it) }.forEach { Hint(it) }
        Ticks(Setup.SEE_PROMISES + Setup.MEASURE_PROMISES)
        PrivacyPromise()
        Primary("Allow", onClick = allowAll)
    }

    // the green tick, always on the home screen (his ask, 8 Oct); the setup card above already carries the promise
    if (needs.isEmpty()) SafetyBadge()

    // what is playing
    val np = s.now
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Art(np?.title)
        Column(Modifier.padding(start = 12.dp)) {
            Text(if (engineOn && np?.title != null) np.title!! else "Nothing playing", color = a.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            val sub = listOfNotNull(np?.artist, Words.app(np?.packageName)).joinToString(" · ")
            if (engineOn && sub.isNotEmpty()) Text(sub, color = a.dim, fontSize = 14.sp, maxLines = 1)
        }
    }

    // the big middle: what kind of thing it is (only while something really plays), and what we do to it
    Column(Modifier.fillMaxWidth().heightIn(min = 250.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val prof = s.profile
        if (playing && prof != null) Text("${Words.kind(prof.top)} · ${Math.round(prof.confidence * 100)}% sure".uppercase(),
            color = a.ink.copy(alpha = .8f), fontSize = 12.sp, letterSpacing = 1.6.sp)
        Text(if (engineOn) "Your sound" else "Paused", color = a.ink, fontSize = 52.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp,
            modifier = Modifier.padding(top = 6.dp))
        Text(Words.summary(parts, engineOn, playing), color = a.ink.copy(alpha = .85f), fontSize = 15.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, start = 30.dp, end = 30.dp))
    }

    // the laptop's one big button (his note after 0.1.4: "the button to turn tuning on and off is not clear");
    // was a small "Turn tuning off" at the very bottom
    BigSwitch(engineOn) { setEngine(!engineOn) }
    if (!engineOn) return

    // how clearly voices come through, before and with the app (his ask, 22 Sep); only with a real measurement
    val before = s.clarityBefore
    if (playing && before != null) ClarityBar(before, s.clarityAfter)

    // Strong mode (his tap 9 Oct 2026 "Give phone a strong mode"): the laptop's bigger, louder setting, opt-in,
    // OFF by default; three steps he can try by ear, shown only while it is on
    Card {
        Toggle("Strong mode", Words.ABOUT_STRONG, sw.strongOn) { update(sw.copy(strongOn = it)) }
        if (sw.strongOn) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (step in com.arnavagarwal.sound.core.StrongMode.steps)
                Secondary(Words.strongStep(step), Modifier.weight(1f), selected = step == sw.strongStep) { update(sw.copy(strongStep = step)) }
        }
        Hint(Words.strong(sw.strongOn, sw.strongStep, s.output.kind == Output.Kind.SPEAKER))
    }

    // density (laptop feature, 29 Sep "All possible"): what it does always shown, then what it is doing now
    Card {
        Toggle("Density", Words.ABOUT_DENSITY, s.densityOn) { brain.setDensity(it) }
        // "for this song" only while a song plays (seen 29 Sep: "+0.8 dB for this song" beside "Nothing is playing")
        Hint(Words.density(s.densityOn, s.densityLive && playing, s.output.kind == Output.Kind.SPEAKER, s.densityDb, s.densityCapDb))
    }

    // the laptop's volume coach (29 Sep, "All possible"): what it does, what it says for this song, and the one button
    Card {
        Text("Volume", color = a.ink, fontWeight = FontWeight.SemiBold)
        Hint(Words.ABOUT_VOLUME)
        Text(Words.volume(s.volumeAdvice, s.songsLearned, playing), color = a.ink, fontSize = 14.sp, lineHeight = 19.sp,
            modifier = Modifier.padding(top = 6.dp))
        if (s.output.isHeadphones) Hint(Words.VOLUME_EARBUDS)
        val va = s.volumeAdvice
        if (playing && va != null && va.verdict != com.arnavagarwal.sound.core.VolumeCoach.Verdict.GOOD)
            Small(Words.VOLUME_BUTTON) { brain.useVolumeAdvice() }
    }

    // quiet listening (laptop's card, 29 Sep "same to same"; OFF by default, his tap): was "Full sound at low volume"
    Card {
        Toggle("Quiet listening", Words.ABOUT_QUIET, sw.loudnessCompEnabled) { update(sw.copy(loudnessCompEnabled = it)) }
        Hint(Words.quiet(sw.loudnessCompEnabled))
    }

    // study music (laptop feature, 29 Sep "All possible" + "same to same"): the laptop's four steps, two to a row
    Card {
        Text("Study music", color = a.ink, fontSize = 16.sp)
        Hint(Words.ABOUT_STUDY)
        val steps = com.arnavagarwal.sound.core.StudyMusic.STEPS
        val chosen = steps.minBy { kotlin.math.abs(it.second - sw.voiceDownDb) }.second
        for (pair in steps.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, db) in pair)
                Secondary(label, Modifier.weight(1f), selected = db == chosen) { update(sw.copy(voiceDownDb = db)) }
        }
        Hint(Words.study(sw.voiceDownDb))
    }

    // how strong (laptop's card, 29 Sep "same to same"): was on the Settings page, now where the laptop has it
    Card {
        Text("How strong", color = a.ink, fontWeight = FontWeight.SemiBold)
        Hint("How much the app changes the sound.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val chosen = Strength.nearest(sw.intensity)
            for (level in Strength.levels)
                Secondary(Words.strength(level), Modifier.weight(1f), selected = level == chosen) { update(sw.copy(intensity = level)) }
        }
    }

    // change it yourself: only while something plays - with nothing playing there is nothing to change (keeps it simple)
    if (playing) Card {
        Text("Change it yourself", color = a.ink, fontWeight = FontWeight.SemiBold)
        // the sliders START where the app put them, so it is visible that the app is tuning (his test, 22 Sep:
        // "since it sits at zero i don't think soo does the optimization"); moving them is optional
        Hint(Words.SLIDERS_HINT)
        val t = sw.manualTone; val app = s.appTone
        Knob("Bass", "The deep boom: drums, bass guitar.", t.bass, a.bass, app.bass) { update(sw.copy(manualTone = t.copy(bass = it))) }
        Knob("Warmth", Words.ABOUT_WARMTH, t.warmth, a.ink, app.warmth) { update(sw.copy(manualTone = t.copy(warmth = it))) }
        Knob("Clarity", "Voices and words.", t.clarity, a.voice, app.clarity) { update(sw.copy(manualTone = t.copy(clarity = it))) }
        Knob("Treble", "The shine: cymbals, \"s\" sounds.", t.treble, a.treble, app.treble) { update(sw.copy(manualTone = t.copy(treble = it))) }
        if (t != Tone.ZERO) Quiet("Back to the app's setting") { update(sw.copy(manualTone = Tone.ZERO)) }
    }

    // the drop-down: shown while playing, and whenever one of them is moved (they stay on when nothing plays)
    if (playing || sw.manualFine != Fine.ZERO) MoreControls(sw, update)

    // nothing playing: the challenge still works, on the app's own free song (his ask, 22 Sep 22:03)
    if (!playing) Card {
        Text("Hear the difference", color = a.ink, fontWeight = FontWeight.SemiBold)
        Hint(Words.DEMO_HOME_HINT)
        Primary(Words.DEMO_BUTTON, onClick = openDemo)
    }

    val hasSong = s.now?.songKey != null
    if (playing) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Primary("Keep this sound for this song", Modifier.weight(1f), enabled = hasSong) { brain.saveForThisSong() }
        Secondary("Hear the difference", Modifier.weight(1f), onClick = openTest)
    }

    // 8D copy (laptop card, 29 Sep "same to same"): a song file -> "<song> - 8D.wav" in Music (engine EightDMaker)
    Card {
        val ctx = LocalContext.current
        val e by com.arnavagarwal.sound.engine.EightDMaker.state.collectAsState()
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) com.arnavagarwal.sound.engine.EightDMaker.start(ctx, uri)
        }
        Text("8D copy", color = a.ink, fontSize = 16.sp)
        Hint(Words.eightD(e, android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q))
        val working = e is com.arnavagarwal.sound.engine.EightDMaker.State.Working
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!working) Secondary(Words.EIGHTD_PICK, Modifier.weight(1f)) { pick.launch(arrayOf("audio/*")) }
            val done = e as? com.arnavagarwal.sound.engine.EightDMaker.State.Done
            if (done != null) Secondary(Words.EIGHTD_PLAY, Modifier.weight(1f)) {
                runCatching {
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(done.uri, "audio/wav")
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            }
        }
    }

    // hearing safety on earbuds + ears
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val adv = s.advice
        Card(Modifier.weight(1f)) {
            Label("Safe volume")
            if (adv == null) Hint(if (s.output.isHeadphones) "Play something to get advice." else "Plug in earbuds or headphones for advice.")
            else {
                Big("${adv.bestPercent}%")
                Hint("You are at about ${Math.round(adv.currentDb)} dB now.")
                Small("Set volume to ${adv.bestPercent}%") { brain.setVolumeIndex(adv.bestIndex) }
            }
        }
        Card(Modifier.weight(1f)) {
            Label("Ears this week")
            Big("${Math.round(s.weekFraction * 100)}%")
            Bar(s.weekFraction.toFloat())
            Hint("of the safe amount for one week")
        }
    }

    // does it work in the app that is playing? one app, named only because it is the one playing
    Coverage(s, brain)

    Secondary("More settings", Modifier.fillMaxWidth(), onClick = openSettings)
}

@Composable
private fun BigSwitch(on: Boolean, toggle: () -> Unit) {
    val a = LocalAurora.current
    val shape = RoundedCornerShape(28.dp)
    val fill = if (on) Brush.horizontalGradient(listOf(a.bass, a.voice, a.treble)) else Brush.horizontalGradient(listOf(a.glass, a.glass))
    Column(
        Modifier.fillMaxWidth().clip(shape).background(fill).border(1.dp, a.edge, shape).clickable(onClick = toggle).padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val ink = if (on) Color(0xFF0B0C12) else a.ink
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Wand(on, ink)
            Column {
                Text(Words.switchTitle(on), color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(Words.switchAction(on), color = ink.copy(alpha = .75f), fontSize = 14.sp)
            }
        }
    }
}

/** The wand on the big switch: a dark stick with a bright tip, and sparkles that twinkle while the magic is on. */
@Composable
private fun Wand(on: Boolean, ink: Color) {
    val twinkle by rememberInfiniteTransition(label = "wand").animateFloat(
        .3f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "twinkle")
    val glow = if (on) twinkle else .35f
    Canvas(Modifier.size(44.dp)) {
        val w = size.width; val h = size.height
        val tip = Offset(w * .70f, h * .30f)
        drawLine(ink, Offset(w * .12f, h * .88f), tip, strokeWidth = w * .10f, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(w * .60f, h * .40f), tip, strokeWidth = w * .10f, cap = StrokeCap.Round)
        fun star(cx: Float, cy: Float, r: Float, alpha: Float) {
            val c = ink.copy(alpha = alpha.coerceIn(0f, 1f))
            drawLine(c, Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = r * .4f, cap = StrokeCap.Round)
            drawLine(c, Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = r * .4f, cap = StrokeCap.Round)
        }
        star(w * .86f, h * .12f, w * .11f, glow)
        star(w * .93f, h * .44f, w * .07f, if (on) 1.3f - glow else .35f)
        star(w * .52f, h * .10f, w * .06f, glow * .85f)
    }
}

@Composable
private fun ClarityBar(before: Double, after: Double?) {
    val a = LocalAurora.current
    val now = after ?: before
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Voice clarity", color = a.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(Words.clarity(now), color = a.ink, fontWeight = FontWeight.SemiBold)
        }
        // the bar is the sound as you hear it; the thin mark is where it was before the app
        BoxWithConstraints(Modifier.fillMaxWidth().height(10.dp)) {
            val w = maxWidth
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp)).background(a.edge)) {
                Box(Modifier.fillMaxWidth(now.toFloat().coerceIn(0.02f, 1f)).height(10.dp).background(Brush.horizontalGradient(listOf(a.bass, a.voice))))
            }
            if (after != null) Box(Modifier.offset(x = (w * before.toFloat().coerceIn(0f, 1f)) - 1.5.dp).size(3.dp, 10.dp).background(a.ink.copy(alpha = .75f)))
        }
        Hint(when {
            after == null -> "Before the app: ${Words.clarity(before).lowercase()}."
            after > before + 0.03 -> "Before the app: ${Words.clarity(before).lowercase()} (the thin line). Now clearer."
            after < before - 0.03 -> "Before the app: ${Words.clarity(before).lowercase()} (the thin line). Voices are a little softer now, for this kind of sound."
            else -> "About the same as before the app."
        })
        Hint("Estimated from how strong the voice range is against the low boom.")
    }
}

@Composable
private fun MoreControls(sw: UserSwitches, update: (UserSwitches) -> Unit) {
    val a = LocalAurora.current
    var open by remember { mutableStateOf(false) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 2.dp)) {
            Text("More controls", color = a.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (open) "Hide ▲" else "Show ▼", color = a.dim, fontSize = 13.sp)
        }
        if (open) {
            Hint("These stay on for everything you play, until you move them back to 0.")
            val f = sw.manualFine
            Knob("Deep rumble", "The lowest boom, felt more than heard.", f.rumble, a.bass) { update(sw.copy(manualFine = f.copy(rumble = it))) }
            Knob("Presence", "Singers sound closer to you.", f.presence, a.voice) { update(sw.copy(manualFine = f.copy(presence = it))) }
            Knob("Air", "Openness above the cymbals.", f.air, a.treble) { update(sw.copy(manualFine = f.copy(air = it))) }
            if (f != Fine.ZERO) Quiet("Put these back to 0") { update(sw.copy(manualFine = Fine.ZERO)) }
        } else if (sw.manualFine != Fine.ZERO) Hint("You have moved some of these. They are on for everything.")
    }
}

@Composable
private fun Coverage(s: EngineState, brain: Brain) {
    val a = LocalAurora.current
    var asked by remember { mutableStateOf(false) }
    val pkg = s.now?.packageName
    val name = Words.app(pkg)
    val direct = pkg != null && s.announced.values.any { it.packageName == pkg }
    Card {
        when {
            name == null -> Text("Works in any app that plays sound. Play something to see it here.", color = a.dim, fontSize = 14.sp)
            direct -> Row(verticalAlignment = Alignment.CenterVertically) { Dot(a.good); Text("  Working in $name", color = a.ink) }
            s.globalActive -> Row(verticalAlignment = Alignment.CenterVertically) { Dot(a.warn); Text("  Probably working in $name. Check below.", color = a.ink) }
            else -> Row(verticalAlignment = Alignment.CenterVertically) { Dot(a.warn); Text("  This phone does not let the app reach $name.", color = a.ink) }
        }
        if (name != null) {
            Small("Check that it works here") { brain.coverageDip(); asked = true }
            if (asked) Hint("Listen now: the sound goes quiet for 1 second. If you heard that, it works in $name. If not, it does not, and we say so.")
        }
    }
}

// ------------------------------------------------------------------ settings

@Composable
private fun SettingsPage(s: EngineState, sw: UserSwitches, brain: Brain, update: (UserSwitches) -> Unit, back: () -> Unit) {
    val a = LocalAurora.current
    Quiet("← Back") { back() }
    Text("Settings", color = a.ink, fontSize = 34.sp, fontWeight = FontWeight.Light)

    // simple first (his ask, 22 Sep: "make the ui in setting part a little simpler"): the one switch and how much
    Card {
        Toggle("Tune automatically", "Picks the best sound for whatever is playing.", sw.autoEnabled) { update(sw.copy(autoEnabled = it)) }
    }

    Card {
        Text("Extras", color = a.ink, fontWeight = FontWeight.SemiBold)
        Toggle("Night mode", "Voices clearer, sudden loud bangs softer.", sw.nightMode) { update(sw.copy(nightMode = it)) }
        Toggle("8D sound", "Music slowly moves around your head. Earbuds only.", sw.eightD) { update(sw.copy(eightD = it)) }
        Toggle("Learn what I like", "Remembers your changes and uses them on similar songs.", sw.learnEnabled) { update(sw.copy(learnEnabled = it)) }
    }

    var experts by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { experts = !experts }.padding(vertical = 8.dp, horizontal = 4.dp)) {
        Text("For experts", color = a.dim, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(if (experts) "Hide ▲" else "Show ▼", color = a.dim, fontSize = 13.sp)
    }
    if (experts) ExpertSettings(s, sw, brain, update)
    TrustCard()
}

/** Privacy, safety, credits and the licence texts (assets/licenses), research/legal-check.md. */
@Composable
private fun TrustCard() {
    val a = LocalAurora.current
    val ctx = LocalContext.current
    Card {
        Text(Words.TRUST_TITLE, color = a.ink, fontWeight = FontWeight.SemiBold)
        Hint(Words.TRUST_PRIVACY)
        Hint(Words.TRUST_SAFETY)
        Hint(Words.TRUST_COPIES)
        Hint(Words.TRUST_AS_IS)
        Hint(Words.TRUST_OWNER)
        Words.TRUST_CREDITS.forEach { Hint(it) }
        var open by remember { mutableStateOf(false) }
        Small(if (open) "Hide the licences" else Words.TRUST_LICENCES) { open = !open }
        if (open) {
            val text = remember {
                listOf("polyform-noncommercial-1.0.0.txt", "ofl-1.1.txt", "apache-2.0.txt").joinToString("\n\n") { f ->
                    runCatching { ctx.assets.open("licenses/$f").bufferedReader().readText() }.getOrDefault("")
                }
            }
            Text(text, color = a.dim, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun ExpertSettings(s: EngineState, sw: UserSwitches, brain: Brain, update: (UserSwitches) -> Unit) {
    val a = LocalAurora.current
    val ctx = LocalContext.current
    Card {
        var msg by remember { mutableStateOf<String?>(null) }
        val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val text = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull()
                val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Imported"
                msg = if (text == null) "Could not open that file." else brain.importCorrection(name, text) ?: "Done. Used for ${s.output.name}."
            }
        }
        Toggle("Fix my earbuds' sound", "Evens out what your earbuds get wrong. Needs a file for your model.", sw.correctionEnabled) { update(sw.copy(correctionEnabled = it)) }
        Hint(if (s.correctionName != null) "Using: ${s.correctionName} (for ${s.output.name})."
             else "Get the file free at autoeq.app: search your earbuds, download \"ParametricEQ.txt\", then add it here.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Small("Add my earbuds' file") { open.launch(arrayOf("text/plain", "*/*")) }
            if (s.correctionName != null) Small("Remove the file") { brain.removeCorrection(); msg = "Removed." }
        }
        msg?.let { Hint(it) }
    }

    Card {
        Text("This song", color = a.ink, fontWeight = FontWeight.SemiBold)
        Hint("Songs learned so far: ${s.tasteSamples}.")
        Small("Forget what I kept for this song", enabled = s.now?.songKey != null) { brain.forgetThisSong() }
    }
}

// ------------------------------------------------------------------ the blind test

@Composable
private fun TestPage(s: EngineState, sw: UserSwitches, brain: Brain, update: (UserSwitches) -> Unit, startDemo: Boolean = false, back: () -> Unit) {
    val a = LocalAurora.current
    val ctx = LocalContext.current
    var ch by remember { mutableStateOf<Challenge?>(null) }
    var action by remember { mutableStateOf<String?>(null) } // what the app did about the result, said plainly
    var round by remember { mutableIntStateOf(0) } // bumps on each pick so the screen redraws
    val demoOn by DemoSong.playing.collectAsState()
    var usingDemo by remember { mutableStateOf(false) }
    // the demo plays only while this page is open (MainActivity.onStop stops it too: never a song left looping in a pocket)
    DisposableEffect(Unit) { onDispose { DemoSong.stop() } }
    // the demo was stopped under a running test (app left): back to the start card, never buttons that play nothing
    LaunchedEffect(demoOn) { if (usingDemo && !demoOn) { brain.challengeEnd(); ch = null; usingDemo = false } }
    // came from the home card's demo button: start at once (only if nothing else began playing meanwhile)
    LaunchedEffect(Unit) { if (startDemo && !s.isPlaying) { DemoSong.start(ctx); usingDemo = true; ch = Challenge(); round = 0 } }
    val c = ch
    Quiet("← Back") { brain.challengeEnd(); DemoSong.stop(); back() }
    Text("Hear the difference", color = a.ink, fontSize = 34.sp, fontWeight = FontWeight.Light)
    Card {
        if (c == null) {
            Hint("Play a song you know well. You will hear two versions, A and B, at exactly the same loudness. One is tuned by the app, one is untouched, and you are not told which. Pick the one you like. 6 rounds.")
            if (s.isPlaying) Primary("Start the test") { ch = Challenge(); round = 0 }
            else {
                // a song playing elsewhere is always used as before; only with nothing playing does the demo step in
                Hint(Words.DEMO_TEST_HINT)
                Primary(Words.DEMO_BUTTON) { DemoSong.start(ctx); usingDemo = true; ch = Challenge(); round = 0 }
            }
        } else if (usingDemo && !(s.running && s.isPlaying && s.now?.title == Words.DEMO_TITLE && DemoSong.audioSessionId in s.announced)) {
            // the engine publishes once a second, AFTER it has attached to the demo and planned for it: until the
            // state names the demo, A and B would switch a chain that is not on it yet
            Hint("Getting the demo song ready…")
        } else if (!c.done) {
            Label("Round ${round.let { c.round } + 1} of ${c.rounds}")
            val enhancedIsA = c.enhancedIsA[c.round]
            val nowA = s.challenge?.let { it.enhancedAudible == enhancedIsA }
            // a pick only counts once both sides were heard in this round (Challenge.canPick)
            fun play(enhanced: Boolean) { brain.challengePlay(enhanced); c.heard(enhanced); round++ }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Secondary(if (nowA == true) "Playing A" else "Play A", Modifier.weight(1f), selected = nowA == true) { play(enhancedIsA) }
                Secondary(if (nowA == false) "Playing B" else "Play B", Modifier.weight(1f), selected = nowA == false) { play(!enhancedIsA) }
            }
            val ready = round >= 0 && c.canPick
            Hint(if (ready) "Switch between them as often as you like, then choose." else "Play both A and B first. Then you can choose.")
            fun choose(pickedA: Boolean) {
                c.pick(pickedA); round++
                if (c.done) {
                    brain.challengeEnd()
                    // the untouched sound won: really turn the tuning down one step, and say so (never a promise)
                    val r = c.result()
                    val lower = if (r.verdict == Challenge.Verdict.PREFERS_ORIGINAL) Strength.stepDown(sw.intensity) else null
                    if (lower != null) update(sw.copy(intensity = lower))
                    action = Words.challengeAction(r.verdict, lower)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Primary("I like A more", Modifier.weight(1f), enabled = ready) { choose(true) }
                Primary("I like B more", Modifier.weight(1f), enabled = ready) { choose(false) }
            }
        } else {
            Text(c.result().message, color = a.ink, fontWeight = FontWeight.SemiBold)
            action?.let { Text(it, color = a.ink) }
            Primary("Finish") { ch = null; brain.challengeEnd(); back() }
            Quiet("Try again") { ch = Challenge(); round = 0; action = null }
        }
    }
}

// ------------------------------------------------------------------ small parts

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Column(modifier.fillMaxWidth().glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }

@Composable private fun Dot(c: Color) = Box(Modifier.size(9.dp).clip(CircleShape).background(c))

@Composable
private fun Pill(text: String) {
    val a = LocalAurora.current
    Text(text, color = a.dim, fontSize = 13.sp, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(a.glass).border(1.dp, a.edge, RoundedCornerShape(99.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
private fun Art(seed: String?) {
    if (seed == null) { Box(Modifier.size(52.dp).glass(14.dp)); return }
    val h = (seed ?: "").hashCode()
    fun c(shift: Int) = Color(android.graphics.Color.HSVToColor(floatArrayOf(((h ushr shift) and 0xFF) / 255f * 360f, .6f, .75f)))
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(c(0), c(8), c(16)))))
}

/** Safety promises as green circle-ticks, one per line - never buried in a paragraph (his ask, 22 Sep). */
@Composable
private fun Ticks(promises: List<String>) {
    val a = LocalAurora.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in promises) Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(a.good), contentAlignment = Alignment.Center) {
                Text("✓", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Text(p, color = a.ink, fontSize = 15.sp, lineHeight = 20.sp, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

/** "Do this -> then this": small chips joined by arrows, wrapping onto new lines on a narrow phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PathChips(steps: List<String>) {
    val a = LocalAurora.current
    val shape = RoundedCornerShape(99.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("What to do", color = a.dim, fontSize = 12.sp, letterSpacing = 1.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            steps.forEachIndexed { i, step ->
                if (i > 0) Text("→", color = a.good, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("${i + 1}. $step", color = a.ink, fontSize = 13.sp,
                    modifier = Modifier.clip(shape).background(a.glass).border(1.dp, a.edge, shape).padding(horizontal = 11.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable private fun Hint(text: String) = Text(text, color = LocalAurora.current.dim, fontSize = 13.sp, lineHeight = 18.sp)
@Composable private fun Label(text: String) = Text(text, color = LocalAurora.current.dim, fontSize = 13.sp)
@Composable private fun Big(text: String) = Text(text, color = LocalAurora.current.ink, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)

@Composable
private fun Bar(f: Float) {
    val a = LocalAurora.current
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)).background(a.edge)) {
        Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(6.dp).background(Brush.horizontalGradient(listOf(a.good, a.treble))))
    }
}

@Composable
private fun Knob(name: String, meaning: String, v: Double, color: Color, app: Double? = null, set: (Double) -> Unit) {
    val a = LocalAurora.current
    // with [app]: the slider shows the TOTAL (the app's choice + yours), and dragging sets only your part
    val total = v + (app ?: 0.0)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = a.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (app != null) Text(Words.sliderSource(v), color = a.dim, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
            Text(Words.signed(total), color = a.ink, fontWeight = FontWeight.SemiBold)
        }
        // the meaning on its own line, in everyday words (his ask, 22 Sep)
        Text(meaning, color = a.dim, fontSize = 13.sp, lineHeight = 17.sp)
        if (app == null) CenterSlider(v, color, set)
        else CenterSlider(total, color, { set(it - app) }, anchor = app)
    }
}

@Composable
private fun sliderColors(c: Color) = SliderDefaults.colors(
    thumbColor = c, activeTrackColor = c, inactiveTrackColor = LocalAurora.current.edge,
    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)

@Composable
private fun Toggle(title: String, what: String, on: Boolean, set: (Boolean) -> Unit) {
    val a = LocalAurora.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { set(!on) }.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) { Text(title, color = a.ink, fontSize = 16.sp); Text(what, color = a.dim, fontSize = 13.sp, lineHeight = 18.sp) }
        Spacer(Modifier.width(12.dp))
        Switch(on, set, colors = SwitchDefaults.colors(checkedTrackColor = a.ink, checkedThumbColor = a.onPrimary))
    }
}

@Composable
private fun Primary(text: String, modifier: Modifier = Modifier.fillMaxWidth(), enabled: Boolean = true, onClick: () -> Unit) {
    val a = LocalAurora.current
    Box(modifier.heightIn(min = 54.dp).clip(RoundedCornerShape(18.dp)).background(if (enabled) a.ink else a.edge)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) a.onPrimary else a.dim, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Secondary(text: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val a = LocalAurora.current
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.heightIn(min = 54.dp).clip(shape).background(if (selected) a.voice.copy(alpha = .35f) else a.glass).border(1.dp, a.edge, shape)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = a.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Small(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val a = LocalAurora.current
    val shape = RoundedCornerShape(99.dp)
    Text(text, color = if (enabled) a.ink else a.dim, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(shape).border(1.dp, a.edge, shape).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp))
}

@Composable
private fun Quiet(text: String, onClick: () -> Unit) {
    val a = LocalAurora.current
    Text(text, color = a.dim, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp))
}
