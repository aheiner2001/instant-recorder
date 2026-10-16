// Top-level build file. AGP 9 compiles Kotlin itself ("built-in Kotlin"); declaring the
// Kotlin plugin here only pins the Kotlin Gradle Plugin version on the classpath.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
