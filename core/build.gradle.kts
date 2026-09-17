plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    // For the `api` configuration below: RequestLog exposes StateFlow in its public API,
    // so consumers need coroutines on their compile classpath, not just at runtime.
    id("java-library")
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

    // Plain JVM, so this does not compromise the Android-free rule above. Pinned to the
    // version :app already resolves via kotlinx-coroutines-android, because two
    // coroutines versions on one classpath is a problem nobody enjoys diagnosing.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
