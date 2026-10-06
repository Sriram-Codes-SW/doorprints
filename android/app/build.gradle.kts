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

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// Release signing (threat model F-11). The keystore never lives in the repo: CI or the developer supplies it
// through Gradle properties (-PHH_KEYSTORE_FILE=..., ~/.gradle/gradle.properties) or environment variables
// of the same names. The config is only created when all four are set; otherwise release builds stay unsigned
// (assembleRelease still works, the APK just cannot be installed until it is signed).
// HH_KEYSTORE_FILE should be an ABSOLUTE path (docs/07 section 7.1). A relative path is resolved by file()
// against this module's directory, android/app/ (not the repo root, not android/, not the shell's cwd),
// so "release.jks" means android/app/release.jks. Keep the keystore outside the repo.
val releaseSigning = listOf("HH_KEYSTORE_FILE", "HH_KEYSTORE_PASSWORD", "HH_KEY_ALIAS", "HH_KEY_PASSWORD")
    .associateWith { name ->
        providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull?.takeIf { it.isNotBlank() }
    }
val hasReleaseSigning = releaseSigning.values.all { it != null }

android {
    // The product is called Doorprints (renamed from "House Hunt" on 2026-09-22). The applicationId changed that
    // day; the namespace (R class, BuildConfig) and the Kotlin package followed on 2026-09-24 (were com.househunt.app).
    // The app was never published, so the new id simply installs side by side with old com.househunt.app test
    // builds (uninstall those by hand; their local data is not carried over). Stored names that outlive a package
    // rename are carried over at start instead: the Room file (data/DatabaseFile.kt) and queued WorkManager jobs
    // (LegacyWorkerFactory.kt); the Keystore alias stays (data/ApiKeyCipher.kt).
    // Everything that depends on the id follows it automatically: the FileProvider authority is
    // "${applicationId}.files" in the manifest and context.packageName + ".files" in code.
    namespace = "app.doorprints"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.doorprints"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Instrumented smoke tests on an emulator (android-emulator.yml) and in Firebase Test Lab. The test storage
        // service keeps their screenshots; AGP pulls them into build/outputs/connected_android_test_additional_output.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // Absolute path recommended; a relative one resolves against android/app/ (see comment at top).
                storeFile = file(releaseSigning.getValue("HH_KEYSTORE_FILE")!!)
                storePassword = releaseSigning.getValue("HH_KEYSTORE_PASSWORD")
                keyAlias = releaseSigning.getValue("HH_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("HH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off for now: kotlinx.serialization, Room (KSP-generated code), MapLibre (JNI) and
            // WorkManager (reflective worker creation) need keep rules that are not written or tested yet, and
            // there are no instrumented tests to catch a class R8 strips. A shrunk build that crashes at runtime
            // is worse than a larger APK. Turn on together with proguard-rules.pro and a release smoke test.
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // Licence and OSGi metadata that several KMP/Ktor jars ship under the same path; not needed at runtime
            // and a duplicate would stop the merge (same exclusions as Google's KMP sample app).
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    testOptions {
        // JVM unit tests touch a few android.* classes (e.g. Log via data/Api.kt); return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
        // Robolectric (screenshot tests) needs the merged resources, manifest and assets.
        unitTests.isIncludeAndroidResources = true
    }

    // Only the app's four languages go into the APK. Without this, the libraries' own translations (values-mr from
    // androidx and material3, …) make Android resolve a phone set to [Marathi, Hindi] to Marathi, which the app
    // lacks, and fall back to English; with it, Android skips to Hindi (AppLocale.applyDefault, docs/05 section 8.2).
    androidResources {
        localeFilters += listOf("en", "hi", "ta", "te")
    }

    lint {
        // Every Android-resource string (the services' strings in res/values*) must exist in values-hi, values-ta
        // and values-te (docs/05 section 8.2). The UI strings are Compose resources in :ui; StringParityTest checks
        // those.
        error += listOf("MissingTranslation", "ExtraTranslation")
    }
}

// The Drive wiring's tests run the real services on the same in-memory Drive as :shared's tests (S4b-BL-117/-118): two
// files of :shared's commonTest (the fake and its faults) are compiled into this module's unit tests; the rest of that folder is not.
android.sourceSets.getByName("test").kotlin.directories.add("../shared/src/commonTest/kotlin/app/doorprints/drive")
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    exclude { it.file.path.contains("/shared/src/commonTest/") && it.file.name !in setOf("InMemoryFakeDrive.kt", "FakeDriveFaults.kt") }
}

