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

import app.doorprints.shared.export.ExportHouse
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The kind file for houses, `docs/schemas/kinds/house.json` (docs/03 ADR-36 §18, S4b-BL-205), against the phones'
 * code and strings: every Android label key resolves in the four Compose dictionaries to the text that
 * `kind-vectors.json` pins; the statuses, checklist, cost fields, value ranges and the backup's house keys are the
 * code's; [HouseSearch.fields] reads exactly what the file's `search` names. The web's `house-kind-file.spec.ts`
 * validates the file against `kind.schema.json` and checks the web side; the server's `HouseKindFileTest` the
 * server's. Not in commonTest: it reads repository files.
 */
class HouseKindFileTest {
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
    private val kind = json("docs/schemas/kinds/house.json")
    private val vectors = json("docs/schemas/kinds/kind-vectors.json").getValue("kinds").jsonArray.first().jsonObject

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.list(key: String) = getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }
    private val fields get() = kind.list("core") + kind.list("extras")

    /** The Compose strings of one language, decoded as the resource compiler does for these plain texts. */
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

    /** Every label of the kind file under its vector id (`kind.new`, `field.money@month`, `check.water`, ...). */
    private fun labelRefs(): List<Pair<String, JsonObject>> {
        val refs = ArrayList<Pair<String, JsonObject>>()
        kind.getValue("labels").jsonObject.forEach { (id, l) -> refs += "kind.$id" to l.jsonObject }
        kind.list("statuses").forEach { refs += "status.${it.str("id")}" to it.getValue("label").jsonObject }
        kind.list("sections").forEach { refs += "section.${it.str("id")}" to it.getValue("label").jsonObject }
        for (f in fields) {
            val label = f.getValue("label").jsonObject
            refs += "field.${f.str("id")}" to label
            label["byCadence"]?.jsonObject?.forEach { (c, alt) -> refs += "field.${f.str("id")}@$c" to JsonObject(label + alt.jsonObject) }
            f["cadences"]?.jsonArray?.mapNotNull { it as? JsonObject }?.forEach { refs += "cadence.${it.str("id")}" to it.getValue("label").jsonObject }
        }
        kind.list("checklist").forEach { refs += "check.${it.str("key")}" to it.getValue("label").jsonObject }
        return refs
    }

    @Test
    fun everyAndroidLabelResolvesInTheFourLanguagesToThePinnedText() {
        val pinned = vectors.getValue("labels").jsonObject.getValue("android").jsonObject
        val refs = labelRefs()
        assertEquals(pinned.getValue("en").jsonObject.keys.sorted(), refs.map { it.first }.sorted())
        for (lang in listOf("en", "hi", "ta", "te")) {
            val dict = composeStrings(lang)
            val want = pinned.getValue(lang).jsonObject
            for ((id, ref) in refs) {
                val key: JsonElement = ref.getValue("android")
                val resolved = if (key is JsonNull) null else dict[key.jsonPrimitive.content]
                if (key !is JsonNull) assertNotNull("$lang $id ($key) is not a Compose string", resolved)
                val expected = want.getValue(id).let { if (it is JsonNull) null else it.jsonPrimitive.content }
                assertEquals("$lang $id", expected, resolved)
            }
        }
    }

    @Test
    fun theStatusesChecklistAndRangesAreTheCodes() {
        assertEquals("doorprints-kind/1", kind.str("format"))
        assertEquals(HouseStatus.entries.map { it.name }, kind.list("statuses").map { it.str("id") })
        for (s in HouseStatus.entries) assertEquals(s, HouseStatus.fromWire(s.name))
        assertEquals(Checklist.keys, kind.list("checklist").map { it.str("key") })
        assertEquals(Checklist.keys, vectors.strings("checklist"))
        val extra = kind.list("extras").associateBy { it.str("id") }
        assertEquals(1, extra.getValue("areaSqft").getValue("min").jsonPrimitive.int)
        assertEquals(HouseValues.MAX_AREA_SQFT, extra.getValue("areaSqft").getValue("max").jsonPrimitive.int)
        assertEquals(HouseValues.MIN_FLOOR, extra.getValue("floor").getValue("min").jsonPrimitive.int)
        assertEquals(HouseValues.MAX_FLOOR, extra.getValue("floor").getValue("max").jsonPrimitive.int)
        for (f in extra.values) {
            if (f.str("type") == "money") assertEquals(f.str("id"), HouseCost.MAX_RUPEES, f.getValue("max").jsonPrimitive.long)
            if (f.str("id").endsWith("Months")) assertEquals(f.str("id"), HouseCost.MAX_MONTHS, f.getValue("max").jsonPrimitive.int)
        }
    }

    @Test
    fun everyBackupHouseKeyHasAPlaceInTheKindFile() {
        val was = fields.flatMap { f -> (f["was"]?.jsonPrimitive?.content ?: f.str("id")).split(", ") }
        assertEquals(
            HouseCost.serializer().descriptor.elementNames.map { "cost.$it" },
            kind.list("extras").filter { it.str("section") == "cost" }.map { it.str("was") },
        )
        val nested = setOf("id", "cost", "rooms", "answers", "moveIn", "checklist", "createdAt", "updatedAt")
        assertEquals(
            ExportHouse.serializer().descriptor.elementNames.filterNot { it in nested }.sorted(),
            was.filterNot { it.startsWith("cost.") }.sorted(),
        )
    }

    @Test
    fun searchReadsExactlyWhatTheKindFileNames() {
        fun token(id: String) = "zq${id.lowercase()}"
        val text = HouseSearch.fields(
            label = token("label"), address = token("address"), street = token("street"), locality = token("locality"),
            notes = token("notes"), contactName = token("contactName"), brokerText = token("brokerId"),
            rooms = listOf(HouseRoom(id = "r1", type = "HALL", name = token("rooms"))),
            answers = listOf(HouseAnswer(id = "a1", text = token("questions"))),
            noteTexts = listOf(token("areaNotes")), moveIn = MoveIn(notes = token("moveIn")), floor = 9,
        ).joinToString(" ")
        val search = vectors.strings("search")
        assertEquals(kind.strings("search").sorted(), search)
        for (id in search.filter { it != "floor" }) assertTrue(id, text.contains(token(id)))
        assertTrue(text.contains("floor 9"))
        // A phone number, a link, a price or a cost has no parameter here: not searched, as the vectors say.
        val notSearched = vectors.strings("notSearched")
        for (id in listOf("contactPhone", "listingUrl", "money", "areaSqft", "deposit")) assertTrue(id, id in notSearched)
        assertFalse(search.any { it in notSearched })
    }
}
