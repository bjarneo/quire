plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 compiles Kotlin itself. This line only pins the Kotlin Gradle plugin version.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
