import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :shared - Kotlin Multiplatform module with Doorprints' platform-neutral code (see README.md in this folder).
// Targets: Android (AGP's KMP library plugin) and, compile-only, iosArm64 + iosSimulatorArm64. The iOS targets exist
// so commonMain cannot use JVM/Android APIs; there is no iOS app and no iOS framework binary in this sprint.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // AGP 9 name for the Android target of a KMP library (androidLibrary {} is deprecated since AGP 9.1).
    android {
        namespace = "com.househunt.shared"
        compileSdk = 37
        minSdk = 26
        compilerOptions.jvmTarget = JvmTarget.JVM_17
        // Host (JVM) tests are off by default in this plugin; they run commonTest + androidHostTest as the Gradle
        // task :shared:testAndroidHostTest (the app's testDebugUnitTest depends on it, see app/build.gradle.kts).
        withHostTest {}
    }

    // Compile-only guard rails for Phase 2. Built by the macOS CI job (shared-ios.yml: compileKotlinIosArm64,
    // compileKotlinIosSimulatorArm64, compileTestKotlinIosSimulatorArm64). On ubuntu these tasks are skipped, also
    // under :shared:allTests, because gradle.properties sets kotlin.native.enableKlibsCrossCompilation=false.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api: ApiClient takes an HttpClient, so :app sees Ktor's client types.
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test.junit)
        }
    }
}
