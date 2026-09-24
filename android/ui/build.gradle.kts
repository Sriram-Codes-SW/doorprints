import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :ui - Compose Multiplatform module with Doorprints' UI code that is not tied to Android (ADR-23, README.md in this
// folder). Targets: Android (AGP's KMP library plugin) and, compile-only for now, iosArm64 + iosSimulatorArm64,
// the same as :shared. The screens move here phase by phase; :app stays the Android application around them.
// The Kotlin package of the moved files stays com.househunt.app.ui, so :app's imports do not change.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        // Android resources (R class) of this module; the Kotlin package is com.househunt.app.ui (see above).
        namespace = "com.househunt.ui"
        compileSdk = 37
        minSdk = 26
        compilerOptions.jvmTarget = JvmTarget.JVM_17
        // Host (JVM) tests: commonTest + androidHostTest as :ui:testAndroidHostTest, which :app's testDebugUnitTest
        // depends on (app/build.gradle.kts), so the CI command runs them.
        withHostTest {}
    }

    // Compile-only, like :shared: shared-ios.yml compiles the iOS klibs on macOS; on ubuntu these tasks are skipped
    // (gradle.properties: kotlin.native.enableKlibsCrossCompilation=false). No framework binary yet (ADR-23 phase 8).
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api: the theme and composables take and return Compose types, and :app uses the domain types
            // (HouseStatus, SyncOutcome) they are built on.
            api(project(":shared"))
            api(libs.cmp.runtime)
            api(libs.cmp.foundation)
            api(libs.cmp.ui)
            api(libs.cmp.material3)
            api(libs.cmp.material.icons.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test.junit)
        }
    }
}
