package com.arnavagarwal.sound.desktop

import com.arnavagarwal.sound.core.ContentKind
import com.arnavagarwal.sound.core.Grid

/**
 * Everything the laptop window SAYS, in plain words (his rule, 22 Sep: "all buttons tell in simple words what they
 * do"). Pure functions, so the wording is unit-tested rather than eyeballed. The phone's twin is app/ui/Words.kt.
 */
object Words {
    /** The three parts of the sound the cloud and the summary talk about, averaged in dB from the live curve. */
    data class Parts(val bass: Double, val voice: Double, val treble: Double)

    fun parts(curveDb: DoubleArray, speaker: Boolean): Parts {
        fun avg(lo: Double, hi: Double): Double {
            val v = Grid.freqs.indices.filter { Grid.freqs[it] in lo..hi }.map { curveDb[it] }
            return if (v.isEmpty()) 0.0 else v.average()
        }
        // on laptop speakers the bass you can hear is their lowest playable notes (Speaker moves it up there); reading
        // the deep bass would call a bass lift "less boom"
        return Parts(bass = if (speaker) avg(250.0, 500.0) else avg(40.0, 180.0), voice = avg(1_000.0, 4_000.0), treble = avg(5_600.0, 16_000.0))
    }

    /** One sentence saying what the app is doing to the sound right now. */
    fun summary(p: Parts, on: Boolean, reaches: Boolean, playing: Boolean): String {
        if (!on) return "Tuning is off. Your laptop sounds exactly as it did before."
        if (!reaches) return "Tuning cannot reach this output yet (see the box at the top)."
        if (!playing) return "Play something. The app will tune it for you."
        val bits = buildList {
            if (p.bass > T) add("fuller bass") else if (p.bass < -T) add("less boom")
            if (p.voice > T) add("clearer voices") else if (p.voice < -T) add("softer voices")
            if (p.treble > T) add("more sparkle") else if (p.treble < -T) add("less harsh highs")
        }
        if (bits.isEmpty()) return "A light touch. It already sounds good."
        return bits.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }

    private const val T = 0.75

    fun kind(k: ContentKind): String = when (k) {
        ContentKind.SPEECH -> "Talk / lecture"
        ContentKind.BASS_HEAVY -> "Bass-heavy music"
        ContentKind.SMOOTH -> "Soft / acoustic"
        ContentKind.VOCAL_POP -> "Vocal pop / Bollywood"
        ContentKind.CLASSICAL -> "Classical"
        ContentKind.ENERGETIC -> "Rock / energetic"
        ContentKind.MOVIE -> "Film / video"
    }

    private val APPS = mapOf("chrome" to "Chrome", "msedge" to "Edge", "firefox" to "Firefox", "spotify" to "Spotify",
        "vlc" to "VLC", "microsoft.zunemusic" to "Media Player", "microsoft.media.player" to "Media Player", "brave" to "Brave")

    /** Windows names the playing app by an id like "Chrome.UserData.Profile4" or "Spotify.exe"; this is the name he knows. */
    fun app(id: String?): String? {
        if (id.isNullOrBlank()) return null
        val low = id.lowercase().removeSuffix(".exe")
        APPS.entries.firstOrNull { low == it.key || low.startsWith(it.key + ".") || low.startsWith(it.key + "_") }?.let { return it.value }
        val head = id.substringBefore('!').substringBefore('_').substringBefore('.').removeSuffix(".exe")
        return head.ifBlank { null }?.replaceFirstChar { it.uppercase() }
    }

    fun device(d: HelperEvent.Device?, reaches: Boolean, apoInstalled: Boolean): String = when {
        d == null -> "Looking for your speakers..."
        !apoInstalled -> "Equalizer APO is not installed, so nothing can be tuned yet."
        reaches -> "Working on ${d.name}"
        else -> "Not working on ${d.name} yet: Equalizer APO is not switched on for it."
    }

    /** What to do when it cannot reach this output (the Device Selector needs his admin tap, E-011). */
    /** With ApoSetup (9 Oct 2026, his "what about people who will use this"): one tap, nothing to tick, no restart. */
    const val NOT_ON_DEVICE_HELP = "Equalizer APO, the part that changes the sound, is not switched on for this output yet. " +
        "Press the button and tap Yes when Windows asks. It switches on every headphone and speaker this laptop knows, " +
        "and any you connect later, by itself. The sound stops for a second while Windows reloads it."
    const val SWITCH_ON_EVERYWHERE = "Switch it on for all my headphones"

