// Top-level build file.
//
// Versions are pinned deliberately: this project must build offline-ish on a
// workstation that already has the SDK, so every version here has been resolved
// once and is cached in ~/.gradle.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
