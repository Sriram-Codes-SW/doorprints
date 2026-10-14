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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A release build ships the house alone (S4b-BL-309; docs/02 T-E13, docs/13 part J1). The gate is the build, so this reads
 * `android/app/build.gradle.kts`, which the release variant is made from: `defaultConfig` fixes `AVAILABLE_KINDS` to
 * `"house"` and `DEV_KINDS` to `false`, and the `release` build type does not change either. Only the debug build type
 * reads `-Pdoorprints.kinds` or `-Pdoorprints.devKinds`. No new tool and no release compile: a text check on the config.
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
    fun theDefaultConfigShipsTheHouseAloneWithTheDeveloperFlagOff() {
        val config = block("defaultConfig")
        assertTrue(config.contains("buildConfigField(\"String\", \"AVAILABLE_KINDS\", \"\\\"house\\\"\")"))
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
}