    /** Portable copy, first open (10 Oct 2026): Equalizer APO is not on this laptop yet. */
    const val INSTALL_APO_HELP = "First, one setup step. This app changes your sound through Equalizer APO, a free " +
        "sound add-on for Windows. Press the button and tap Yes when Windows asks: it downloads Equalizer APO (about " +
        "12 MB), puts it in, and switches it on for every speaker and headphone. Give it a minute; the sound stops " +
        "for a second at the end. If it still says not working after that, restart the laptop once."
    const val INSTALL_APO = "Set up the sound part"
    /** Fallback when ApoSetup is missing: Equalizer APO's own Device Selector, by hand. */
    const val NOT_ON_DEVICE_HELP_BY_HAND = "Equalizer APO works only on the outputs ticked in its Device Selector. Connect every " +
        "headphone you use first (Bluetooth and dongle ones are listed only while connected), press the button, tap Yes, " +
        "tick them all, press OK, then restart the laptop. Each new headphone needs this once."
    const val OPEN_DEVICE_SELECTOR = "Open Equalizer APO's Device Selector"

    /** The one big button: his test protocol (23 Sep) is to replay the passage and switch the tuning between passes. */
    fun switchTitle(on: Boolean) = if (on) "Audito Amplificus" else "Audito Amplificus"
    fun switchAction(on: Boolean) = if (on) "The magic is on. Tap to hear it plain" else "Magic is off. Tap to cast it"

    const val HEAR_STEPS = "1. Play a short part of a song.\n2. Tap the big button.\n3. Play the same part again.\nThen answer below."
    const val Q_BETTER = "Which sounded better?"
    const val Q_LOUDER = "Did one sound louder?"
    const val THANKS = "Saved. Try another part, or another song."

    /**
     * The green tick under the title, always there (his ask, 8 Oct), same title as the phone (app WordsTest pins it).
     * True of this app: the helper listens by LOOPBACK to what the laptop plays, never to a microphone; the song log keeps
     * numbers, not sound; nothing in it talks to a network. Karaoke / clean / 8D make new files only from a file he picks.
     */
    const val SAFE_TITLE = "Private and safe"
    val SAFE_LINES = listOf(
        "Your microphone is never used. The app never hears you.",
        "It measures only what your laptop is playing. Nothing is recorded.",
        "Nothing is sent anywhere. Everything stays on this laptop.",
    )

    fun tally(tuned: Int, plain: Int, same: Int): String? =
        if (tuned + plain + same == 0) null else "So far: with tuning won $tuned, without won $plain, same $same."

    /** When his answers have moved the tuned side's level. */
    fun earLevel(db: Double): String? = if (kotlin.math.abs(db) < 0.05) null
        else "Tuned side ${if (db < 0) "turned down" else "turned up"} ${amount(db)} dB from your answers."

    fun clip(trimDb: Double): String? = if (trimDb > -0.05) null
        else "Turned down ${amount(trimDb)} dB for now, so loud parts do not crackle."

    /* What each feature DOES to the song, shown under its title all the time (his ask 29 Sep: "all features need a
     * simple explanation easy to understand what they do to the song"; narrowed to these five). The status line
     * under each stays as it was. Every word here must stay true to the code it describes: DensityControl,
     * VolumeCoach + Engine.useAdvice, QuietFix (MAX_DB 6), LaptopSetting.voiceDownText, Tone's 300 Hz warmth filter. */
    const val VOLUME_BUTTON = "Set it for me"
    const val ABOUT_DENSITY = "Makes the song feel fuller and bigger. The app raises the whole song, and a limiter " +
        "(an automatic ceiling) stops the loudest hits from going any higher. So the quiet moments, like a soft " +
        "verse or a fading note, come up closer to the loud ones. That packed-together sound is the density."
    const val ABOUT_VOLUME = "Every song is released at its own loudness, so at the same volume one song sounds weak " +
        "and the next one blasts. The app learns the volume you usually leave songs at, compares each new song to it, " +
        "and tells you whether to turn this one up or down. \"$VOLUME_BUTTON\" moves the Windows volume for you."
    val ABOUT_QUIET = "When sound is quiet, your ears stop hearing the deep notes first (and a little of the " +
        "bright top), so music turns thin. If you listen quieter than usual, this adds that low end and a touch of " +
        "treble back, up to ${com.arnavagarwal.sound.core.QuietFix.MAX_DB.toInt()} dB, so the song sounds the same, just softer. At your normal volume it does nothing."
    const val ABOUT_STUDY = "Turns the singer down and leaves the instruments. It cuts the band of pitches where " +
        "voices sit, so the words stop pulling your attention while you study. The beat and the tune stay."
    const val ABOUT_WARMTH = "Body and fullness: the low-middle pitches around 300 Hz, where the thick part of a " +
        "voice, guitar and piano sits. Up sounds richer and cosier; too much sounds muddy, down sounds thin."

