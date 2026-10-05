plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "de.codevoid.wmsproxy"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.codevoid.wmsproxy"
        // Android 8.0. startForegroundService() and NotificationChannel both require
        // API 26; nothing else in the codebase needs higher. startForeground() is
        // version-gated in ProxyService: the 3-arg form with FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        // on API 34+, the deprecated 2-arg form on 26–33.
        minSdk = 26
        targetSdk = 34
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersionName") as String?).takeIf { !it.isNullOrEmpty() } ?: "dev-local"
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
        // TopAppBar and ModalBottomSheet are still experimental in material3 1.3; one flag
        // for the module rather than an annotation on every screen that uses them.
        freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
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
    // The full icon set: star, sync, copy, open-in-browser and friends. R8 keeps only the
    // icons that are referenced, so the release build pays for what it draws.
    implementation("androidx.compose.material:material-icons-extended")
    // 2.8 is the last line built on Compose 1.7, which the BOM above pins; 2.9 moves to
    // Compose 1.8 and lifecycle 2.9, a toolchain step of its own.
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // The preview map: a plain-Java map view with pinch, pan and fling, fed by the same
    // template expansion the proxy and DMD get. Display only; nothing it decodes is served.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    testImplementation("junit:junit:4.13.2")
}
