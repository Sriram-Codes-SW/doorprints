// Versions live in gradle/libs.versions.toml. Every plugin is declared here (apply false) so :app and :shared load
// AGP and the Kotlin Gradle plugin in the same classloader, which AGP's KMP library plugin requires.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    // AGP 9 compiles :app's Kotlin itself; declaring KGP here only pins the Kotlin version it uses.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}