    /** The density card: what it does, and why it may not be playing right now. */
    fun density(on: Boolean, live: Boolean, tuningOn: Boolean, db: Double = 0.0, capDb: Double = 0.0, softDb: Double = 0.0,
                safeDb: Double = 0.0): String = densityCore(on, live, tuningOn, db, capDb, softDb, safeDb) + speakerSafe(safeDb, on)

    /** Speaker-safe loudness (LaptopSetting.speakerBackOffDb), said only while it is actually easing something. */
    private fun speakerSafe(safeDb: Double, densityOn: Boolean): String = if (safeDb < 0.05) "" else
        " Volume is high, so to spare the speakers the app is easing " +
            (if (densityOn) "density by ${one(safeDb)} dB and " else "") +
            "the voice boosts by ${one(safeDb * LaptopSetting.BOOST_SHARE)} dB. Below 75% volume nothing is eased."

    private fun one(x: Double) = String.format(java.util.Locale.ROOT, "%.1f", x)

    private fun densityCore(on: Boolean, live: Boolean, tuningOn: Boolean, db: Double, capDb: Double, softDb: Double, safeDb: Double): String = when {
        !on -> "Off. Turn it on to keep the whole song closer to the speakers' full strength."
        live && capDb < 0.05 && safeDb >= 0.05 -> "Auto: none at this volume."
        live && capDb < 0.05 -> "Auto: none for this kind of music. Its loud and soft moments are part of it."
        live -> "Auto: ${signed(db)} dB for this song, chosen by the app (up to ${signed(capDb)} for this kind). " +
            "Quiet parts are lifted and loud parts held, so the song sounds fuller and carries further." +
            (if (softDb >= 0.5) " Voice-led song: the middle is ${String.format(java.util.Locale.ROOT, "%.1f", softDb)} dB gentler, " +
                "as you liked on Husn and Jo Tum Mere Ho." else "")
        !tuningOn -> "On, but tuning is off, so it is not playing right now."
        else -> "On, but it only plays on the laptop's own speakers."
    }

    /** The volume card (core VolumeCoach). No mic: it learns from where he leaves the slider. */
    fun volume(a: com.arnavagarwal.sound.core.VolumeCoach.Advice?, learned: Int, playing: Boolean): String {
        val need = com.arnavagarwal.sound.core.VolumeCoach.MIN_SONGS - learned
        if (need > 0) return "Learning the volume you like. Ready after $need more " +
            (if (need == 1) "song" else "songs") + " played through at a volume you leave alone."
        if (a == null) return if (playing) "Listening to this song..." else "Play something to get a volume for it."
        return when (a.verdict) {
            com.arnavagarwal.sound.core.VolumeCoach.Verdict.GOOD -> "This volume suits this song."
            com.arnavagarwal.sound.core.VolumeCoach.Verdict.UP -> "This song was made quieter than most. Turn it up " +
                (if (a.changeDb >= 6) "quite a bit" else "a little") + " and it will sound fuller."
            com.arnavagarwal.sound.core.VolumeCoach.Verdict.DOWN -> "This song is louder than you usually listen. Turn it " +
                (if (a.changeDb <= -6) "down quite a bit" else "down a little") + ". It will sound just as good."
        }
    }

