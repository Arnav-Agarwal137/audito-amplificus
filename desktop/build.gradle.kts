// The Windows version: the same tuning brain (:core) with Windows ears (windows/helper, a .NET process) and
// Equalizer APO as the hands (it changes all system sound; we write its config).
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // KaraokeTest and DenoiseTest run the Python helpers for real; without this a change to a helper left the test
    // task UP-TO-DATE and a broken helper read as green (found 28 Sep by a mutant that survived only for that reason)
    inputs.dir(rootProject.file("tools/karaoke"))
    inputs.dir(rootProject.file("tools/denoise"))
    // files the tests read directly (EditionTest, DesktopTest), so a change to them cannot be served a cached green
    inputs.file("build.gradle.kts")
    inputs.file(rootProject.file("tools/windows-spike/strong-liked.txt"))
    // E-035: a test fixture broke the public export for 20 days because the export's privacy scan only ran at export
    // time. So every desktop test run also runs it. export.py is not in the public copy, which says so instead.
    dependsOn("exportSelftest")
}

tasks.register<Exec>("exportSelftest") {
    val script = rootProject.file("tools/publish/export.py")
    onlyIf("tools/publish/export.py exists (absent in the public copy)") { script.exists() }
    workingDir = rootProject.projectDir
    commandLine("python", "-I", script.path, "--selftest")
    // its verdict depends on every tracked file, so it is never served from cache
    outputs.upToDateWhen { false }
}

// Where the helper, the Python tools and the installed copy live: -PauditoTools=<folder> or AUDITO_TOOLS, else D:/dev
// (the owner's laptop). ToolsRoot.kt reads the same setting at run time.
val toolsRoot: String = providers.gradleProperty("auditoTools").orElse(providers.environmentVariable("AUDITO_TOOLS")).getOrElse("D:/dev")

// FREE or PAID copy (Edition.kt): -PauditoEdition=paid hides and refuses karaoke + clean a talk (research-only weights).
val edition: String = providers.gradleProperty("auditoEdition").getOrElse("free")

compose.desktop {
    application {
        mainClass = "com.arnavagarwal.sound.desktop.MainKt"
        jvmArgs += listOf("-Dsound.helper=$toolsRoot/sound-builds/helper/SoundHelper.dll", "-Daudito.tools=$toolsRoot", "-Daudito.edition=$edition")
        // the PORTABLE copy for other people (his tap 10 Oct 2026, "Portable app (zip)"): createDistributable makes a
        // folder with its own Java inside and a launcher .exe; portableZip below adds the helper parts and zips it
        nativeDistributions {
            packageName = "Audito Amplificus"
            packageVersion = "1.0.0"
            vendor = "Arnav Agarwal"
            modules("java.instrument", "java.prefs", "jdk.unsupported") // :desktop:suggestRuntimeModules, 10 Oct 2026
            windows { iconFile.set(rootProject.file("tools/install/audito.ico")) }
        }
    }
}

// The .NET parts as SELF-CONTAINED single .exe files (a friend's laptop has no .NET), into build/portable-parts.
val portableParts = layout.buildDirectory.dir("portable-parts")
fun dotnetPublish(name: String, project: String, out: String) = tasks.register<Exec>(name) {
    inputs.dir(rootProject.file(project).parentFile.let { d -> fileTree(d) { exclude("bin/**", "obj/**") } })
    outputs.dir(portableParts.map { it.dir(out) })
    commandLine("dotnet", "publish", rootProject.file(project).path, "-c", "Release", "-r", "win-x64", "--self-contained",
        "-p:PublishSingleFile=true", "-p:IncludeNativeLibrariesForSelfExtract=true", "-p:EnableCompressionInSingleFile=true", "-p:DebugType=none",
        "-o", portableParts.get().dir(out).asFile.path)
}
val publishHelper = dotnetPublish("publishPortableHelper", "windows/helper/SoundHelper.csproj", "helper")
val publishApoSetup = dotnetPublish("publishPortableApoSetup", "windows/apo-setup/ApoSetup.csproj", "apo-setup")

