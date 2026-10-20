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

package app.doorprints.shared.model

import app.doorprints.shared.export.BackupValidation
import app.doorprints.shared.export.BackupWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Which kinds the phone shows (kinds PR C, S4b-BL-274; docs/03 §18.12), against `docs/schemas/kinds/search-kind-vectors.json`
 * format 2, the file the website's `kind-setting.spec.ts` reads: `setting` (what the resolver answers for the build's
 * kinds and the stored values), `write` (the backup's `search` object, byte for byte) and `read` (every reader reads past
 * it). Not in commonTest: it reads repository files.
 */
class KindSettingVectorsTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val vectors = Json.parseToJsonElement(repoFile("docs/schemas/kinds/search-kind-vectors.json").readText()).jsonObject
    private fun cases(section: String) = vectors.getValue(section).jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String) = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun theFormatIsTheSecondOneTheWebsiteReads() {
        assertEquals("doorprints-search-kind-vectors/2", vectors.getValue("format").jsonPrimitive.content)
    }

    @Test
    fun whatTheResolverAnswersForTheBuildsKindsAndTheStoredValues() {
        for (c in cases("setting")) {
            // The vectors describe a build with the school unreleased (the website's specs pin its released set the same way).
            val s = KindSetting(c.strings("available"), c.text("experimental"), c.text("enabled"), c.text("default"), released = listOf("house"))
            val name = c.text("name")
            assertEquals("$name: offered", c.strings("offered"), s.offered)
            assertEquals("$name: enabled", c.strings("enabledKinds"), s.enabled)
            assertEquals("$name: default", c.text("defaultKind"), s.default)
            assertEquals("$name: spaces", c.getValue("spaces").jsonPrimitive.booleanOrNull, s.spaces)
        }
    }

    @Test
    fun theBackupsSearchObjectIsWrittenByteForByte() {
        for (c in cases("write")) {
            assertEquals(c.text("name"), c.text("search"), KindSettingRules.searchOf(c.strings("kinds"), c.text("defaultKind")!!))
        }
    }

    @Test
    fun everyReaderReadsPastWhatTheSearchObjectHoldsAndNeverChangesACandidate() {
        val text = repoFile("docs/schemas/backup-sample.json").readText()
        val sample = Json.parseToJsonElement(text).jsonObject
        val want = BackupWire.decodeData(text)
        for (c in cases("read")) {
            val fields = LinkedHashMap(sample)
            if (c["absent"] == null) fields["search"] = c.getValue("search") else fields.remove("search")
            val data = BackupWire.decodeData(JsonObject(fields).toString())
            assertEquals(c.text("name"), want, data)
            assertNull(c.text("name"), BackupValidation.checkData(data))
        }
    }

    @Test
    fun aPhoneThatNeverChoseTakesTheKindsAFileNamesOnlyWhenTheyAreWellFormed() {
        assertEquals(listOf("house", "school") to "school", KindSettingRules.spacesOf(listOf("house", "school"), "school"))
        assertNull(KindSettingRules.spacesOf(listOf("house"), "house"))
        assertNull(KindSettingRules.spacesOf(listOf("house", "school"), "clinic"))
        assertNull(KindSettingRules.spacesOf(null, null))
    }

    @Test
    fun theSettersFollowTheWebsitesRules() {
        val both = KindSetting(listOf("house", "school"), released = listOf("house"))
        assertEquals("a production build keeps the house whatever is stored", listOf("house"), KindSetting(listOf("house"), "1", "[\"house\",\"school\"]", "school").enabled)
        val on = both.withExperimental(true)
        assertEquals("1", on.experimental)
        assertNull(on.withExperimental(false).experimental)
        val two = on.withKindEnabled("school", true)
        assertEquals("[\"house\",\"school\"]", two.enabledText)
        assertEquals(listOf("house", "school"), two.enabled)
        // The last enabled kind cannot be turned off.
        assertEquals(two.withKindEnabled("house", false).withKindEnabled("school", false).enabled, listOf("school"))
        // Turning off the default makes the next enabled kind the default.
        val schoolDefault = two.withDefault("school")
        assertEquals("school", schoolDefault.defaultText)
        assertEquals("house", schoolDefault.withKindEnabled("school", false).default)
        assertNull("the house alone is stored as no list", schoolDefault.withKindEnabled("school", false).enabledText)
        // A default that is not enabled is refused; the house as default is stored as no value.
        assertEquals(on.defaultText, on.withDefault("school").defaultText)
        assertNull(schoolDefault.withDefault("house").defaultText)
        // A kind that is not offered cannot be turned on.
        assertEquals(both, both.withKindEnabled("school", true))
    }

    @Test
    fun aBuildListsTheHouseAlwaysAndTheBuiltInKindsItNames() {
        assertEquals(listOf("house"), KindSettingRules.availableOf(null))
        assertEquals(listOf("house"), KindSettingRules.availableOf("clinic"))
        assertEquals(listOf("house", "school"), KindSettingRules.availableOf("school"))
        assertEquals(listOf("house", "school"), KindSettingRules.availableOf("school, house"))
    }
}