    /** The low-volume fix card (core QuietFix). */
    /** Strong mode (Engine.setStrong, his tap 31 Oct 2026): what it does, always shown on its card. */
    // ---- your headphones' own fix (1 Nov 2026), worded like the phone's "Fix my earbuds' sound"
    const val FIX_TITLE = "Your headphones' own fix"
    const val ABOUT_FIX = "Evens out what your exact earbuds or headphones get wrong, measured in a lab for that model. " +
        "Get the file free at autoeq.app: search your model, choose Equalizer APO as the app, download its file, then add it here. " +
        "It is kept for this output only, and works with the tuning and Strong mode."
    const val FIX_ADD = "Add the file"
    const val FIX_REMOVE = "Remove"
    const val FIX_REMOVED = "Removed."
    const val FIX_PICK = "Pick the autoeq.app file for your headphones"
    const val FIX_UNREADABLE = "Could not read that file."
    const val FIX_NO_FILTERS = "No filters found in that file. Pick the Equalizer APO file from autoeq.app."
    const val FIX_NOT_IN_PREVIEW = "The preview keeps nothing."
    const val FIX_COULD_NOT_SAVE = "Could not keep that file. Nothing changed."
    const val FIX_NO_OUTPUT = "No sound output found yet. Play something, then try again."
    const val FIX_SPEAKERS = "This is for earbuds and headphones. Plug them in or connect them first."
    fun fixDone(name: String, device: String) = "Done. Using $name for $device."
    fun fixNow(name: String?, device: String?) =
        if (name != null) "Using: $name (for ${device ?: "this output"})." else "No file yet for ${device ?: "this output"}."

    const val ABOUT_STRONG = "Gives earbuds and headphones the big laptop-speaker sound: a fuller body and clearer voices, " +
        "like Strong mode on the phone. The laptop speakers always have it. When it is off, earbuds get a gentle tune " +
        "that keeps the same loudness. Try each step with a song you know and keep the one you like."

    /** Strong mode's three steps as plain buttons (core StrongMode.LIGHT / BOLD / MAX), the phone's words. */
    fun strongStep(step: Int): String = when (step) {
        com.arnavagarwal.sound.core.StrongMode.LIGHT -> "Light"
        com.arnavagarwal.sound.core.StrongMode.MAX -> "Max"
        else -> "Bold"
    }

    /** Strong mode's status line, for the output in use. The preamp never goes above 0 dB on headphones (Engine). */
    fun strong(on: Boolean, step: Int, speaker: Boolean, tuningOn: Boolean): String = when {
        !on -> "Off. Turn it on for a bigger, fuller sound in earbuds and headphones."
        !tuningOn -> "On (${strongStep(step)}), but tuning is off: tap the big button above to hear it."
        speaker -> "On (${strongStep(step)}). It starts when earbuds or headphones are connected; the laptop speakers already have this sound."
        else -> "On (${strongStep(step)}): fuller and clearer in your earbuds or headphones."
    }

    fun quiet(on: Boolean, ready: Boolean, liftDb: Double): String = when {
        !on -> "Off. Turn it on so quiet listening keeps its bass and sparkle."
        !ready -> "On. It starts once the app has learned the volume you like (see Volume above)."
        liftDb < 0.3 -> "On. Nothing to add right now: you are listening at your usual volume."
        else -> "You are listening quieter than usual, and ears hear less bass and treble when it is quiet. " +
            "Adding back up to ${signed(liftDb)} dB of them."
    }

    /** Study music (LaptopSetting.voiceDownText): the singer turned down, the music kept. Buttons say what they do. */
    val STUDY_STEPS = listOf("Off" to 0.0, "Voice softer" to 6.0, "Voice much softer" to 12.0, "Voice almost gone" to 24.0)

    fun study(db: Double): String = when {
        db <= 0.0 -> "Off. Turn the singer's voice down so lyrics don't pull you away from studying. The music stays."
        db < 10 -> "The singer is a little softer. The music is as it was."
        db < 20 -> "The singer is much softer. The music is as it was."
        else -> "The singer is almost gone, like karaoke. Instruments in the middle of the song may dip a little too."
    }

    /** Karaoke card (Karaoke.kt). */
    const val KARAOKE_PICK = "Pick a song file"
    const val KARAOKE_OPEN = "Open the folder"

    /** 8D copy card (EightD.kt, tools/eightd/eightd.py). */
    fun eightD(s: EightD.State): String = when (s) {
        is EightD.State.Idle -> "Pick a song file and get an 8D copy: the music slowly circles around your head, once " +
            "every 12 seconds. Made for earphones; on speakers it only sways left and right. The bass stays steady in " +
            "the middle. Your song file is not changed."
        is EightD.State.Working -> "Making the 8D copy of \"${s.song}\"... about 10 seconds per minute of song."
        is EightD.State.Done -> "Done. \"${s.song} - 8D\" is saved next to your song. Put your earphones on."
        is EightD.State.Failed -> "That didn't work: ${s.why}."
    }