// ONE setup .exe (his ask 11 Oct 2026: as easy as possible): Inno Setup with no wizard pages and no admin, puts the
// app in the person's own Programs folder with the same portable/ layout as the zip, then starts it.
// Inno Setup 6: -PinnoSetup=<ISCC.exe>, else D:/dev/tools/InnoSetup6/ISCC.exe (installed 11 Oct 2026).
tasks.register<Exec>("windowsInstaller") {
    dependsOn("createDistributable", publishHelper, publishApoSetup)
    val iscc = providers.gradleProperty("innoSetup").getOrElse("D:/dev/tools/InnoSetup6/ISCC.exe")
    val ver = providers.gradleProperty("portableVersion").getOrElse("1.0.0")
    val app = layout.buildDirectory.dir("compose/binaries/main/app/Audito Amplificus").get().asFile
    commandLine(iscc, "/Q", "/DAppDir=${app.path}", "/DParts=${portableParts.get().asFile.path}",
        "/DRoot=${rootProject.projectDir.path}", "/DAppVer=$ver", "/DOutDir=${file("$toolsRoot/sound-builds").path}",
        rootProject.file("tools/portable/AuditoAmplificus.iss").path)
}

// <dist>/Audito Amplificus/portable/{helper,apo-setup,install-apo.ps1} (ToolsRoot.portable), then one zip in
// <tools>/sound-builds. ApoSetup is GPL-2.0-or-later, so its source and licence ride along in the zip.
tasks.register<Zip>("portableZip") {
    dependsOn("createDistributable", publishHelper, publishApoSetup)
    val app = layout.buildDirectory.dir("compose/binaries/main/app")
    from(app)
    from(portableParts) { into("Audito Amplificus/portable") }
    from(rootProject.file("tools/portable/install-apo.ps1")) { into("Audito Amplificus/portable") }
    from(rootProject.file("tools/portable/READ-ME-FIRST.txt"))
    from(rootProject.file("windows/apo-setup")) {
        include("*.cs", "*.csproj", "COPYING", "NOTICE.md")
        into("Audito Amplificus/portable/apo-setup/source")
    }
    // same legal folder as the setup .exe (AuditoAmplificus.iss), 2 Nov 2026
    from(rootProject.file("licenses/laptop")) { into("Audito Amplificus/legal/licenses") }
    from(rootProject.file("tools/publish/public")) { include("*.md"); exclude("CONTRIBUTING.md"); into("Audito Amplificus/legal") }
    archiveFileName.set("Audito-Amplificus-laptop-${providers.gradleProperty("portableVersion").getOrElse("1.0.0")}.zip")
    destinationDirectory.set(file("$toolsRoot/sound-builds"))
}

// The installed copy (tools/install/install.ps1): the app's jars in a fixed folder on D:, started at logon by the JDK's
// own javaw.exe (a freshly built launcher .exe is blocked by Defender here, E-013). The build folder is not used, so a
// gradle clean or a rebuild can never break the copy that is running.
tasks.register<Sync>("installCopy") {
    from(tasks.named("jar"))
    // two different jars can share a file name (JetBrains' small stub and AndroidX's real runtime-desktop-1.12.1.jar,
    // seen 29 Sep): a name-only copy would keep one and drop the other, so a shared name gets its group in front
    val files = configurations.named("runtimeClasspath").get().files
    val shared = files.groupBy { it.name }.filterValues { it.size > 1 }.keys
    files.forEach { f ->
        from(f) { if (f.name in shared) rename { f.parentFile.parentFile.parentFile.parentFile.name + "-" + f.name } }
    }
    into("$toolsRoot/sound-builds/app/lib")
}

// Headless check against the real helper + Equalizer APO (Smoke.kt). Runs on the JDK's own java (E-013).
tasks.register<JavaExec>("smoke") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.arnavagarwal.sound.desktop.SmokeKt")
    systemProperty("sound.helper", "$toolsRoot/sound-builds/helper/SoundHelper.dll")
}

// The two settings for the quiet-listening blind test (QuietText.kt).
tasks.register<JavaExec>("quietText") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.arnavagarwal.sound.desktop.QuietTextKt")
}

// Old vs new density for the per-song blind test (DensityCapText.kt).
tasks.register<JavaExec>("densityCap") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.arnavagarwal.sound.desktop.DensityCapTextKt")
}

// The song log for sound files (SongFile.kt): web songs measured by the app's own code.
tasks.register<JavaExec>("songFile") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.arnavagarwal.sound.desktop.SongFileKt")
}

// The window, started with the tuning OFF (for screenshots while someone is listening).
tasks.register<JavaExec>("preview") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.arnavagarwal.sound.desktop.MainKt")
    systemProperty("sound.helper", "$toolsRoot/sound-builds/helper/SoundHelper.dll")
    args("--look")
}

// Study music's Equalizer APO lines (QuietText.kt, VoiceText) for the silent check voice_check.py.
tasks.register<JavaExec>("voiceText") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.arnavagarwal.sound.desktop.VoiceText")
}

