// Pure Kotlin: every sound decision lives here so it can be measured by JVM tests (RULES: error check as we go).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(21) }

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
