package com.arnavagarwal.sound.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The portable zip for other people (10 Oct 2026): it must find its OWN parts, never the owner's D:/dev ones. */
class PortableTest {
    private fun withAppPath(path: String?, block: () -> Unit) {
        val old = System.getProperty("jpackage.app-path")
        if (path == null) System.clearProperty("jpackage.app-path") else System.setProperty("jpackage.app-path", path)
        try { block() } finally { if (old == null) System.clearProperty("jpackage.app-path") else System.setProperty("jpackage.app-path", old) }
    }

    @Test fun portableFolderWinsOverTheBakedInPaths() {
        val app = Files.createTempDirectory("audito portable").toFile()
        try {
            File(app, "portable/helper").mkdirs(); File(app, "portable/helper/SoundHelper.exe").writeText("x")
            File(app, "portable/install-apo.ps1").writeText("x")
            withAppPath(File(app, "Audito Amplificus.exe").path) {
                assertEquals(File(app, "portable/helper/SoundHelper.exe").absolutePath, ToolsRoot.helper.absolutePath)
                assertEquals(File(app, "portable/apo-setup/ApoSetup.exe").absolutePath, ToolsRoot.apoSetup.absolutePath)
                assertEquals(File(app, "portable").absolutePath, ToolsRoot.dir.absolutePath)
                assertEquals(File(app, "portable/install-apo.ps1").absolutePath, ToolsRoot.installApo?.absolutePath)
            }
        } finally { app.deleteRecursively() }
    }

    @Test fun withoutTheLauncherNothingChangesOnHisLaptop() = withAppPath(null) {
        assertNull(ToolsRoot.portable); assertNull(ToolsRoot.installApo)
        assertTrue(ToolsRoot.apoSetup.path.endsWith("ApoSetup.dll"))
    }

    @Test fun aLauncherWithoutTheHelperIsNotPortable() {
        val app = Files.createTempDirectory("audito empty").toFile()
        try { withAppPath(File(app, "x.exe").path) { assertNull(ToolsRoot.portable) } } finally { app.deleteRecursively() }
    }

    @Test fun exePartsRunAsThemselvesDllsThroughDotnet() {
        assertEquals(listOf(File("C:/a b/SoundHelper.exe").absolutePath), ToolsRoot.dotnetCommand(File("C:/a b/SoundHelper.exe")))
        assertEquals("dotnet", ToolsRoot.dotnetCommand(File("C:/a/SoundHelper.dll")).first())
        val c = Apo.elevateCommand(File("D:/Bob's Apps/Audito Amplificus/portable/apo-setup/ApoSetup.exe"))
        assertTrue("-FilePath 'D:\\Bob''s Apps\\Audito Amplificus\\portable\\apo-setup\\ApoSetup.exe' -ArgumentList 'setup'" in c, c)
        assertTrue("dotnet" !in c)
    }

    @Test fun installCommandIsElevatedAndQuotesBothPaths() {
        val c = Apo.installCommand(File("D:/Bob's Apps/A A/portable/install-apo.ps1"), File("D:/Bob's Apps/A A/portable/apo-setup/ApoSetup.exe"))
        // the prompt must name OUR exe, never PowerShell (a stranger's reason to say No)
        assertTrue(c.startsWith("Start-Process -Verb RunAs -WindowStyle Hidden -FilePath 'D:\\Bob''s Apps\\A A\\portable\\apo-setup\\ApoSetup.exe'"), c)
        assertTrue("-ArgumentList 'install \"D:\\Bob''s Apps\\A A\\portable\\install-apo.ps1\"'" in c, c)
        assertTrue("powershell" !in c.lowercase())
    }
}
