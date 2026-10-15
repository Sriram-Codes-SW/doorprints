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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * `docs/schemas/kinds/kind-word-vectors.json` (S4b-BL-316): the phone words of the kind files that are not the sixteen of
 * S4b-BL-278, as the house had them before `SpaceWords` and `PageWords` stopped holding tables. The house's keys and its texts
 * in en, hi, ta and te are pinned, so a house space is word for word what it was (the Roborazzi baselines say the same in
 * pixels); the files' `words` and [KindWordKeys] are the vectors'. The website's `kind-words.spec.ts` reads the same file.
 */
class KindWordVectorsTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private fun json(relative: String) = Json.parseToJsonElement(repoFile(relative).readText()).jsonObject
    private val vectors = json("docs/schemas/kinds/kind-word-vectors.json")
    private val words = vectors.getValue("words").jsonArray.map { it.jsonObject }

    private fun composeStrings(lang: String): Map<String, String> {
        val dir = if (lang == "en") "values" else "values-$lang"
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(repoFile("android/ui/src/commonMain/composeResources/$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i)
            e.attributes.getNamedItem("name").nodeValue to e.textContent.replace("\\'", "'").replace("\\\"", "\"")
        }
    }

    private fun JsonObject.keyOrNull(name: String) = getValue(name).let { if (it is JsonNull) null else it.jsonPrimitive.content }

    @Test
    fun theVectorsAreTheIdsAfterTheSixteenInTheSchemasOrder() {
        assertEquals("doorprints-kind-word-vectors/1", vectors.getValue("format").jsonPrimitive.content)
        assertEquals(KindWordKeys.IDS.drop(16), words.map { it.getValue("id").jsonPrimitive.content })
    }

    @Test
    fun theHousesKeysAndTextsAreAsTheyWereInFourLanguages() {
        for (lang in listOf("en", "hi", "ta", "te")) {
            val dict = composeStrings(lang)
            for (w in words) {
                val id = w.getValue("id").jsonPrimitive.content
                val house = w.getValue("house").jsonObject
                val key = house.keyOrNull("android")
                assertEquals(id, key, KindWordKeys.HOUSE[id])
                assertEquals("$lang $id ($key)", house.getValue("text").jsonObject.getValue(lang).jsonPrimitive.content, dict[key])
            }
        }
    }

    @Test
    fun theSchoolsKeysAndEnglishTextsArePinnedAndEveryKeyExists() {
        val en = composeStrings("en")
        for (w in words) {
            val id = w.getValue("id").jsonPrimitive.content
            val school = w.getValue("school").jsonObject
            val key = school.keyOrNull("android")
            assertEquals(id, key, KindWordKeys.SCHOOL[id])
            assertNotNull("$id ($key) is not a Compose string", en[key])
            assertEquals(id, school.getValue("text").jsonObject.getValue("en").jsonPrimitive.content, en[key])
        }
    }

    @Test
    fun theKindFilesHoldTheVectorsKeys() {
        for ((file, kindKey) in listOf("house" to "house", "school" to "school")) {
            val onDisk = json("docs/schemas/kinds/$file.json").getValue("words").jsonObject
            for (w in words) {
                val id = w.getValue("id").jsonPrimitive.content
                val v = w.getValue(kindKey).jsonObject
                val entry = onDisk.getValue(id).jsonObject
                assertEquals("$file $id web", v.keyOrNull("web"), entry.keyOrNull("web"))
                assertEquals("$file $id android", v.keyOrNull("android"), entry.keyOrNull("android"))
            }
        }
    }
}
