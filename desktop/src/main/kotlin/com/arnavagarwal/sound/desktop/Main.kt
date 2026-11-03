package com.arnavagarwal.sound.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberWindowState
import com.arnavagarwal.sound.core.EarLevel
import com.arnavagarwal.sound.core.Strength
import com.arnavagarwal.sound.core.Tone
import com.arnavagarwal.sound.core.VolumeCoach
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import java.io.File
import java.io.RandomAccessFile

/**
 * The Windows app. Runs on the JDK's own java.exe (a freshly built launcher .exe is blocked by Defender here, E-013).
 * Closing the window keeps the tuning on (the tray icon stays); Quit puts the sound back to exactly how it was.
 */
fun main(args: Array<String>) {
    // two copies would fight over Equalizer APO's file
    val lockDir = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "SoundApp").apply { mkdirs() }
    val lock = RandomAccessFile(File(lockDir, "running.lock"), "rw").channel.tryLock()
    // the desktop shortcut (30 Sep) starts a second copy while the logon one sits in the tray: ask it to show its window
    val showRequest = File(lockDir, "show.request")
    if (lock == null) { showRequest.writeText("show"); System.err.println("Sound is already running - asked it to open."); return }
    showRequest.delete()

    // gradle :desktop:preview passes --look: the window opens but can never change what someone is hearing.
    // (23 Sep: the old -Dsound.startOff route left the tuning ON while a song was playing - E-015.)
    val lookOnly = "--look" in args
    System.err.println("Sound: " + if (lookOnly) "LOOK ONLY - Equalizer APO will not be written" else "normal start")
    val apo = Apo(lookOnly = lookOnly)
    val engine = Engine(apo, ToolsRoot.helper, lookOnly = lookOnly)
    // even if the window is closed by Windows (log off, shutdown), the laptop goes back to normal (E-012)
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { apo.neutral() } })
    engine.start()

    application {
        val st by engine.state.collectAsState()
        // --tray (the logon start, tools/install/install.ps1): only the icon near the clock, no window in his face
        var visible by remember { mutableStateOf(!("--tray" in args && isTraySupported)) }
        val icon = remember { CloudIcon() }
        fun quit() { engine.stop(); exitApplication() }
        if (isTraySupported) {
            Tray(icon = icon, tooltip = "Audito Amplificus", onAction = { visible = true }, menu = {
                Item("Open Sound", onClick = { visible = true })
                Item(if (st.tuningOn) "Turn tuning off" else "Turn tuning on", onClick = { engine.setTuning(!st.tuningOn) })
                Separator()
                Item("Quit (sound goes back to normal)", onClick = ::quit)
            })
        }
        val windowState = rememberWindowState(width = 460.dp, height = 860.dp)
        LaunchedEffect(Unit) {
            while (true) {
                if (showRequest.exists()) { showRequest.delete(); visible = true; windowState.isMinimized = false }
                delay(500)
            }
        }
        Window(
            onCloseRequest = { if (isTraySupported) visible = false else quit() },
            visible = visible, title = "Audito Amplificus", icon = icon,
            state = windowState,
        ) {
            // Minimised, the drifting cloud still redrew ~60 times a second: ~20% of a core, the app's whole
            // battery cost (the sound engine itself is ~0.2%). Nobody can see it then, so it stands still.
            AuroraTheme { Home(st, engine, seen = visible && !windowState.isMinimized) }
        }
    }
}

// ---------------------------------------------------------------- the page

