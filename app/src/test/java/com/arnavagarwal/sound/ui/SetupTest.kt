package com.arnavagarwal.sound.ui

import com.arnavagarwal.sound.core.ContentKind
import com.arnavagarwal.sound.ui.Setup.Step
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupTest {
    private val none = Setup.Have()
    private val all = Setup.Have(see = true, measure = true, earbuds = true, warn = true, background = true)

    /** Walk the whole flow the way the screen does, pressing "Not now" on every page. */
    private fun walk(have: Setup.Have, sdk: Int): List<Step> {
        val seen = mutableListOf(Step.WELCOME)
        while (seen.last() != Step.DONE) { seen += Setup.next(seen.last(), have, sdk); check(seen.size < 20) }
        return seen
    }

    @Test fun oneAllowButtonAsksEverythingInOrder() {
        // his words 30 Sep: "keep the allowing continuously ... make it at once": boxes first, then battery, then the page
        assertEquals(listOf(Step.MEASURE, Step.EARBUDS, Step.WARN, Step.BACKGROUND, Step.SEE), Setup.chain(none, 36))
    }

    @Test fun olderAndroidSkipsPermissionsItDoesNotHave() {
        assertEquals(listOf(Step.MEASURE, Step.BACKGROUND, Step.SEE), Setup.chain(none, 30))
        assertEquals(listOf(Step.MEASURE, Step.EARBUDS, Step.BACKGROUND, Step.SEE), Setup.chain(none, 32))
    }

    @Test fun grantedStepsAreNeverAskedAgain() {
        assertEquals(emptyList(), Setup.chain(all, 36))
        assertEquals(listOf(Step.BACKGROUND), Setup.chain(all.copy(background = false), 36))
    }

    /** No ask lives on its own card any more: setup and the home card both run the one chain, nothing else asks. */
    @Test fun setupAndHomeBothUseTheOneButton() {
        val src = File("src/main/java/com/arnavagarwal/sound/ui/MainActivity.kt").readText()
        val home = src.substringAfter("private fun Home(").substringBefore("private fun ClarityBar(")
        val setup = src.substringAfter("private fun SetupFlow(").substringBefore("private fun Root(")
        assertTrue("allowAll" in home && "allowAll()" in setup, "a screen does not use the one Allow button")
        assertTrue("PrivacyPromise()" in home && "PrivacyPromise()" in setup, "an Allow button has no privacy promise above it (his ask, 30 Sep)")
        for (s in listOf("Setup.Step.EARBUDS", "Setup.Step.BACKGROUND", "BatteryPicture(", "MAKER_SWITCH_PATH", "WARN_BUTTON"))
            assertFalse(s in home, "$s is still asked on its own on the home screen")
        assertEquals(Setup.CHAIN.toSet(), Step.entries.toSet() - Step.WELCOME - Step.DONE, "an allowance is missing from the chain")
    }

    @Test fun everyAskingPageSaysWhatIsLostIfSkipped() {
        for (s in Setup.asks(36)) assertTrue(Setup.missingSays(s) != null, "$s has no skip explanation")
    }

    @Test fun measurePageSaysItNeverHearsYouAsAGreenTick() {
        val p = Setup.page(Step.MEASURE)
        assertTrue("microphone" in p.body, p.body)
        // his words, 22 Sep: "tell that we don't use your mic audio too soo ntg you speak we hear" - as a tick, not in the paragraph
        assertTrue(p.promises.any { "never hears you" in it && "Nothing you say" in it }, p.promises.toString())
        assertTrue(p.promises.any { "recorded" in it && "sent" in it }, p.promises.toString())
        assertEquals(Setup.MEASURE_PROMISES, p.promises) // the home card shows the same list
    }

    @Test fun everyAskingPageEndsWithADoThisThenThisPath() {
        for (s in Setup.asks(36)) {
            val p = Setup.page(s)
            assertTrue(p.path.size >= 2, "$s has no do-this-then-this path")
            // the first step names the very button on the page, in quotes, so the path starts where the person is
            assertTrue("\"${p.allow}\"" in p.path.first(), "$s path starts \"${p.path.first()}\", not with its button \"${p.allow}\"")
        }
        assertTrue("\"${Setup.OPEN_APP_SETTINGS}\"" in Setup.BLOCKED_PATH.first())
        assertTrue(Setup.page(Step.WELCOME).path.isEmpty() && Setup.page(Step.DONE).path.isEmpty())
    }

    // his test, 22 Sep: the battery step was "confusing a little" -> one box with Allow, plus a picture on Vivo / iQOO
    @Test fun batteryStepIsOneTapAndTheMakerPictureMatchesItsPath() {
        assertEquals("Allow", Setup.page(Step.BACKGROUND).allow)
        assertTrue("\"${Setup.OPEN_BATTERY}\"" in Setup.MAKER_SWITCH_PATH.first())
        assertTrue(Setup.hasMakerBatterySwitch("vivo") && Setup.hasMakerBatterySwitch("iQOO"))
        assertFalse(Setup.hasMakerBatterySwitch("Google") || Setup.hasMakerBatterySwitch(null))
        // every screen of the picture names a step that is in the written path, in the same order
        val idx = Setup.MAKER_SWITCH_SCREENS.map { s -> Setup.MAKER_SWITCH_PATH.indexOfFirst { s.caption.substringBefore(':') in it } }
        assertTrue(idx.all { it >= 0 } && idx == idx.sorted(), "picture $idx does not follow the path")
    }

    @Test fun everyButtonIsPlainWordsAndShort() {
        for (s in Step.entries) {
            val b = Setup.page(s).allow
            assertTrue(b.split(' ').size <= 4, "button \"$b\" is too long")
            for (jargon in listOf("permission", "grant", "listener", "notification access", "optimi")) assertFalse(jargon in b.lowercase(), "button \"$b\" says $jargon")
        }
    }

    @Test fun notificationHasBothButtons() {
        // his ask 7 Oct 2026: on AND off from the notification, without leaving the song
        assertEquals("Turn off", Words.NOTE_TURN_OFF)
        assertEquals("Turn on", Words.NOTE_TURN_ON)
        assertTrue(Words.NOTE_PAUSED.contains(Words.NOTE_TURN_ON))
    }

    @Test fun notificationLineIsPlain() {
        assertEquals("Tuning for talk / lecture", Words.notificationLine(true, false, true, ContentKind.SPEECH, false))
        assertEquals("Tuning for vocal pop / Bollywood. A bit loud: turn it down a little.", Words.notificationLine(true, false, true, ContentKind.VOCAL_POP, true))
        assertEquals("On. Waiting for something to play.", Words.notificationLine(true, false, false, null, true))
        assertEquals("Hear-the-difference test is running", Words.notificationLine(true, true, true, ContentKind.SPEECH, false))
    }

    /** The Vivo steps say which row to find by the app's name; a rename must reach them (29 Sep: Sound -> Audito Amplificus). */
    @Test fun makerStepsNameTheAppAsAndroidShowsIt() {
        val name = Regex("<string name=.app_name.>([^<]+)</string>").find(File("src/main/res/values/strings.xml").readText())!!.groupValues[1]
        assertTrue(Setup.MAKER_SWITCH_PATH.any { "\"$name\"" in it }, "no step names $name")
        assertTrue(Setup.MAKER_SWITCH_SCREENS.any { it.caption.startsWith("$name:") }, "no picture names $name")
    }

    // ---- Play Store build (31 Oct 2026): no one-tap battery box, same feature through the battery list

    @Test fun thisTestRunIsTheSideloadBuild() {
        // the phone he and his friend use must keep the one-tap box; tests run without -PauditoStore
        assertFalse(com.arnavagarwal.sound.BuildConfig.PLAY_STORE)
        assertEquals(Setup.BACKGROUND_PATH_BOX, Setup.page(Step.BACKGROUND).path)
    }

    @Test fun playBuildStepsNameTheListNotTheBox() {
        val play = Setup.page(Step.BACKGROUND, play = true).path
        assertTrue(play.none { "in the box" in it }, "Play steps still promise a box: $play")
        assertTrue(play.any { "Don't optimise" in it } && play.any { "Amplificus" in it }, "Play steps: $play")
        // Android 15/16 has no "Don't optimise" there: the page says "Allow background usage" (emulator walk, 1 Nov 2026)
        assertTrue(play.any { "Allow background usage" in it } && "Allow background usage" in Setup.allText(true), "Play steps: $play")
        assertTrue(Setup.allText(true).contains("battery list") && !Setup.allText(false).contains("battery list"))
        // the sideload words are unchanged, byte for byte, from 0.1.16
        assertEquals("Tap Allow. A few boxes come up one after another: tap Allow in each. Last, Android opens a page: " +
            "switch on \"Allow notification access\" and tap Allow. The app comes back by itself.", Setup.allText(false))
    }

    @Test fun playManifestRemovesTheBatteryPermissionAndTheCodeChecksTheSwitch() {
        val m = File("src/play/AndroidManifest.xml").readText()
        assertTrue(Regex("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS\"\\s+tools:node=\"remove\"").containsMatchIn(m), "play manifest does not remove it")
        val src = File("src/main/java/com/arnavagarwal/sound/ui/MainActivity.kt").readText()
        val fn = src.substringAfter("private fun openBatterySetting").substringBefore("\n}\n")
        assertTrue(fn.indexOf("PLAY_STORE") in 0 until fn.indexOf("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"),
            "the one-tap box is reachable before the Play check")
    }
}
