package com.arnavagarwal.sound.ui

/**
 * The first-run setup: one permission per page, each saying in plain words what it is FOR and what the
 * app will never do with it (RULES.md 22 Sep: plain words, simple, one main thing per screen).
 * Pure logic, so the order, the skipping and the wording are unit-tested rather than eyeballed.
 */
object Setup {

    enum class Step { WELCOME, SEE, MEASURE, EARBUDS, WARN, BACKGROUND, DONE }

    /** What the phone has already granted. A granted step is never shown again. */
    data class Have(
        val see: Boolean = false,
        val measure: Boolean = false,
        val earbuds: Boolean = false,
        val warn: Boolean = false,
        val background: Boolean = false,
    )

    /** Android has no such permission before this version: earbuds' name needs 12+, notifications 13+. */
    fun exists(step: Step, sdk: Int): Boolean = when (step) {
        Step.EARBUDS -> sdk >= 31
        Step.WARN -> sdk >= 33
        else -> true
    }

    /**
     * Every allowance, in the order the ONE Allow button runs them (his words, 30 Sep: "keep the allowing continuously
     * ... make it at once he need not commit again and again", reversing the 29 Sep "ask later on the home screen").
     * Android's own boxes first, all in one go; then the battery box; last the one switch Android keeps on a page.
     */
    val CHAIN = listOf(Step.MEASURE, Step.EARBUDS, Step.WARN, Step.BACKGROUND, Step.SEE)

    /** What the Allow button still has to ask on this phone. Empty = nothing left, the home card hides. */
    fun chain(have: Have, sdk: Int): List<Step> = CHAIN.filter { exists(it, sdk) && !granted(it, have) }

    /** The one asking page: what will pop up, in the order it pops up, so nothing is a surprise. */
    /** The one-tap page's words. The Play build adds the battery list, which Android does not close by itself. */
    fun allText(play: Boolean): String =
        "Tap Allow. A few boxes come up one after another: tap Allow in each. " +
        (if (play) "Then a battery list opens: pick \"All apps\" if it is there, tap Amplificus, choose \"Don't optimise\" (newer phones: switch on \"Allow background usage\") and come back. " else "") +
        "Last, Android opens a page: switch on \"Allow notification access\" and tap Allow. The app comes back by itself."

    val ALL: Page by lazy { Page(
        "One tap to set it up",
        allText(com.arnavagarwal.sound.BuildConfig.PLAY_STORE),
        "Allow",
        "If that switch is grey and says \"restricted setting\", tap \"Open App info\", then the ⋮ menu at the top, then \"Allow restricted settings\", and try again.",
        promises = SEE_PROMISES + MEASURE_PROMISES) } // lazy: the promises are declared further down

    /**
     * The promise shown in big letters right above every Allow button (his words, 30 Sep: "very very big font ... we
     * do not take anything off of your phone ... the owner itself doesn't get to know what you are doing", and in
     * smaller words under it "if ... you feel that we are stealing your info please go ahead and uninstall").
     * True only while the app has no internet permission (TrustTest pins the manifest).
     */
    const val PRIVACY_BIG = "Nothing leaves your phone."
    const val PRIVACY_BODY = "Everything stays on your phone. Not even we, the makers, can see what you play or do. Your privacy stays untouched."
    const val PRIVACY_SMALL = "If at any time you do not trust us, or feel we are taking your information, simply uninstall the app."

    /**
     * The green tick on the home screen, always there (his ask, 8 Oct: "a green tick kind of a thing, telling me ... we do
     * not use any of your sound or your microphone"). Each line must stay TRUE: the microphone is never opened (the
     * Visualizer reads the output mix, session 0), but the app DOES measure what is playing - that is how it tunes - so it
     * says "nothing is recorded", never "does not use your sound". The laptop says the same (desktop Words.SAFE_*).
     */
    const val SAFE_TITLE = "Private and safe"
    val SAFE_LINES = listOf(
        "Your microphone is never used. The app never hears you.",
        "No sound is recorded or saved.",
        "No internet. Nothing leaves your phone.",
    )

    /** Pages the first-run setup shows on this Android version. */
    fun applies(step: Step, sdk: Int): Boolean = exists(step, sdk)

    fun granted(step: Step, have: Have): Boolean = when (step) {
        Step.SEE -> have.see
        Step.MEASURE -> have.measure
        Step.EARBUDS -> have.earbuds
        Step.WARN -> have.warn
        Step.BACKGROUND -> have.background
        Step.WELCOME, Step.DONE -> false
    }

    /** The next page to show after [after]: the first later step that applies here and is not granted yet. */
    fun next(after: Step, have: Have, sdk: Int): Step =
        Step.entries.drop(after.ordinal + 1).firstOrNull { it == Step.DONE || (applies(it, sdk) && !granted(it, have)) } ?: Step.DONE

    /** The asking pages that will actually be shown, for "Step 2 of 4". */
    fun asks(sdk: Int): List<Step> = Step.entries.filter { it != Step.WELCOME && it != Step.DONE && applies(it, sdk) }

    /**
     * One setup page. [promises] are drawn as green ticks (his ask, 22 Sep: "make the safety point clearer like
     * a green tick"). [path] is the "do this -> then this" line at the bottom, naming the exact buttons; its
     * first chip always names this page's own button ([allow]).
     */
    data class Page(
        val title: String, val body: String, val allow: String, val help: String? = null,
        val promises: List<String> = emptyList(), val path: List<String> = emptyList(),
    )

