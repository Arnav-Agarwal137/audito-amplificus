package com.arnavagarwal.sound.ui

import com.arnavagarwal.sound.core.Challenge
import com.arnavagarwal.sound.core.ContentKind
import com.arnavagarwal.sound.core.DEFAULT_BAND_CUTOFFS
import com.arnavagarwal.sound.core.Strength

/**
 * Everything the screen SAYS, in plain words (his rule, 22 Sep: "all buttons tell in simple words what
 * they do"). Pure functions, so the wording is unit-tested rather than eyeballed.
 */
object Words {

    /** The three parts of the sound the cloud and the summary talk about, averaged in dB from the live bands. */
    data class Parts(val bass: Double, val voice: Double, val treble: Double)

    fun parts(bandsDb: DoubleArray, speaker: Boolean = false, cutoffs: DoubleArray = DEFAULT_BAND_CUTOFFS): Parts {
        fun avg(lo: Double, hi: Double): Double {
            val v = bandsDb.indices.filter { it < cutoffs.size && cutoffs[it] >= lo && cutoffs[it] <= hi }.map { bandsDb[it] }
            return if (v.isEmpty()) 0.0 else v.average()
        }
        // on the phone speaker the bass you can hear is its lowest playable notes (Speaker: the deep bass is taken out
        // and moved up there); reading the deep bands would call a bass lift "less boom"
        val bass = if (speaker) avg(181.0, 500.0) else avg(0.0, 180.0)
        return Parts(bass = bass, voice = avg(1_000.0, 4_000.0), treble = avg(5_600.0, 30_000.0))
    }

