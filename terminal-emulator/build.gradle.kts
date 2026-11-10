// Termux's terminal emulator (Apache License 2.0, see LICENSE), from github.com/termux/termux-app (commit 8629e63):
// the screen, its escape sequences, and a shell started in a pseudo terminal (src/main/jni/termux.c). The app's
// terminal is built on it.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.emulator"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 28
        externalNativeBuild {
            ndkBuild {
                cFlags += listOf("-std=c11", "-Wall", "-Wextra", "-Os", "-fno-stack-protector", "-Wl,--gc-sections")
            }
        }
        // Nixup runs on 64-bit ARM phones.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
}