@Composable
fun Home(st: DesktopState, engine: Engine, seen: Boolean = true) {
    val a = LocalAurora.current
    val speaker = st.device?.isSpeaker ?: true
    val parts = Words.parts(st.curveDb, speaker)
    val playing = st.playing?.playing == true
    Column(
        Modifier.fillMaxSize().background(a.bg).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Audito Amplificus", color = a.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(Words.device(st.device, st.reachesOutput, st.apoInstalled), color = if (st.reachesOutput) a.dim else a.warn, fontSize = 14.sp)
        // portable copy, first open: no Equalizer APO yet, so one button downloads + installs it and switches it on
        val installer = ToolsRoot.installApo
        if (!st.apoInstalled && installer != null) Card {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(Words.INSTALL_APO_HELP, color = a.ink, fontSize = 14.sp, lineHeight = 20.sp)
                Pill(Words.INSTALL_APO, selected = false) { Apo.installEverything(installer) }
            }
        }
        // at the TOP, not the foot of the page: on new headphones he never scrolled down to it (9 Oct 2026)
        if (st.apoInstalled && st.device != null && !st.reachesOutput) Card {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val auto = ToolsRoot.apoSetup.isFile
                Text(if (auto) Words.NOT_ON_DEVICE_HELP else Words.NOT_ON_DEVICE_HELP_BY_HAND, color = a.ink, fontSize = 14.sp, lineHeight = 20.sp)
                if (auto) Pill(Words.SWITCH_ON_EVERYWHERE, selected = false) { Apo.switchOnEverywhere() }
                else Pill(Words.OPEN_DEVICE_SELECTOR, selected = false) { Apo.openDeviceSelector() }
            }
        }
        SafetyBadge()

        // the cloud, with what is playing on top of it
        Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
            AuroraCloud(parts, active = st.tuningOn && st.reachesOutput && playing, drift = seen, modifier = Modifier.fillMaxSize())
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(st.playing?.title ?: "Nothing playing", color = a.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, maxLines = 2)
                val sub = listOfNotNull(st.playing?.artist, Words.app(st.playing?.app)?.let { "in $it" }).joinToString("  ")
                if (sub.isNotBlank()) Text(sub, color = a.dim, fontSize = 13.sp, textAlign = TextAlign.Center)
                if (playing && st.profile != null) Text("Tuning for ${Words.kind(st.profile.top).lowercase()}", color = a.dim, fontSize = 13.sp)
            }
        }
        Text(Words.summary(parts, st.tuningOn, st.reachesOutput, playing), color = a.ink, fontSize = 16.sp)

        // the one big button (his protocol: replay the passage, switch between passes)
        BigSwitch(st.tuningOn) { engine.setTuning(!st.tuningOn) }

        // Strong mode for earbuds / headphones (his tap 31 Oct 2026), right under the wand as on the phone
        Card {
            Text("Strong mode", color = a.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(Words.ABOUT_STRONG, color = a.ink, fontSize = 13.sp, lineHeight = 18.sp)
            Text(Words.strong(st.strongOn, st.strongStep, st.device?.isSpeaker ?: true, st.tuningOn), color = a.dim, fontSize = 13.sp, lineHeight = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("On", selected = st.strongOn, modifier = Modifier.weight(1f)) { engine.setStrong(true) }
                Pill("Off", selected = !st.strongOn, modifier = Modifier.weight(1f)) { engine.setStrong(false) }
            }
            if (st.strongOn) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (step in com.arnavagarwal.sound.core.StrongMode.steps)
                    Pill(Words.strongStep(step), selected = step == st.strongStep, modifier = Modifier.weight(1f)) { engine.setStrongStep(step) }
            }
        }

        // your headphones' own fix (his tap 1 Nov 2026): earbuds / headphones only, like the phone's
        if (st.device?.isSpeaker == false) Card {
            var msg by remember { mutableStateOf<String?>(null) }
            Text(Words.FIX_TITLE, color = a.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(Words.ABOUT_FIX, color = a.ink, fontSize = 13.sp, lineHeight = 18.sp)
            Text(Words.fixNow(st.fixName, st.device?.name), color = a.dim, fontSize = 13.sp, lineHeight = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(Words.FIX_ADD, selected = false, modifier = Modifier.weight(1f)) {
                    val dlg = java.awt.FileDialog(null as java.awt.Frame?, Words.FIX_PICK, java.awt.FileDialog.LOAD)
                    dlg.file = "*.txt"; dlg.isVisible = true
                    dlg.file?.let { engine.importFix(java.io.File(dlg.directory, it)) { m -> msg = m } }
                }
                if (st.fixName != null) Pill(Words.FIX_REMOVE, selected = false, modifier = Modifier.weight(1f)) { engine.removeFix(); msg = Words.FIX_REMOVED }
            }
            msg?.let { Text(it, color = a.dim, fontSize = 13.sp, lineHeight = 18.sp) }
        }

        // density on its own (his protocol, one change per test: E-017)
        Card {
            Text("Density", color = a.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(Words.ABOUT_DENSITY, color = a.ink, fontSize = 13.sp, lineHeight = 18.sp)
            Text(Words.density(st.densityOn, st.densityLive, st.tuningOn, st.densityDb, st.densityCapDb, st.softMiddleDb, st.safeDb), color = a.dim, fontSize = 13.sp, lineHeight = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Auto", selected = st.densityOn, modifier = Modifier.weight(1f)) { engine.setDensity(true) }
                Pill("Off", selected = !st.densityOn, modifier = Modifier.weight(1f)) { engine.setDensity(false) }
            }
        }

        // recommended volume (no mic, learned from him), then the two new effects, each switchable alone (E-017)
        Card {
            Text("Volume", color = a.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(Words.ABOUT_VOLUME, color = a.ink, fontSize = 13.sp, lineHeight = 18.sp)
            Text(Words.volume(st.advice, st.songsLearned, playing), color = a.dim, fontSize = 13.sp, lineHeight = 18.sp)
            val adv = st.advice
            if (adv != null && adv.verdict != VolumeCoach.Verdict.GOOD) Pill(Words.VOLUME_BUTTON, selected = false) { engine.useAdvice() }
        }
        Card {
            Text("Quiet listening", color = a.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(Words.ABOUT_QUIET, color = a.ink, fontSize = 13.sp, lineHeight = 18.sp)
            Text(Words.quiet(st.quietOn, st.songsLearned >= VolumeCoach.MIN_SONGS, st.quietDb), color = a.dim, fontSize = 13.sp, lineHeight = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Auto", selected = st.quietOn, modifier = Modifier.weight(1f)) { engine.setQuiet(true) }
                Pill("Off", selected = !st.quietOn, modifier = Modifier.weight(1f)) { engine.setQuiet(false) }
            }
        }
        // Width card removed 25 Sep: dropped after his blind tests (RULES.md end). Engine keeps it off.
        Card {
            Text("Study music", color = a.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(Words.ABOUT_STUDY, color = a.ink, fontSize = 13.sp, lineHeight = 18.sp)
            Text(Words.study(st.voiceDownDb), color = a.dim, fontSize = 13.sp, lineHeight = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, db) in Words.STUDY_STEPS)
                    Pill(label, selected = st.voiceDownDb == db, modifier = Modifier.weight(1f)) { engine.setVoiceDown(db) }
            }
        }
        Card {
            Text("How strong", color = a.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Gentle" to Strength.GENTLE, "Normal" to Strength.NORMAL, "Strong" to Strength.STRONG).forEach { (label, v) ->
                    Pill(label, selected = Strength.nearest(st.intensity) == v, modifier = Modifier.weight(1f)) { engine.setIntensity(v) }
                }
            }
        }

        Card {
            Text("Change it yourself", color = a.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(Words.slidersHint(st.tuningOn), color = a.dim, fontSize = 13.sp, lineHeight = 18.sp)
            val t = st.manualTone; val app = st.appTone
            Knob("Bass", "The deep boom: drums, bass guitar.", t.bass, a.bass, app.bass) { engine.setManualTone(t.copy(bass = it)) }
            Knob("Warmth", Words.ABOUT_WARMTH, t.warmth, a.ink, app.warmth) { engine.setManualTone(t.copy(warmth = it)) }
            Knob("Clarity", "Voices and words.", t.clarity, a.voice, app.clarity) { engine.setManualTone(t.copy(clarity = it)) }
            Knob("Treble", "The shine: cymbals, \"s\" sounds.", t.treble, a.treble, app.treble) { engine.setManualTone(t.copy(treble = it)) }
            if (t != Tone.ZERO) Pill("Back to the app's setting", selected = false) { engine.setManualTone(Tone.ZERO) }
        }

        Card {
            Text("Hear the difference", color = a.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(Words.HEAR_STEPS, color = a.dim, fontSize = 14.sp, lineHeight = 20.sp)
            Answer(Words.Q_BETTER, listOf("With tuning" to true, "Without" to false, "Same" to null)) { engine.judged(it) }
            Answer(Words.Q_LOUDER, listOf("Tuned louder" to EarLevel.Heard.TUNED_LOUDER, "Same" to EarLevel.Heard.SAME,
                "Tuned quieter" to EarLevel.Heard.TUNED_QUIETER)) { engine.heard(it) }
            Words.tally(st.tally.first, st.tally.second, st.tally.third)?.let { Text(it, color = a.dim, fontSize = 13.sp) }
            Words.earLevel(st.earLevelDb)?.let { Text(it, color = a.dim, fontSize = 13.sp) }
        }

        // file tools, not used while listening, and the ear-test card: at the bottom (his words and tap 29 Sep)
        // research-only sound models: never in a PAID copy (Edition.kt)
        if (Edition.researchModelsAllowed() && Karaoke.available) Card {
            val k by Karaoke.state.collectAsState()
            Text("Karaoke & music only", color = a.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(Words.karaoke(k), color = a.dim, fontSize = 13.sp, lineHeight = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(Words.KARAOKE_PICK, selected = false, modifier = Modifier.weight(1f)) { Karaoke.pickAndRun() }
                if (k is Karaoke.State.Done) Pill(Words.KARAOKE_OPEN, selected = false, modifier = Modifier.weight(1f)) { Karaoke.openFolder() }
            }
        }
        if (EightD.available) Card {
            val e by EightD.state.collectAsState()
            Text("8D copy", color = a.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(Words.eightD(e), color = a.dim, fontSize = 13.sp, lineHeight = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(Words.KARAOKE_PICK, selected = false, modifier = Modifier.weight(1f)) { EightD.pickAndRun() }
                if (e is EightD.State.Done) Pill(Words.KARAOKE_OPEN, selected = false, modifier = Modifier.weight(1f)) { EightD.openFolder() }
            }
        }
        if (Edition.researchModelsAllowed() && Denoise.available) Card {
            val d by Denoise.state.collectAsState()
            Text("Clean a talk", color = a.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(Words.denoise(d), color = a.dim, fontSize = 13.sp, lineHeight = 18.sp)
            // one button while idle; the strength is asked only after a file is picked (his tap 28 Sep)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d is Denoise.State.Chosen) {
                    for ((label, db) in Words.DENOISE_STEPS)
                        Pill(label, selected = false, modifier = Modifier.weight(1f)) { Denoise.choose(db) }
                    Pill(Words.DENOISE_CANCEL, selected = false, modifier = Modifier.weight(1f)) { Denoise.choose(null) }
                } else {
                    Pill(Words.DENOISE_PICK, selected = false, modifier = Modifier.weight(1f)) { Denoise.pick() }
                    if (d is Denoise.State.Done) Pill(Words.KARAOKE_OPEN, selected = false, modifier = Modifier.weight(1f)) { Denoise.openFolder() }
                }
            }
        }

        Words.clip(st.clipTrimDb)?.let { Text(it, color = a.dim, fontSize = 13.sp) }
        st.problem?.let { Card { Text(it, color = a.warn, fontSize = 14.sp) } }
        Text(Words.CLOSE_NOTE, color = a.dim, fontSize = 12.sp, lineHeight = 17.sp)
        Text(Words.OWNER, color = a.dim, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().glass(20.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

/** A big green tick and the three safety facts, in a softly green-edged box - the phone's badge (his ask, 8 Oct). */
@Composable
private fun SafetyBadge() {
    val a = LocalAurora.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(a.good.copy(alpha = .10f)).border(1.dp, a.good.copy(alpha = .55f), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(a.good), contentAlignment = Alignment.Center) {
            Text("✓", color = Color.Black, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(Words.SAFE_TITLE, color = a.good, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            for (l in Words.SAFE_LINES) Text(l, color = a.ink, fontSize = 13.sp, lineHeight = 17.sp)
        }
    }
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
private fun Pill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val a = LocalAurora.current
    val shape = RoundedCornerShape(22.dp) // a full pill when one line, a soft card if a label ever wraps
    Box(
        modifier.clip(shape).background(if (selected) a.ink else a.glass).border(1.dp, a.edge, shape).clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) a.onPrimary else a.ink, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 17.sp) }
}

/** One question, answered with a tap; the row then says it was saved until the next pair (one pair per question). */
@Composable
private fun <T> Answer(question: String, options: List<Pair<String, T>>, onAnswer: (T) -> Unit) {
    val a = LocalAurora.current
    var saved by remember { mutableStateOf(false) }
    Text(question, color = a.ink, fontSize = 15.sp)
    if (saved) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Words.THANKS, color = a.good, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Pill("Answer again", selected = false) { saved = false }
        }
    } else Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // equal heights even if a label has to wrap on a narrow window
        options.forEach { (label, v) -> Pill(label, selected = false, modifier = Modifier.weight(1f).fillMaxHeight()) { onAnswer(v); saved = true } }
    }
}

@Composable
private fun Knob(name: String, meaning: String, v: Double, color: Color, app: Double, set: (Double) -> Unit) {
    val a = LocalAurora.current
    // the slider shows the TOTAL (the app's choice + yours); dragging sets only your part
    val total = v + app
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = a.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(Words.sliderSource(v), color = a.dim, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
            Text(Words.signed(total), color = a.ink, fontWeight = FontWeight.SemiBold)
        }
        Text(meaning, color = a.dim, fontSize = 13.sp, lineHeight = 17.sp)
        CenterSlider(total, color, { set(it - app) }, anchor = app)
    }
}

/** The tray and window icon: the three Aurora lights, drawn (no image file to lose). */
class CloudIcon : Painter() {
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        val w = size.width; val h = size.height
        fun light(c: Color, x: Float, y: Float, r: Float) =
            drawCircle(Brush.radialGradient(listOf(c, c.copy(alpha = .6f), Color.Transparent), Offset(x, y), r), r, Offset(x, y))
        light(Color(0xFFFF7A2F), w * .34f, h * .62f, w * .42f)
        light(Color(0xFFE0409B), w * .52f, h * .38f, w * .38f)
        light(Color(0xFF35C9FF), w * .70f, h * .62f, w * .34f)
    }
}
