plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Pure JVM on purpose: nothing in the rewrite path touches android.graphics or any
// Android API, so every geometry, capabilities and URL-building decision is testable
// with plain JUnit in CI without an emulator. Keep it that way — an Android dependency
// here would push its tests into :app and out of reach of a normal `test` run.
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
