plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
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
    namespace = "com.househunt.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.househunt.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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

    testOptions {
        // JVM unit tests touch a few android.* classes (e.g. Log in ApiClient); return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Every string must exist in values-hi, values-ta and values-te (docs/05 section 8.2).
        error += listOf("MissingTranslation", "ExtraTranslation")
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.exifinterface:exifinterface:1.4.1")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("org.maplibre.gl:android-sdk:13.6.1")

    testImplementation("junit:junit:4.13.2")
}
