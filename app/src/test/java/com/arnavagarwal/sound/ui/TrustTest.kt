package com.arnavagarwal.sound.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The legal check (research/legal-check.md, 29 Sep): everything the app ships that someone else made is credited
 * in Settings, the licence texts it names really ship, and no screen promises safety it can only estimate.
 */
class TrustTest {
    private val credits = Words.TRUST_CREDITS.joinToString("\n")

    @Test fun everyBundledThirdPartyThingIsCredited() {
        // what ships: the fonts (res/font), the demo song (res/raw), the 8D head measurements (core eightd_kit.bin)
        val fonts = File("src/main/res/font").listFiles()!!.map { it.name }
        assertTrue(fonts.isNotEmpty() && fonts.all { it.startsWith("inter_tight") }, "a new font needs its own credit: $fonts")
        assertTrue("Inter Tight" in credits && "Open Font License" in credits)
        val raw = File("src/main/res/raw").listFiles()!!.map { it.name }
        assertTrue(raw == listOf("demo_song.ogg"), "new sound in res/raw needs a credit: $raw")
        assertTrue("SUPERARE" in credits && "CC0" in credits)
        assertTrue(File("../core/src/main/resources/eightd_kit.bin").exists())
        assertTrue("KEMAR" in credits && "Gardner" in credits && "Martin" in credits)
        assertTrue("Apache License 2.0" in credits)
    }

    @Test fun theLicenceTextsItNamesShip() {
        val ofl = File("src/main/assets/licenses/ofl-1.1.txt").readText()
        val apache = File("src/main/assets/licenses/apache-2.0.txt").readText()
        assertTrue("SIL OPEN FONT LICENSE Version 1.1" in ofl && "Inter Project Authors" in ofl)
        assertTrue("Apache License" in apache && "Version 2.0" in apache && apache.length > 9000)
    }

    @Test fun noAbsoluteSafetyPromise() {
        val said = listOf(Words.VOLUME_EARBUDS, Words.TRUST_SAFETY, Words.TRUST_PRIVACY).joinToString(" ")
        assertFalse(Regex("never suggests more than is safe|guarantee|protects your hearing|cures?", RegexOption.IGNORE_CASE).containsMatchIn(said))
        assertTrue("not medical advice" in Words.TRUST_SAFETY && "estimate" in Words.VOLUME_EARBUDS)
    }

    @Test fun wellnessWordingNamesNoDiseaseAnywhereOnScreen() {
        // CDSCO: general-wellness software must make no reference to diseases, disorders or pathological conditions.
        // Scans every quoted string on the screens (Words.kt, Setup.kt, MainActivity.kt), not a list we remember.
        val screens = listOf("Words.kt", "Setup.kt", "MainActivity.kt").joinToString("\n") { f ->
            File("src/main/java/com/arnavagarwal/sound/ui/$f").readLines()
                .filterNot { l -> l.trimStart().startsWith("//") || l.trimStart().startsWith("*") }.joinToString("\n")
        }
        val quoted = Regex(""""([^"\\]|\\.)*"""").findAll(screens).joinToString(" ") { it.value }
        assertTrue(quoted.length > 5000, "the scan found almost no screen text (${quoted.length} chars): the scanner is broken")
        val bad = Regex("""tinnitus|hearing loss|deaf|disease|disorder|diagnos|treatment|\bcures?\b|ringing""", RegexOption.IGNORE_CASE)
            .findAll(quoted).map { it.value }.toList()
        assertTrue(bad.isEmpty(), "disease words on screen: $bad")
        assertTrue("not a medical device" in Words.TRUST_SAFETY && "without any guarantee" in Words.TRUST_AS_IS)
    }


    @Test fun thePrivacyLineIsTrueOfTheManifest() {
        // "no internet access at all" is a claim about the manifest: adding INTERNET must turn this red
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("android.permission.INTERNET" in manifest)
        assertTrue("no internet access" in Words.TRUST_PRIVACY)
        // "Nothing leaves your phone": Android Auto Backup would copy the app's data to the user's Google Drive by
        // itself, without the app needing INTERNET (allowBackup was "true" until 8 Oct 2026)
        assertTrue("Nothing leaves your phone" in Words.TRUST_PRIVACY)
        assertTrue(Regex("""android:allowBackup="false"""").containsMatchIn(manifest), "Auto Backup would send data off the phone")
    }

    @Test fun theGreenTickIsTrueOfTheCode() {
        // "Your microphone is never used" / "No sound is recorded or saved" are claims about the code: anything that opens
        // the microphone or writes audio must turn this red. Scans every source file, not a list we remember.
        val src = File("src/main/java").walkTopDown().filter { it.extension in setOf("kt", "java") }.toList()
        assertTrue(src.size >= 15, "the scan found only ${src.size} files: the scanner is broken")
        val code = src.joinToString("\n") { it.readText() }
        val mic = Regex("""\bAudioRecord\b|\bMediaRecorder\b|AudioSource\.(MIC|VOICE_\w+|CAMCORDER|UNPROCESSED)|\bAudioPlaybackCapture""")
            .findAll(code).map { it.value }.toList()
        assertTrue(mic.isEmpty(), "the green tick says the microphone is never used, but the code has: $mic")
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("android.permission.INTERNET" in manifest)
        val said = (listOf(Setup.SAFE_TITLE) + Setup.SAFE_LINES).joinToString(" ")
        assertTrue("microphone is never used" in said && "No internet" in said && "recorded or saved" in said)
        assertFalse(Regex("guarantee|completely|100%", RegexOption.IGNORE_CASE).containsMatchIn(said), "no absolute promise: $said")
    }
}
