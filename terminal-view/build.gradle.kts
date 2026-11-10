// Termux's terminal view (Apache License 2.0, see LICENSE), from github.com/termux/termux-app (commit 8629e63): draws
// the emulator's screen, takes the keyboard and touch, and selects text. The app's terminal is built on it.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.view"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }
}

dependencies {
    api(project(":terminal-emulator"))
    implementation("androidx.annotation:annotation:1.9.1")
}