    fun karaoke(s: Karaoke.State): String = when (s) {
        is Karaoke.State.Idle -> "Pick a song file and get two new files: the music without the singer (for karaoke " +
            "or background music) and the singer alone. Your song file is not changed."
        is Karaoke.State.Working -> "Splitting \"${s.song}\" into music and voice... about a few seconds per minute of song."
        is Karaoke.State.Done -> "Done. \"${s.song} - music only\" and \"${s.song} - voice only\" are saved next to your song."
        is Karaoke.State.Failed -> "That didn't work: ${s.why}."
    }

    /** Clean-a-talk card (Denoise.kt). */
    const val DENOISE_PICK = "Pick a talk or video"
    const val DENOISE_CANCEL = "Cancel"
    val DENOISE_STEPS = listOf("Gentle" to Denoise.GENTLE_DB, "Stronger" to Denoise.STRONG_DB, "Voice only" to Denoise.VOICE_ONLY)

    fun denoise(s: Denoise.State): String = when (s) {
        is Denoise.State.Idle -> "For lectures, videos, voice notes - any file: fan hum, hiss, room noise and music are " +
            "taken down so the voice is clear. Your file is not changed; a cleaned copy is saved next to it. It only " +
            "runs when you pick a file - nothing is on in the background."
        is Denoise.State.Chosen -> "How strongly should \"${s.file.nameWithoutExtension}\" be cleaned? Gentle keeps " +
            "more of the room; Stronger takes more of the noise down; Voice only keeps the voice and nothing else."
        is Denoise.State.Working -> "Cleaning \"${s.name}\"... about a few seconds per minute."
        is Denoise.State.Done -> "Done. \"${s.file.name}\" is saved next to the original."
        is Denoise.State.Failed -> "That didn't work: ${s.why}."
    }

    /** The stereo width card (core AutoWidth). */
    fun width(on: Boolean, width: Double, songSideDb: Double?, kind: ContentKind?): String = when {
        !on -> "Off. Turn it on to spread songs wider than the laptop's close-together speakers can."
        songSideDb == null -> "On. Listening to the start of this song first."
        width > 1.0005 -> "This song is spread ${Math.round((width - 1) * 100)}% wider. The bass stays in the middle."
        kind == ContentKind.SPEECH || kind == ContentKind.CLASSICAL -> "On. Left as it is: talk and classical do not need it."
        songSideDb < com.arnavagarwal.sound.core.AutoWidth.MONO_DB -> "On. This song is almost the same on both sides, so there is nothing to spread."
        else -> "On. This song is already wide. Left as it is."
    }

    const val SLIDERS_HINT ="The app has already set these for this song. Move one only if you want it different."
    const val SLIDERS_HINT_OFF = "Tuning is off, so these are not used right now. They show what the app would set for this song."
    fun slidersHint(tuningOn: Boolean) = if (tuningOn) SLIDERS_HINT else SLIDERS_HINT_OFF
    fun sliderSource(yours: Double): String = if (kotlin.math.abs(yours) < 0.05) "set by the app" else "changed by you"

    /** Same licence line as the phone (app Words.TRUST_OWNER; WordsTest pins that they agree). */
    const val OWNER = "Made by Arnav Agarwal. Free to use, change and share for any non-commercial purpose " +
        "(PolyForm Noncommercial License 1.0.0). Selling it, or anything made from it, needs the owner's permission: " +
        "ask on GitHub, github.com/Arnav-Agarwal137."
    const val CLOSE_NOTE = "Closing this window keeps the tuning on. To stop it, right-click the Sound icon near the clock and choose Quit."

    /** A size without a sign: 2, 1.5. */
    fun amount(v: Double): String = signed(kotlin.math.abs(v)).removePrefix("+")

    fun signed(v: Double): String = when {
        kotlin.math.abs(v) < 0.05 -> "0"
        v == Math.floor(v) -> (if (v > 0) "+" else "") + v.toInt()
        else -> (if (v > 0) "+" else "") + String.format(java.util.Locale.ROOT, "%.1f", v)
    }
}