    /** The promises, shared with the home screen's "quick steps" card so both always say the same thing. */
    val SEE_PROMISES = listOf("Reads only the name of what is playing", "Never reads your messages")
    val MEASURE_PROMISES = listOf(
        "The app never hears you. Nothing you say is used.",
        "It only measures the sound your phone is PLAYING.",
        "Nothing is recorded, saved or sent.",
    )

    /** The Play build has no one-tap battery box (Google policy 9888379 does not list sound apps): it opens the
     *  battery list instead, so the steps name the list. Same feature, two more taps (31 Oct 2026). */
    val BACKGROUND_PATH_BOX = listOf("Tap \"Allow\"", "Tap \"Allow\" in the box", "Come back here")
    val BACKGROUND_PATH_LIST = listOf("Tap \"Allow\"", "Pick \"All apps\" if shown, find Amplificus", "Choose \"Don't optimise\" or switch on \"Allow background usage\"", "Come back here")

    fun page(step: Step, play: Boolean = com.arnavagarwal.sound.BuildConfig.PLAY_STORE): Page = when (step) {
        Step.WELCOME -> Page(
            "A little magic for every sound",
            "Play anything: music, a lecture, a film. The app works out what kind of sound it is and tunes it for you while it plays. Set it up once. It takes about 30 seconds.",
            "Set it up")
        Step.SEE -> Page(
            "Let the app see what is playing",
            "So it knows if it is a song, a lecture or a film, and which song.",
            "Open the setting",
            "If the switch is grey and says \"restricted setting\", tap \"Open App info\", then the ⋮ menu at the top, then \"Allow restricted settings\", and try again.",
            promises = SEE_PROMISES,
            path = listOf("Tap \"Open the setting\"", "Switch on \"Allow notification access\"", "Tap \"Allow\"", "Come back here"))
        Step.MEASURE -> Page(
            "Let the app measure the sound",
            "Android calls this the microphone permission, because it is the only key Android has for measuring sound. The app uses it only to measure the music, talk or film your phone is playing, from bass to treble.",
            "Allow",
            promises = MEASURE_PROMISES,
            path = listOf("Tap \"Allow\"", "In the box, tap \"While using the app\""))
        Step.EARBUDS -> Page(
            "Let the app know your earbuds",
            "So it can keep the right sound for each pair of earbuds and speakers, and give volume advice.",
            "Allow",
            promises = listOf("Reads only their name"),
            path = listOf("Tap \"Allow\"", "Tap \"Allow\" in the box"))
        Step.WARN -> Page(
            "Let the app warn you",
            "One quiet line while it works, so you can always see it and switch it off. And a warning if your ears have had too much loud sound this week.",
            "Allow",
            path = listOf("Tap \"Allow\"", "Tap \"Allow\" in the box"))
        Step.BACKGROUND -> Page(
            "Keep working when the screen is off",
            "Some phones stop apps to save battery, and the sound would go back to plain. This lets the app keep tuning while your phone is in your pocket.",
            "Allow",
            path = if (play) BACKGROUND_PATH_LIST else BACKGROUND_PATH_BOX)
        Step.DONE -> Page(
            "The spell is ready",
            "Play something. The app will tune it on its own. You can change anything later in Settings.",
            "Turn tuning on")
    }

    /** What each skipped step means in practice, for the last page. Null = nothing lost. */
    fun missingSays(step: Step): String? = when (step) {
        Step.SEE -> "Without \"see what is playing\", every sound gets the same tuning."
        Step.MEASURE -> "Without \"measure the sound\", the app cannot tune or give volume advice."
        Step.EARBUDS -> "Without \"know your earbuds\", all earbuds share one sound."
        Step.WARN -> "Without \"warn you\", you get no loud-sound warnings."
        Step.BACKGROUND -> "The phone may stop the app when the screen is off."
        Step.WELCOME, Step.DONE -> null
    }

    /** Android refuses to ask a second time after two "Don't allow"s; the only way left is the app's own settings page. */
    const val BLOCKED = "Android will not ask again. Tap below, then Permissions, and turn it on there."
    /** The path when Android has stopped asking: the switch is only in the app's own settings page now. */
    val BLOCKED_PATH = listOf("Tap \"Open App info\"", "Tap \"Permissions\"", "Choose the one that says \"Not allowed\"", "Choose \"Allow\"", "Come back here")
    const val OPEN_APP_SETTINGS = "Open App info"
    const val NOT_NOW = "Not now"

    // ---- the maker's own battery switch (his ask, 22 Sep: "add a pic ... the battery step ... it's confusing") ----
    /** Vivo and iQOO add a second battery switch on top of Android's; the picture is only shown on those phones. */
    fun hasMakerBatterySwitch(manufacturer: String?): Boolean = manufacturer?.lowercase()?.trim() in setOf("vivo", "iqoo")
    const val MAKER_SWITCH_TITLE = "Your phone has one more switch"
    const val MAKER_SWITCH_HINT = "After the box above, turn this on too, or your phone may still stop the app. The words can differ a little on your phone."
    const val OPEN_BATTERY = "Open Battery settings"
    const val MAKER_DONE = "I did this, hide it"
    val MAKER_SWITCH_PATH = listOf("Tap \"Open Battery settings\"", "Tap \"Background power consumption\"", "Find \"Audito Amplificus\"", "Turn on \"Allow\"", "Come back here")

    /** One small screen of the picture: which row of the list is lit, what it says underneath, and whether a switch shows on. */
    data class Screen(val caption: String, val litRow: Int, val switchOn: Boolean = false)
    val MAKER_SWITCH_SCREENS = listOf(
        Screen("Battery", litRow = 1),
        Screen("Background power consumption", litRow = 3),
        Screen("Audito Amplificus: Allow", litRow = 2, switchOn = true),
    )
}
