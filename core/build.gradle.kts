plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Pure JVM on purpose: nothing in the rewrite path touches android.graphics or any
// Android API, so every geometry, capabilities and URL-building decision is testable
// with plain JUnit in CI without an emulator. Keep it that way — an Android dependency
// here would push its tests into :app and out of reach of a normal `test` run.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
}
