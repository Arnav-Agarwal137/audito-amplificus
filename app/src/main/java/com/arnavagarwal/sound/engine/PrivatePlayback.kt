package com.arnavagarwal.sound.engine

/**
 * Private playback is never touched (his ask, 30 Sep: do not tune sound from incognito or private browsing, "that may
 * break the trust"). While it plays, the engine stands down exactly as for a call (Brain.tick): the meter stops, the
 * global chain is switched off, and nothing about it is shown, remembered or counted.
 *
 * Signals, any one is enough:
 * - a browser that has only private tabs (Firefox Focus / Klar, DuckDuckGo, Tor Browser);
 * - a browser whose media notification is not public: Chrome and its relatives mark an incognito tab's media
 *   notification private and replace its title with a placeholder (chromium "Hide media metadata from media
 *   controllers if required"). Only browsers are judged by this, because ordinary music apps may leave the default
 *   visibility, and judging them would switch the tuning off for no reason;
 * - any app whose media notification is SECRET (it asked not to be shown on the lock screen at all);
 * - a browser's media text that names incognito or private browsing.
 * Visibility numbers are android.app.Notification's: SECRET -1, PRIVATE 0, PUBLIC 1.
 */
object PrivatePlayback {
    /** Browsers that never keep history: every sound from them is private. */
    val PRIVATE_ONLY = setOf(
        "org.mozilla.focus", "org.mozilla.klar", "com.duckduckgo.mobile.android",
        "org.torproject.torbrowser", "org.torproject.torbrowser_alpha",
    )

    /** Browsers with a private mode, judged by their media notification. */
    val BROWSERS = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "org.chromium.chrome",
        "com.brave.browser", "com.brave.browser_beta", "com.microsoft.emmx", "com.opera.browser", "com.opera.mini.native",
        "com.opera.gx", "com.vivaldi.browser", "com.kiwibrowser.browser", "com.sec.android.app.sbrowser",
        "com.vivo.browser", "com.heytap.browser", "com.mi.globalbrowser", "com.UCMobile.intl", "org.mozilla.firefox",
        "org.mozilla.firefox_beta", "org.mozilla.fenix", "com.yandex.browser", "mark.via.gp", "com.cloudmosa.puffinFree",
    )

    private const val SECRET = -1
    private const val PUBLIC = 1
    // "A site is playing media" is the placeholder Chrome AND Firefox put in place of a private tab's song name.
    // Firefox marks that notification PUBLIC, so the placeholder is its only sign (measured 7 Oct 2026, E-033).
    private val WORDS = Regex("incognito|private (tab|browsing|window|mode)|inprivate|secret mode|a site is playing media", RegexOption.IGNORE_CASE)

    /**
     * [visibility] = the playing app's media notification visibility, or null when there is none to read.
     * [texts] = the media title/artist/album and that notification's own title/text lines.
     */
    fun isPrivate(pkg: String?, visibility: Int?, texts: List<String?>): Boolean {
        if (pkg == null) return false
        if (pkg in PRIVATE_ONLY) return true
        if (visibility != null && visibility <= SECRET) return true
        if (pkg !in BROWSERS) return false
        // a browser's media session starts a moment before its notification is posted; until that notification can
        // be read, nobody knows whether the tab is incognito, so it counts as private (measured on Chrome, 30 Sep:
        // otherwise the first second of an incognito tab was measured). onNotificationPosted re-judges it.
        if (visibility == null || visibility < PUBLIC) return true
        return texts.any { it != null && WORDS.containsMatchIn(it) }
    }
}
