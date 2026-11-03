package com.arnavagarwal.sound.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 2 Nov 2026 legal audit: the laptop setup shipped Apache / BSD / MIT / OFL / Unicode parts and carried none of their
 *  licence texts, nor our own licence or privacy policy. These pin that the legal folder exists, that every file the
 *  notices point at is really there, and that both packagings (setup .exe and portable zip) carry it. */
class LegalTest {
    private val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val notices = File(root, "tools/publish/public/THIRD-PARTY-NOTICES.md").readText()

    @Test fun everyLaptopLicenceTheNoticesNameExists() {
        val named = Regex("""licenses/laptop/([A-Za-z0-9._-]+)""").findAll(notices).map { it.groupValues[1] }.toSet()
        assertTrue(named.size >= 7, "only ${named.size} laptop licence files named: $named")
        for (n in named) assertTrue(File(root, "licenses/laptop/$n").length() > 500, "missing or empty: licenses/laptop/$n")
    }

    @Test fun ourOwnNoticeMatchesThePublicExport() {
        val export = File(root, "tools/publish/export.py").readText()
        val author = Regex("""AUTHOR = "([^"]+)"""").find(export)!!.groupValues[1]
        val github = Regex("""GITHUB = "([^"]+)"""").find(export)!!.groupValues[1]
        val ours = File(root, "licenses/laptop/LICENSE-Audito-Amplificus.txt").readText()
        assertTrue("Required Notice: Copyright (c) 2026 $author ($github)" in ours, "notice drifted from export.py")
        assertTrue(File(root, "licenses/laptop/polyform-noncommercial-1.0.0.txt").readText().startsWith("# PolyForm Noncommercial License 1.0.0"))
    }

    @Test fun bothPackagingsCarryTheLegalFolder() {
        val iss = File(root, "tools/portable/AuditoAmplificus.iss").readText()
        assertTrue("""Source: "{#Root}\licenses\laptop\*"; DestDir: "{app}\legal\licenses"""" in iss, "setup .exe lost the licences")
        assertTrue("""Source: "{#Root}\tools\publish\public\*.md"; DestDir: "{app}\legal"""" in iss, "setup .exe lost privacy/terms")
        val gradle = File(root, "desktop/build.gradle.kts").readText()
        assertTrue("""from(rootProject.file("licenses/laptop")) { into("Audito Amplificus/legal/licenses") }""" in gradle, "zip lost the licences")
        for (doc in listOf("PRIVACY.md", "DISCLAIMER.md", "THIRD-PARTY-NOTICES.md", "COMMERCIAL.md", "README.md"))
            assertTrue(File(root, "tools/publish/public/$doc").isFile, doc)
        assertEquals(1, Regex("licences and privacy").findAll(iss).count(), "Start-menu shortcut to the legal folder")
    }
}
