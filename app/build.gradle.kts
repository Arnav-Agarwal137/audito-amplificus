import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.arnavagarwal.sound"
    // 37 because Compose 1.12 demands it; this only changes the API we compile against - behaviour follows targetSdk (36)
    compileSdk = 37

    defaultConfig {
        applicationId = "com.arnavagarwal.sound"
        minSdk = 28 // DynamicsProcessing arrived in Android 9 (API 28)
        targetSdk = 36
        versionCode = 18
        versionName = "0.1.17"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // -PauditoStore=play -> the Play Store build (31 Oct 2026): no one-tap battery box, "-play" on the version.
        // Anything else is refused, so a typo can never quietly build the sideload version under a Play name.
        val store = providers.gradleProperty("auditoStore").getOrElse("side")
        require(store == "side" || store == "play") { "auditoStore must be side or play, got '$store'" }
        buildConfigField("boolean", "PLAY_STORE", (store == "play").toString())
        if (store == "play") versionNameSuffix = "-play"
    }
    if (providers.gradleProperty("auditoStore").getOrElse("side") == "play") {
        sourceSets.getByName("debug").manifest.srcFile("src/play/AndroidManifest.xml")
        sourceSets.getByName("release").manifest.srcFile("src/play/AndroidManifest.xml")
    }

    // The release key lives on D: and is never committed (RESUME-HERE.md). Missing file = the release
    // build still works, just unsigned, so a fresh machine can build without it.
    val signing = Properties().apply {
        val f = file(providers.gradleProperty("soundSigning").getOrElse("D:/dev/keys/sound-release.properties"))
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (signing.getProperty("storeFile") != null) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation(kotlin("test-junit")) // Android unit tests run on JUnit 4; plain kotlin("test") binds no @Test
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
}

// TrustTest / WordsTest read these files directly (the manifest, the licence texts, the laptop's words), so they must be
// task inputs: without this a cached green survived allowBackup being turned back on (mutant, 8 Oct 2026)
tasks.withType<Test>().configureEach {
    inputs.file("src/main/AndroidManifest.xml")
    inputs.file("src/play/AndroidManifest.xml")
    inputs.dir("src/main/assets")
    inputs.dir("src/main/res")
    inputs.dir("../desktop/src/main/kotlin")
    inputs.dir("../core/src/main/resources")
}