    /** One sentence saying what the app is doing to the sound right now. */
    fun summary(p: Parts, on: Boolean, playing: Boolean): String {
        if (!on) return "The magic is off. Your phone sounds exactly as it did before."
        if (!playing) return "Play something. The app will tune it for you."
        val bits = buildList {
            if (p.bass > T) add("more bass") else if (p.bass < -T) add("less boom")
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

    private val APPS = mapOf(
        "com.spotify.music" to "Spotify",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "com.google.android.youtube" to "YouTube",
        "com.jio.media.jiobeats" to "JioSaavn",
        "com.gaana" to "Gaana",
        "com.bsbportal.music" to "Wynk",
        "com.amazon.mp3" to "Amazon Music",
        "com.apple.android.music" to "Apple Music",
        "com.android.chrome" to "Chrome",
        "com.soundcloud.android" to "SoundCloud",
        "in.startv.hotstar" to "Hotstar",
        "com.netflix.mediaclient" to "Netflix",
        "com.google.android.apps.podcasts" to "Podcasts",
    )

    /** A package name as a person would say it: "com.spotify.music" -> "Spotify". */
    fun app(pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        APPS[pkg]?.let { return it }
        val last = pkg.split('.').lastOrNull { it.length > 2 && it !in setOf("android", "music", "app", "mobile") } ?: pkg
        return last.replaceFirstChar { it.uppercase() }
    }

    /** The one line in the always-on notification: what the app is doing, in plain words, no brand. */
    fun notificationLine(running: Boolean, testing: Boolean, playing: Boolean, kind: ContentKind?, tooLoud: Boolean): String = when {
        !running -> "Starting..."
        testing -> "Hear-the-difference test is running"
        !playing -> "On. Waiting for something to play."
        else -> "Tuning for " + (kind?.let { kind(it).replaceFirstChar(Char::lowercaseChar) } ?: "what is playing") + (if (tooLoud) ". A bit loud: turn it down a little." else "")
    }

    /** The notification's own on/off button, so he never has to leave the song to switch (his ask, 7 Oct 2026). */
    const val NOTE_TURN_OFF = "Turn off"
    const val NOTE_TURN_ON = "Turn on"
    /** The line left in the notification while the magic is off, beside the Turn on button. */
    const val NOTE_PAUSED = "Magic is off. Tap Turn on to start again."

    /** The voice-clarity bar's word for a 0..1 score ([com.arnavagarwal.sound.core.VoiceClarity]). */
    fun clarity(score: Double): String = when {
        score >= 0.65 -> "Clear"
        score >= 0.4 -> "A bit muffled"
        else -> "Muffled"
    }

    /** "How strong" as three plain buttons. */
    fun strength(level: Double): String = when (Strength.nearest(level)) {
        Strength.GENTLE -> "Gentle"
        Strength.STRONG -> "Strong"
        else -> "Normal"
    }

    /**
     * What the screen says after the challenge, and does: when the untouched sound won, the app turns its
     * strength down one step. [newStrength] is the level it moved to, or null if it was already at Gentle.
     */
    fun challengeAction(verdict: Challenge.Verdict, newStrength: Double?): String? = when {
        verdict != Challenge.Verdict.PREFERS_ORIGINAL -> null
        newStrength != null -> "So the app now tunes more gently: How strong is set to ${strength(newStrength)}. You can change it in Settings."
        else -> "How strong is already on Gentle. If you prefer the untouched sound everywhere, tap \"Turn tuning off\" on the home screen."
    }

    /** The app's own free song for "Hear the difference" when nothing plays (engine/DemoSong.kt). */
    const val DEMO_TITLE = "Our demo song"
    const val DEMO_ARTIST = "\"Beach Babes\" by SUPERARE - free to use (CC0), comes with the app"
    const val DEMO_HOME_HINT = "Nothing is playing right now. Hear what the app does on its own free demo song: two versions at the same loudness, and you pick the one you like."
    const val DEMO_BUTTON = "Try it on our demo song"
    const val DEMO_TEST_HINT = "Nothing is playing, so the test uses the app's own free demo song. Earbuds make the difference easiest to hear."

    /** Above the sliders: they start where the app put them, and moving them is optional. */
    const val SLIDERS_HINT = "The app has already set these for this song. Move one only if you want it different. Only this song changes."

    /** Beside each slider's number: whose setting it is now. */
    fun sliderSource(yours: Double): String = if (kotlin.math.abs(yours) < 0.05) "set by the app" else "changed by you"

    /** What Density does to the song: the laptop's card text word for word (desktop Words.ABOUT_DENSITY). */
    const val ABOUT_DENSITY = "Makes the song feel fuller and bigger. The app raises the whole song, and a limiter " +
        "(an automatic ceiling) stops the loudest hits from going any higher. So the quiet moments, like a soft " +
        "verse or a fading note, come up closer to the loud ones. That packed-together sound is the density."

    /** The density card's status line (Brain.steerDensity): what it is doing now, or why it is not playing. */
    fun density(on: Boolean, live: Boolean, speaker: Boolean, db: Double = 0.0, capDb: Double = 0.0): String = when {
        !on -> "Off. Turn it on to make songs on the phone's speaker sound fuller."
        live && capDb < 0.05 -> "Auto: none for this kind of music. Its loud and soft moments are part of it."
        live -> "Auto: ${signed(db)} dB for this song, chosen by the app (up to ${signed(capDb)} for this kind). " +
            "Quiet parts are lifted and loud parts held, so the song sounds fuller and carries further."
        !speaker -> "On, but it only plays on the phone's own speaker."
        else -> "On. It starts when the app is tuning a song on the phone's speaker."
    }

    /** Strong mode (core StrongMode, his tap 9 Oct 2026): what it does, always shown on its card. */
    const val ABOUT_STRONG = "Makes the sound bigger, like the laptop app: a fuller body and clearer voices, and " +
        "a bit louder too, on the speaker and in earbuds. When it is off, the app tunes the sound without making it louder. " +
        "Try each step with a song you know and keep the one you like."

    /** Strong mode's three steps as plain buttons (core StrongMode.LIGHT / BOLD / MAX). */
    fun strongStep(step: Int): String = when (step) {
        com.arnavagarwal.sound.core.StrongMode.LIGHT -> "Light"
        com.arnavagarwal.sound.core.StrongMode.MAX -> "Max"
        else -> "Bold"
    }

    /** Strong mode's status line: what it is doing on the output in use. Earbuds never get louder (hearing). */
    fun strong(on: Boolean, step: Int, speaker: Boolean): String {
        val louder = when (step) {
            com.arnavagarwal.sound.core.StrongMode.LIGHT -> "a little louder"
            com.arnavagarwal.sound.core.StrongMode.MAX -> "much louder"
            else -> "louder"
        }
        return when {
            !on -> "Off. Turn it on for a bigger, fuller sound."
            speaker -> "On (${strongStep(step)}): fuller and clearer, and $louder on the phone's speaker. A limiter " +
                "(an automatic ceiling) stops the loudest moments from going any higher."
            else -> "On (${strongStep(step)}): fuller and clearer, and $louder in your earbuds or headphones. " +
                "Keep the phone's volume where it is comfortable: louder in the ears for long can harm hearing."
        }
    }

    /**
     * The 8D copy card (engine EightDMaker). Idle and Failed are the laptop's words (desktop Words.eightD); the laptop
     * saves "next to your song" and quotes its speed, the phone saves to the Music folder and shows how far it is.
     */
    const val EIGHTD_PICK = "Pick a song file"
    const val EIGHTD_PLAY = "Play it"
    fun eightD(s: com.arnavagarwal.sound.engine.EightDMaker.State, musicFolder: Boolean = true): String = when (s) {
        is com.arnavagarwal.sound.engine.EightDMaker.State.Idle -> "Pick a song file and get an 8D copy: the music slowly circles around your head, once " +
            "every 12 seconds. Made for earphones; on speakers it only sways left and right. The bass stays steady in " +
            "the middle. Your song file is not changed."
        is com.arnavagarwal.sound.engine.EightDMaker.State.Working ->
            "Making the 8D copy of \"${s.song}\"... ${(s.progress * 100).toInt()}%. Keep the app open."
        is com.arnavagarwal.sound.engine.EightDMaker.State.Done -> "Done. \"${s.saved}\" is saved in " +
            (if (musicFolder) "your Music folder" else "the app's music folder") + ". Put your earphones on."
        is com.arnavagarwal.sound.engine.EightDMaker.State.Failed -> "That didn't work: ${s.why}."
    }

    /** Ears card, earbuds in, warnings not allowed yet (Setup.LATER). */
    const val WARN_ASK = "Get a warning when your ears have had too much loud sound this week."
    const val WARN_BUTTON = "Warn me"

    /** The big on/off button: the laptop's words (WordsTest reads the desktop source). */
    fun switchTitle(on: Boolean) = if (on) "Audito Amplificus" else "Audito Amplificus"
    fun switchAction(on: Boolean) = if (on) "The magic is on. Tap to hear it plain" else "Magic is off. Tap to cast it"

    /** Warmth slider (core Tone's 300 Hz peak, the same filter as the laptop's): the laptop's words, word for word. */
    const val ABOUT_WARMTH = "Body and fullness: the low-middle pitches around 300 Hz, where the thick part of a " +
        "voice, guitar and piano sits. Up sounds richer and cosier; too much sounds muddy, down sounds thin."

    /**
     * Quiet listening (core EqualLoudness.compensation from the phone's loudness estimate). The laptop's name and its
     * first sentence; the rest says the PHONE's rule: it follows how loud the phone plays, not "quieter than usual".
     */
    val ABOUT_QUIET = "When sound is quiet, your ears stop hearing the deep notes first (and a little of the " +
        "bright top), so music turns thin. When the phone plays quietly, this adds that low end and a touch of treble " +
        "back, up to ${com.arnavagarwal.sound.core.EqualLoudness.MAX_BOOST_DB.toInt()} dB, so the song sounds the " +
        "same, just softer. The lower the volume, the more it adds; when it is loud it does nothing."

    fun quiet(on: Boolean): String =
        if (!on) "Off. Turn it on so quiet listening keeps its bass and sparkle."
        else "On. It adds bass and treble back whenever the phone is playing quietly."

    /**
     * Study music (core StudyMusic). NOT the laptop's words: the laptop cuts only the middle of the stereo picture and
     * "leaves the instruments"; the phone cuts the whole song, so it must say that instruments in the band soften too.
     */
    const val ABOUT_STUDY = "Turns the singer down so the words stop pulling your attention while you study. It lowers " +
        "the band of pitches where voices sit, then gives the loudness back, so the song is not quieter. On the phone " +
        "the whole song goes through this, so instruments in the same band, like a guitar or a piano, go down with " +
        "the voice. The beat, the bass and the shine stay."

    /** The status line for each step (core StudyMusic.STEPS). The laptop's lines (desktop Words.study), except that the
     *  laptop's "The music is as it was" is not true on the phone: there the instruments in the band go down too. */
    fun study(db: Double): String = when {
        db <= 0.0 -> "Off. Turn the singer's voice down so lyrics don't pull you away from studying. The music stays."
        db < 10 -> "The singer is a little softer. Guitars and pianos in the same band soften a little too."
        db < 20 -> "The singer is much softer. Guitars and pianos in the same band are softer too."
        else -> "The singer is almost gone. Guitars and pianos in the same band go down with it; the beat and the bass stay."
    }

    /** The Volume card (core VolumeCoach): the laptop's words, with the phone's volume instead of Windows'. */
    const val VOLUME_BUTTON = "Set it for me"
    const val ABOUT_VOLUME = "Every song is released at its own loudness, so at the same volume one song sounds weak " +
        "and the next one blasts. The app learns the volume you usually leave songs at, compares each new song to it, " +
        "and tells you whether to turn this one up or down. \"$VOLUME_BUTTON\" moves the phone's volume for you."
    /** Said under the Volume card on earbuds only: Brain caps the coach at the hearing-safe volume. */
    // legal check 29 Sep: never an absolute safety promise; the figure is an estimate (research/legal-check.md)
    const val VOLUME_EARBUDS = "On earbuds it keeps its suggestions inside the WHO safe-listening limit, as far as it can " +
        "estimate it from the phone's volume."

    /** The Volume card's status line (desktop Words.volume, word for word). */
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

    fun signed(v: Double): String = when {
        kotlin.math.abs(v) < 0.05 -> "0"
        else -> "%+.1f".format(v).replace("-", "−")
    }

    /** Settings > "Privacy, safety and credits" (research/legal-check.md, 29 Sep). Plain facts, no promises. */
    const val TRUST_TITLE = "Privacy, safety and credits"
    const val TRUST_PRIVACY = "Nothing leaves your phone. The app has no internet access at all: it never asks Android " +
        "for it, so it cannot send anything anywhere. It reads the name of the song that is playing and measures the sound " +
        "on its way to your earbuds. It never records you, and it keeps only your settings and what it learned, on this phone."
    // CDSCO medical-device-software guidance: general wellness stays out of MDR-2017 only while it names no disease
    // or disorder, so this says what it is (a listening guide) and names no symptom (research/legal-check.md)
    const val TRUST_SAFETY = "Ears this week and the volume advice are a general listening guide, after the WHO / ITU " +
        "safe-listening guidance (ITU-T H.870). They are estimates from your phone's volume and typical earbuds. The app " +
        "is not a medical device, not a hearing test and not medical advice. For anything about your hearing, see a doctor."
    const val TRUST_AS_IS = "Audito Amplificus is free and comes as it is, without any guarantee. You choose how loud " +
        "you listen; keep the volume where it is comfortable."
    const val TRUST_COPIES = "8D copies are for songs you own, for your own listening. Sharing a changed copy of " +
        "someone else's song can break copyright."
    val TRUST_CREDITS = listOf(
        "Font: Inter Tight by The Inter Project Authors, SIL Open Font License 1.1.",
        "8D sound: MIT KEMAR head measurements by Bill Gardner and Keith Martin, MIT Media Lab.",
        "Demo song: \"Beach Babes\" by SUPERARE, public domain (CC0).",
        "Built with Kotlin, Jetpack Compose and AndroidX, Apache License 2.0.",
        "Earbud files: AutoEq (autoeq.app) by Jaakko Pasanen. You download them yourself; nothing of AutoEq comes with the app.",
        "Audito Amplificus is an independent app. It is not connected to any music service, earbud maker, book, film or game.",
    )
    /** The licence, his words 8 Oct 2026: "let them use it n change, the owner is still me, and if they wanna sell it they
     *  have to contact me" -> PolyForm Noncommercial 1.0.0 (assets/licenses). Contact is GitHub only (his choice). */
    const val TRUST_OWNER = "Made by Arnav Agarwal. Free to use, change and share for any non-commercial purpose " +
        "(PolyForm Noncommercial License 1.0.0). Selling it, or anything made from it, needs the owner's permission: " +
        "ask on GitHub, github.com/Arnav-Agarwal137."
    const val TRUST_LICENCES = "Read the licences"
}
