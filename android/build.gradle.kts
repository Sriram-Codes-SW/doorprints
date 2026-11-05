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

// Build-tooling security pins (Dependabot alerts of 2026-10-21, docs/ops/dependabot-triage-2026-10-21.md). AGP 9.4.1 brings
// Bouncy Castle 1.80.2 (signing), jose4j 0.9.5 and jdom2 2.0.6 onto the Gradle plugin classpath, and its lint tool brings
// Bouncy Castle 1.80.2 too. None of them reaches the APK; the pins lift the tooling to the patched versions.
// Re-check on every AGP bump: remove a pin once AGP's own version is at or above it.
buildscript {
    dependencies {
        constraints {
            classpath("org.bouncycastle:bcprov-jdk18on:1.86") { because("GHSA-9pwp-9qqc-pr26, GHSA-qp49-qgx5-5m26") }
            classpath("org.bouncycastle:bcpkix-jdk18on:1.86") { because("GHSA-wg6q-6289-32hp") }
            classpath("org.bouncycastle:bcutil-jdk18on:1.86") { because("same release train as bcprov and bcpkix") }
            classpath("org.bitbucket.b_c:jose4j:0.9.7") { because("GHSA-3677-xxcr-wjqv") }
            classpath("org.jdom:jdom2:2.0.6.1") { because("GHSA-2363-cqg2-863c") }
            classpath("org.apache.httpcomponents:httpclient:4.5.14") { because("GHSA-7r82-7xv7-xcpj") }
            classpath("org.apache.commons:commons-lang3:3.21.0") { because("GHSA-j288-q9x7-2f5v") }
            classpath("io.opentelemetry:opentelemetry-api:1.67.0") { because("GHSA-rcgg-9c38-7xpx") }
        }
    }
}

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
    alias(libs.plugins.androidx.room) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

// The same pins for the lint tool (androidLintTool), which every Android module resolves on its own.
subprojects {
    configurations.configureEach {
        // Not on any APK or test classpath here (checked); if a tool configuration brings it in, it gets the patched API.
        resolutionStrategy.eachDependency {
            if (requested.group == "io.opentelemetry" && requested.name == "opentelemetry-api") useVersion("1.67.0")
        }
        if (name == "androidLintTool") {
            resolutionStrategy.eachDependency {
                when ("${requested.group}:${requested.name}") {
                    "org.apache.httpcomponents:httpclient" -> useVersion("4.5.14")
                    "org.apache.commons:commons-lang3" -> useVersion("3.21.0")
                    "org.bouncycastle:bcprov-jdk18on", "org.bouncycastle:bcpkix-jdk18on", "org.bouncycastle:bcutil-jdk18on" ->
                        useVersion("1.85")
                    "org.bitbucket.b_c:jose4j" -> useVersion("0.9.7")
                    "org.jdom:jdom2" -> useVersion("2.0.6.1")
                }
            }
        }
    }
}
