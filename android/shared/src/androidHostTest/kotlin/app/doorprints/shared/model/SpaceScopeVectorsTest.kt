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

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The scoping rules of a space on the phones (kinds PR C, S4b-BL-289), against `docs/schemas/kinds/space-scope-vectors.json`,
 * the file the website's `space-scope.spec.ts` reads: `contains`, `compare`, `fileName`, `assistant`, `shareKind` and `createKind`.
 * Not in commonTest: it reads repository files.
 */
class SpaceScopeVectorsTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private val vectors = Json.parseToJsonElement(repoFile("docs/schemas/kinds/space-scope-vectors.json").readText()).jsonObject
    private fun cases(section: String) = vectors.getValue(section).jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String) = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.booleanOrNull!!
    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }
    private fun JsonElement.candidates() = jsonArray.map {
        val o = it.jsonObject
        ScopedCandidate(o.text("id")!!, o.text("kind"), o.text("status"), o["deleted"]?.jsonPrimitive?.booleanOrNull ?: false)
    }

    @Test
    fun theFormatIsTheOneTheWebsiteReads() {
        assertEquals("doorprints-space-scope-vectors/1", vectors.getValue("format").jsonPrimitive.content)
        assertTrue(cases("contains").size >= 6 && cases("compare").size >= 7 && cases("fileName").size >= 9)
    }

    @Test
    fun aSpaceHoldsTheCandidatesOfItsKindAndAKindWithNoSpaceIsInTheDefaultSpace() {
        for (c in cases("contains")) {
            val holds = SpaceScope.holdsFor(c.text("space")!!, c.flag("multi"), c.text("default")!!, c.strings("available"))
            val got = c.getValue("candidates").candidates().filter { holds(it.kind) }.map { it.id }
            assertEquals(c.text("name"), c.strings("holds"), got)
        }
    }

    @Test
    fun theSpaceViewAnswersTheSameAsTheRule() {
        val view = SpaceView("school", multi = true, default = "house", available = listOf("house", "school"))
        assertTrue(view.holds("school"))
        assertTrue(!view.holds(null) && !view.holds("house"))
        assertTrue(!view.holds("clinic")) // a kind with no space here is in the default space, which is the house
        assertTrue(SpaceView.ONE.holds("school") && SpaceView.ONE.holds(null))
    }

    @Test
    fun comparePicksAreKeptInOrderAndAnotherSpacesAreRefused() {
        for (c in cases("compare")) {
            val holds = SpaceScope.holdsFor(c.text("space")!!, c.flag("multi"), c.text("default")!!, c.strings("available"))
            val pick = SpaceScope.comparePick(c.strings("picked"), c.getValue("candidates").candidates(), holds)
            assertEquals(c.text("name") + ": kept", c.strings("kept"), pick.kept)
            assertEquals(c.text("name") + ": refused", c.strings("refused"), pick.refused)
        }
    }

    @Test
    fun aSpacesCopyPutsTheEnglishPluralBeforeTheDate() {
        for (c in cases("fileName")) {
            assertEquals(c.text("name"), c.text("out"), SpaceScope.spaceFileName(c.text("in")!!, c.text("space"), c.text("many")!!))
        }
    }

    @Test
    fun theBuiltInKindsEnglishPluralsAreTheNounsTheFileNamesUse() {
        assertEquals("Doorprints-schools-2026-09-22-csv.zip", SpaceScope.spaceFileName("Doorprints-2026-09-22-csv.zip", "school", KindSettingRules.pluralOf("school")!!))
        assertEquals("Doorprints-2026-09-22.md", SpaceScope.spaceFileName("Doorprints-2026-09-22.md", "house", KindSettingRules.pluralOf("house")!!))
    }

    @Test
    fun theAssistantIsShownInTheHouseWhenOnAndInAnotherKindOnlyOnThePersonsOwnOrWhereTheServerNamesTheKind() {
        for (c in cases("assistant")) {
            assertEquals(c.text("name"), c.flag("shown"), SpaceScope.aiShownIn(c.text("kind")!!, c.strings("available"), c.flag("on"), c.flag("ownKey"), if ("serverKinds" in c) c.strings("serverKinds") else emptyList()))
        }
    }

    @Test
    fun aSharedListingLandsInTheChosenKindWhenItIsEnabledElseTheDefault() {
        for (c in cases("shareKind")) {
            assertEquals(c.text("name"), c.text("kind"), SpaceScope.shareKind(c.text("chosen"), c.strings("enabled"), c.text("default")!!))
        }
    }

    @Test
    fun aNewEntryIsOfTheSpacesKindWhileItIsEnabledElseTheDefault() {
        val cases = cases("createKind")
        assertEquals(5, cases.size)
        for (c in cases) {
            assertEquals(c.text("name"), c.text("kind"), SpaceScope.createKind(c.text("space")!!, c.strings("enabled"), c.text("default")!!))
        }
    }
}
