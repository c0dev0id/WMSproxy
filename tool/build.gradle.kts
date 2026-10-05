plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

// The catalogue tool: reads every library service with the app's own parser and writes
// the documents into the asset the app ships. Plain JVM and never part of the app; it
// runs in CI, where a build is possible, from the catalogue workflow.
kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("de.codevoid.wmsproxy.tool.CatalogTool")
}

// Paths in the tool are relative to the repository, not to this module.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(project(":core"))
}
