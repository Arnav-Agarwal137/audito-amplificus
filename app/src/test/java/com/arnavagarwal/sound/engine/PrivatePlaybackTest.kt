package com.arnavagarwal.sound.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivatePlaybackTest {
    private val none = listOf<String?>(null, null, null)

    @Test fun privateOnlyBrowsersAreAlwaysPrivate() {
        assertTrue(PrivatePlayback.isPrivate("org.mozilla.focus", 1, listOf("Some song", null)))
        assertTrue(PrivatePlayback.isPrivate("org.torproject.torbrowser", null, none))
    }

    @Test fun anIncognitoTabIsPrivateAndANormalTabIsNot() {
        assertTrue(PrivatePlayback.isPrivate("com.android.chrome", 0, listOf("A site is playing media", null)))
        assertFalse(PrivatePlayback.isPrivate("com.android.chrome", 1, listOf("Lecture 4", "youtube.com")))
        assertTrue(PrivatePlayback.isPrivate("com.brave.browser", 1, listOf("Private tab", null)))
        assertTrue(PrivatePlayback.isPrivate("com.android.chrome", null, listOf(null, "Incognito tab")))
    }

    /** A music app that leaves the default (private) visibility must still be tuned: only browsers are judged by it. */
    @Test fun firefoxPrivateTabIsPrivate() {
        // as measured 7 Oct 2026: PUBLIC notification, placeholder in the title AND the session metadata
        assertTrue(PrivatePlayback.isPrivate("org.mozilla.firefox", 1, listOf("A site is playing media", null, null, "A site is playing media", "", null)))
        assertFalse(PrivatePlayback.isPrivate("org.mozilla.firefox", 1, listOf("Tone title", "Test", null, "Tone title", "Test", null)))
    }

    @Test fun musicAppsAreNeverSwitchedOffByMistake() {
        assertFalse(PrivatePlayback.isPrivate("com.spotify.music", 0, listOf("Private Eyes", "Hall & Oates")))
        assertFalse(PrivatePlayback.isPrivate("com.google.android.apps.youtube.music", 0, listOf("Incognito", "Some artist")))
        assertFalse(PrivatePlayback.isPrivate(null, -1, none))
    }

    /** Chrome starts the session before it posts the notification: that gap must not be measured. */
    @Test fun aBrowserWithNoNotificationYetIsPrivate() {
        assertTrue(PrivatePlayback.isPrivate("com.android.chrome", null, listOf("Some title", null)))
        assertFalse(PrivatePlayback.isPrivate("com.spotify.music", null, listOf("Some title", null)))
    }

    @Test fun aSecretNotificationIsPrivateForAnyApp() {
        assertTrue(PrivatePlayback.isPrivate("com.example.player", -1, none))
    }

    /** Brain stands down for it the same way as for a call, and the source hides what is playing. */
    @Test fun engineAndSourceAreWired() {
        val brain = java.io.File("src/main/java/com/arnavagarwal/sound/engine/Brain.kt").readText()
        assertTrue("NowPlayingSource.state.value.isPrivate" in brain && "if (inCall || inPrivate) return" in brain)
        val src = java.io.File("src/main/java/com/arnavagarwal/sound/engine/NowPlayingSource.kt").readText()
        assertTrue("PrivatePlayback.isPrivate(" in src && "if (priv) null else np" in src)
    }

    /** "don't mention it": no screen says a word about it. */
    @Test fun noScreenMentionsIt() {
        val ui = java.io.File("src/main/java/com/arnavagarwal/sound/ui").listFiles()!!.filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(Regex("incognito|private (tab|browsing|playback)", RegexOption.IGNORE_CASE).containsMatchIn(ui))
    }
}
