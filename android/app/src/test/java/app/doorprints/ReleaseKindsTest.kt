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

package app.doorprints

import app.doorprints.shared.model.KindSetting
import app.doorprints.shared.model.KindSettingRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A release build lists the school and, since 2026-10-20, releases it with the house (S4b-BL-309; owner, 2026-10-15; docs/03 ADR-13b;
 * docs/02 T-E13, docs/13 part J8). The gate is the build and the person's switch, so this reads `android/app/build.gradle.kts`, which the
 * release variant is made from: `defaultConfig` fixes `AVAILABLE_KINDS` to `"house,school"` and `DEV_KINDS` to `false`, and the
 * `release` build type does not change either; the released list ([KindSettingRules.RELEASED]) is the house and the school. Only the
 * debug build type reads `-Pdoorprints.kinds` or `-Pdoorprints.devKinds`. The iPhone lists the same (`IOS_AVAILABLE_KINDS`). No
 * new tool and no release compile: a text check on the config.
 */
class ReleaseKindsTest {
    private val script: String = run {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "app/build.gradle.kts").takeIf { it.isFile } ?: File(dir, "build.gradle.kts").takeIf { it.isFile && it.readText().contains("AVAILABLE_KINDS") }
            if (f != null) return@run f.readText()
            dir = dir.parentFile
        }
        error("android/app/build.gradle.kts not found")
    }

    /** The text of the `{ ... }` block that starts at [header] (braces balanced; the config has none in strings there). */
    private fun block(header: String): String {
        val start = script.indexOf(header)
        assertTrue("$header block not found", start >= 0)
        var depth = 0
        for (i in script.indexOf('{', start) until script.length) {
            if (script[i] == '{') depth++
            if (script[i] == '}' && --depth == 0) return script.substring(start, i + 1)
        }
        error("unbalanced $header")
    }

    @Test
    fun theDefaultConfigListsTheSchoolCompiledInWithTheDeveloperFlagOff() {
        val config = block("defaultConfig")
        assertTrue(config.contains("buildConfigField(\"String\", \"AVAILABLE_KINDS\", \"\\\"house,school\\\"\")"))
        assertTrue(config.contains("buildConfigField(\"boolean\", \"DEV_KINDS\", \"false\")"))
    }

    @Test
    fun theReleaseBuildTypeChangesNeitherAndReadsNoKindsProperty() {
        val release = block("        release {")
        assertFalse(release.contains("AVAILABLE_KINDS"))
        assertFalse(release.contains("DEV_KINDS"))
        assertFalse(release.contains("doorprints.kinds"))
        assertFalse(release.contains("doorprints.devKinds"))
    }

    @Test
    fun onlyTheDebugBuildTypeReadsTheKindsProperties() {
        val debug = block("        debug {")
        assertTrue(debug.contains("doorprints.kinds"))
        // Outside the debug block no property names the kinds.
        assertFalse(script.replace(debug, "").contains("gradleProperty(\"doorprints."))
    }

    /** The kinds a build's `AVAILABLE_KINDS` lists, from the config text. */
    private fun releaseKinds(): String =
        Regex("buildConfigField\\(\"String\", \"AVAILABLE_KINDS\", \"\\\\\"([a-z,]+)\\\\\"\"\\)").find(block("defaultConfig"))!!.groupValues[1]

    @Test
    fun theReleaseReleasesTheHouseAndTheSchoolAndShowsOnlyTheHouseUntilTheSchoolIsShown() {
        assertEquals(listOf("house", "school"), KindSettingRules.availableOf(releaseKinds()))
        assertEquals(listOf("house", "school"), KindSettingRules.RELEASED)
        // The school is offered with no switch but shown only when the person shows it: with nothing stored, the release's setting is the house app.
        val s = KindSetting(KindSettingRules.availableOf(releaseKinds()))
        assertEquals(listOf("house", "school"), s.offered)
        assertEquals(listOf("house"), s.enabled)
        assertFalse(s.spaces)
        // Every listed kind is released, so Settings hides Experimental: more than houses (the mechanism stays for the next kind).
        assertFalse(s.hasUnreleased)
    }

    @Test
    fun theSyntheticConformanceKindIsInNoReleaseList() {
        assertFalse(releaseKinds().contains("zz_all"))
        assertFalse(KindSettingRules.availableOf(releaseKinds()).contains("zz_all"))
        assertFalse(KindSettingRules.RELEASED.contains("zz_all"))
    }

    @Test
    fun theDebugBuildListsWhatTheReleaseListsAndOnlyAnExplicitPropertyTurnsTheDeveloperFlagOn() {
        val debug = block("        debug {")
        assertTrue(debug.contains("val kinds = named ?: \"${releaseKinds()}\""))
        assertTrue("DEV_KINDS follows the properties, never the shipped list", debug.contains("val devKinds = shipsMore || providers.gradleProperty(\"doorprints.devKinds\").orNull == \"true\""))
    }

    /**
     * S4b-BL-300, rewritten for "compiled in, off" (owner, 2026-10-15): the iPhone lists the same kinds as the Android release,
     * from one constant, `IOS_AVAILABLE_KINDS`, which only `iosSettingsStore()` (the app's store) passes on; the simulator tests'
     * store and the Swift shell name no kind, and Info.plist is untouched by the list. `IosKindsGateTest` (`:ui`) holds the rest.
     */
    @Test
    fun theIphoneListsTheSameKindsAsTheReleaseFromOneConstant() {
        fun fileUp(relative: String): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                File(dir, relative).takeIf { it.isFile }?.let { return it }
                dir = dir.parentFile
            }
            error("$relative not found")
        }
        val ios = fileUp("shared/src/iosMain/kotlin/app/doorprints/data/SettingsIos.kt").readText()
        assertTrue("the iPhone's list is the release's", ios.contains("internal const val IOS_AVAILABLE_KINDS = \"${releaseKinds()}\""))
        assertTrue("the app's store passes the list", ios.contains("availableKinds = KindSettingRules.availableOf(IOS_AVAILABLE_KINDS)"))
        assertTrue("the store's default must stay the house alone", fileUp("shared/src/commonMain/kotlin/app/doorprints/data/Settings.kt").readText()
            .contains("val availableKinds: List<String> = listOf(Candidates.BASELINE_KIND)"))
        val shell = File(fileUp("shared/build.gradle.kts").parentFile.parentFile.parentFile, "ios/Doorprints")
        if (shell.isDirectory) {
            val swift = shell.walkTopDown().filter { it.isFile && it.extension == "swift" }.map { it.readText() }.joinToString("\n")
            assertFalse("the Swift shell names a kind: the list is Kotlin's", Regex("availableKinds|devKinds", RegexOption.IGNORE_CASE).containsMatchIn(swift))
        }
    }
}
