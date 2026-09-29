/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :ui - Compose Multiplatform module with Doorprints' UI code that is not tied to Android (ADR-23, README.md in this
// folder). Targets: Android (AGP's KMP library plugin) and iosArm64 + iosSimulatorArm64, the same as :shared, which
// build the iOS app's framework (CMP-8b). The screens move here phase by phase; :app stays the Android application
// around them, and the Swift app in ios/ around the framework.
// The Kotlin package of the moved files stays app.doorprints.ui, so :app's imports do not change.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    // Compose resources: the UI strings in src/commonMain/composeResources become the generated Res class (CMP-2).
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    android {
        // The Android namespace; the Kotlin package is app.doorprints.ui.
        namespace = "app.doorprints.ui"
        // Compose resources are packaged as Android assets, which needs Android resources on in this KMP library.
        androidResources { enable = true }
        compileSdk = 37
        minSdk = 26
        compilerOptions.jvmTarget = JvmTarget.JVM_17
        // Host (JVM) tests: commonTest + androidHostTest as :ui:testAndroidHostTest, which :app's testDebugUnitTest
        // depends on (app/build.gradle.kts), so the CI command runs them.
        withHostTest {}
    }

    // The iOS app's framework (ADR-23 CMP-8b): DoorprintsKit, static, with this module, :shared and the libraries in
    // it; the Swift app calls MainViewControllerKt.MainViewController(). Xcode builds it through
    // embedAndSignAppleFrameworkForXcode (ios/), CI also links it (linkDebugFrameworkIosSimulatorArm64). Linking needs
    // Xcode, so only macOS runs these tasks; on ubuntu the iOS tasks are skipped (gradle.properties:
    // kotlin.native.enableKlibsCrossCompilation=false), and with that flag set to true the klibs also compile there.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "DoorprintsKit"
            // Static: linked into the app binary, so there is no dynamic framework to embed and sign separately.
            isStatic = true
        }
    }

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
            // api: :app's screens call stringResource(Res.string.…) until they move here (ADR-23).
            api(libs.cmp.components.resources)
            // DeletedHouseUndo's NonCancellable write-back (CMP-3).
            implementation(libs.kotlinx.coroutines.core)
            // Navigation and view models in common code (CMP-5): the nav graph (Root.kt), the screens' lifecycle-aware
            // collection and effects, AssistantViewModel and its SavedStateHandle. api: :app's screens still call
            // viewModel { }, collectAsStateWithLifecycle and dropUnlessResumed, and implement RootScreens' slots.
            api(libs.jb.navigation.compose)
            api(libs.jb.lifecycle.runtime.compose)
            api(libs.jb.lifecycle.viewmodel.compose)
            api(libs.jb.lifecycle.viewmodel.savedstate)
            // The Assistant keeps its answer and plan in saved state as JSON (AssistantViewModel).
            implementation(libs.kotlinx.serialization.json)
            // The house form's photo tiles and viewer (CMP-6 P6a): Coil 3 is multiplatform; the same library :app used.
            implementation(libs.coil.compose)
        }
        androidMain.dependencies {
            // The Android side of the seams (CMP-5): permission checks (ContextCompat, ActivityCompat) and the
            // permission prompts (rememberLauncherForActivityResult); since CMP-6 the house form's BackHandler.
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            // The Map's view (CMP-7, PlatformMap.android.kt, MapLibreStyleOps): MapLibre Native, the OpenGL ES build
            // :app already used (the catalog's maplibre-android).
            implementation(libs.maplibre.android)
        }
        iosMain.dependencies {
            // The swipe-back for PlatformBackHandler (CMP-8b, BackHandler.ios.kt): NavigationBackHandler, the API the
            // navigation graph already listens to on iOS.
            implementation(libs.jb.navigationevent.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // IndiaViewRulesTest evaluates the boundary filters, which are MapLibre style JSON (CMP-3).
            implementation(libs.kotlinx.serialization.json)
            // AssistantViewModelTest: viewModelScope runs on Dispatchers.Main, replaced by a test dispatcher (CMP-5).
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test.junit)
        }
    }
}

// The generated resource accessors (Res.string.x, Res.plurals.x): public, because :app's screens use them until they
// move to :ui, in a package next to the UI code.
compose.resources {
    publicResClass = true
    packageOfResClass = "app.doorprints.ui.res"
    generateResClass = always
}

// The simulator the iosSimulatorArm64Test task runs on (CMP-8a; `simctl spawn --standalone`): CI passes -PiosSimulatorDevice=<UDID> of an iPhone
// that exists on the runner image, so the run does not depend on KGP's default device name being installed.
providers.gradleProperty("iosSimulatorDevice").orNull?.let { simulator ->
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
        device.set(simulator)
    }
}