// See the robolectricRuntime dependency below and the unit-test system properties after it.
val robolectricRuntime = configurations.create("robolectricRuntime") { isTransitive = false }

dependencies {
    // Platform-neutral logic, DTOs and the Ktor API client (Sprint 3.5, see ../shared/README.md).
    implementation(project(":shared"))
    // Compose Multiplatform UI: theme, shared composables and UI rules (ADR-23, ../ui/README.md).
    implementation(project(":ui"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    // Room: AppDatabase, its entities and DAOs are in :shared commonMain since CMP-4 P4a (Room KMP, KSP and the schema
    // export run there); room-runtime comes through :shared's api. No direct room-ktx dependency (WorkManager still
    // pulls it in; it is empty since Room 2.7). Transactions use Room's common
    // useWriterConnection { it.immediateTransaction {} } (data/DatabaseTransactions.kt in :shared, CMP-4 P4c).

    implementation(libs.androidx.work.runtime.ktx)
    // SettingsStore is common code in :shared since CMP-4 P4b (datastore-preferences-core); :app opens the file with
    // the Android artifact's preferencesDataStoreFile (data/SettingsStoreFactory.kt).
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)

    implementation(libs.play.services.location)
    // Drive access token on Android (drive/auth, S4b-BL-117): AuthorizationClient, scope drive.file only.
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)
    // JSON in the export, import and Assistant code (the Room checklist converter moved to :shared in CMP-4 P4a).
    implementation(libs.kotlinx.serialization.json)
    // No direct OkHttp dependency any more: HTTP goes through :shared's Ktor client (OkHttp engine, OkHttp 5.x).
    implementation(libs.coil.compose)
    implementation(libs.maplibre.android)

    testImplementation(libs.junit)
    // Screenshot tests on the JVM (docs/06 TC-U-56): Robolectric renders the screens, Roborazzi compares them.
    testImplementation(libs.robolectric)
    // Robolectric's Android image (android-all-instrumented for the SDK the screenshot tests run on, @Config(sdk)),
    // as a Gradle dependency rather than Robolectric's own HTTP fetch at test time: the fetch has no cache the CI
    // and session caches know, and failed once on 2026-09-29 (MavenArtifactFetcher, IOException). Copied into
    // build/robolectric-deps and handed over with robolectric.offline (below).
    robolectricRuntime(libs.robolectric.android.all)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    // SyncServerResetTest: the repository's sync against a fake server (S4b-BL-20); the engine :shared's tests use.
    testImplementation(libs.ktor.client.mock)
    // Room's MigrationTestHelper for AppDatabaseMigrationTest (CMP-4 P4a), with the framework SQLite driver.
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.sqlite.framework)
    debugImplementation(libs.compose.ui.test.manifest)
    // Instrumented smoke tests (docs/06 TC-I-35), run on an emulator by android-emulator.yml.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.services.storage)
    androidTestUtil(libs.androidx.test.services)
}

// CI runs `./gradlew assembleDebug testDebugUnitTest`. :shared is a KMP library whose Android host tests are the task
// :shared:testAndroidHostTest (it has no testDebugUnitTest), so the app's unit-test task pulls them in. That keeps the
// existing CI command covering the commonTest suite (domain rules and the Ktor API contract tests). :ui's host tests
// are pulled in the same way.
tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn(":shared:testAndroidHostTest", ":ui:testAndroidHostTest")
}

// Robolectric offline (docs/06 TC-U-56): the android-all jar comes from the Gradle cache (robolectricRuntime), not
// from an HTTP download while the tests run.
val robolectricDeps = tasks.register<Copy>("copyRobolectricDeps") {
    from(robolectricRuntime)
    into(layout.buildDirectory.dir("robolectric-deps"))
}
tasks.withType<Test>().configureEach {
    dependsOn(robolectricDeps)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-deps").get().asFile.absolutePath)
}
