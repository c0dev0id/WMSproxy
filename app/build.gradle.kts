plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.codevoid.wmsproxy"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.codevoid.wmsproxy"
        // Android 14. The proxy runs as a specialUse foreground service, and both that
        // type and its required <property> subtype declaration are API 34 features, so a
        // lower floor would only buy version-gating code for devices this never targets.
        minSdk = 34
        targetSdk = 34
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersionName") as String?).takeIf { !it.isNullOrEmpty() } ?: "dev-local"

        // The hostname the TLS certificate is valid for, and therefore the host the
        // HTTPS URL must name. Defaults to the loopback address, which matches the
        // self-signed certificate in assets. CI overrides it when a real certificate is
        // supplied, because a certificate for a named host will not validate when the
        // client connects to a bare IP.
        buildConfigField(
            "String",
            "TLS_HOST",
            "\"" + ((project.findProperty("tlsHost") as String?).takeIf { !it.isNullOrEmpty() } ?: "127.0.0.1") + "\"",
        )
    }

    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    val keystorePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
    val signingKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
    val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")
    val hasSigningConfig = !keystorePath.isNullOrEmpty() &&
        !keystorePassword.isNullOrEmpty() &&
        !signingKeyAlias.isNullOrEmpty() &&
        !signingKeyPassword.isNullOrEmpty()

    if (hasSigningConfig) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        // A distinct applicationId so a branch build installs alongside the release build.
        // The proxy is a navigation dependency — testing a branch must not disturb the
        // instance in daily use, and both can be running to compare behaviour.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Off by default in AGP 8. The updater compares the running build against the
        // published one via BuildConfig.VERSION_NAME, so this is load-bearing.
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
