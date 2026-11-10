// The app: builds the phone config with the Magisk module, and opens the config folder.
plugins {
    id("com.android.application")
}

android {
    namespace = "dev.nixup.manager"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.nixup.manager"
        minSdk = 28
        targetSdk = 36
        versionCode = 8
        versionName = "0.1.6"
    }
}

dependencies {
    // The terminal: Termux's emulator and view.
    implementation(project(":terminal-view"))
    // The API-29 fallbacks (window insets, the role service) say which version they need.
    implementation("androidx.annotation:annotation:1.9.1")
    // Google Play, as Aurora Store reaches it (GPL-3.0): the engine downloads android.packages.playstore with it.
    implementation("com.auroraoss:gplayapi:4.0.0")
    // Its calls are suspend functions; the engine waits for them (runBlocking).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
