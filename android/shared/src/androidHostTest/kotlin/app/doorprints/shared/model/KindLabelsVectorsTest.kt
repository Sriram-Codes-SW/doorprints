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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * [KindLabels] against the shared resolver vectors `docs/schemas/kinds/kind-label-vectors.json` (S4b-BL-207, docs/06
 * TC-U-209): every case resolves to the pinned Android key and visibility, and that key shows, in the four Compose
 * dictionaries, the words `kind-vectors.json` pins, so the phone's labels for a house are today's in en, hi, ta and te.
 * Also: the resolver's copy of `house.json` is the file's (keys, cadence keys, sections, nothing hidden), and the
 * visibility rules on a kind that hides a section and a field. The website's twin is `kind-labels.spec.ts`; `:ui`'s
 * `KindLabelsScreensTest` checks the screens' resources. Not in commonTest: it reads repository files.
 */
class KindLabelsVectorsTest {
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
    private val vectors = json("docs/schemas/kinds/kind-label-vectors.json")
    private val cases = vectors.getValue("cases").jsonArray.map { it.jsonObject }
    private val pinned = json("docs/schemas/kinds/kind-vectors.json").getValue("kinds").jsonArray.first().jsonObject
        .getValue("labels").jsonObject.getValue("android").jsonObject
    private val house = json("docs/schemas/kinds/house.json")

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonElement.orNull(): String? = if (this is JsonNull) null else jsonPrimitive.content

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

    @Test
    fun everyCaseResolvesToThePinnedKeyAndVisibility() {
        assertEquals("doorprints-kind-label-vectors/1", vectors.str("format"))
        for (case in cases) {
            val name = case.str("name")
            val resolved = KindLabels.of(case.str("kind"))?.resolve(case.str("ref"), case["cadence"]?.orNull())
            if (case["resolved"] is JsonNull) {
                assertNull(name, resolved)
                assertFalse(name, KindLabels.of(case.str("kind"))?.isVisible(case.str("ref")) ?: false)
                continue
            }
            assertNotNull(name, resolved)
            assertEquals(name, case.getValue("android").orNull(), resolved!!.key)
            assertEquals(name, case.getValue("visible").jsonPrimitive.boolean, resolved.visible)
        }
    }

    @Test
    fun theResolvedLabelsShowTodaysWordsInTheFourLanguages() {
        val resolving = cases.filter { it["resolved"] !is JsonNull }
        assertEquals(pinned.getValue("en").jsonObject.keys.sorted(), resolving.map { it.str("vector") }.distinct().sorted())
        for (lang in listOf("en", "hi", "ta", "te")) {
            val dict = composeStrings(lang)
            val want = pinned.getValue(lang).jsonObject
            for (case in resolving) {
                val key = KindLabels.of(case.str("kind"))!!.resolve(case.str("ref"), case["cadence"]?.orNull())!!.key
                val text = key?.let { assertNotNull("$lang ${case.str("name")}: $key", dict[it]); dict[it] }
                assertEquals("$lang ${case.str("name")}", want.getValue(case.str("vector")).orNull(), text)
            }
        }
    }

    @Test
    fun theHouseCopyIsTheKindFile() {
        val want = LinkedHashMap<String, String?>()
        val byCadence = LinkedHashMap<String, String>()
        val sections = LinkedHashMap<String, String>()
        fun key(label: JsonElement) = label.jsonObject.getValue("android").orNull()
        house.getValue("labels").jsonObject.forEach { (id, l) -> want["kind.$id"] = key(l) }
        house.getValue("statuses").jsonArray.forEach { want["status.${it.jsonObject.str("id")}"] = key(it.jsonObject.getValue("label")) }
        house.getValue("sections").jsonArray.forEach {
            want["section.${it.jsonObject.str("id")}"] = key(it.jsonObject.getValue("label"))
            assertTrue(it.jsonObject["visible"]?.jsonPrimitive?.boolean ?: true)
        }
        for (f in house.getValue("core").jsonArray + house.getValue("extras").jsonArray) {
            val field = f.jsonObject
            val id = field.str("id")
            want["field.$id"] = key(field.getValue("label"))
            sections[id] = field.str("section")
            assertTrue(id, field["visible"]?.jsonPrimitive?.boolean ?: true)
            field.getValue("label").jsonObject["byCadence"]?.jsonObject?.forEach { (c, alt) ->
                alt.jsonObject["android"]?.orNull()?.let { byCadence["field.$id@$c"] = it }
            }
            field["cadences"]?.jsonArray?.mapNotNull { it as? JsonObject }?.forEach { want["cadence.${it.str("id")}"] = key(it.getValue("label")) }
        }
        house.getValue("checklist").jsonArray.forEach { want["check.${it.jsonObject.str("key")}"] = key(it.jsonObject.getValue("label")) }

        val labels = KindLabels.HOUSE
        assertEquals("house", labels.kind)
        assertEquals(want.keys.sorted(), labels.refs.sorted())
        for ((ref, k) in want) assertEquals(ref, KindLabel(k, true), labels.resolve(ref))
        for ((ref, k) in byCadence) assertEquals(ref, k, labels.resolve(ref.substringBefore('@'), ref.substringAfter('@'))!!.key)
        assertEquals(mapOf("field.money@month" to "house_price_rent"), byCadence)
        for ((id, section) in sections) {
            // A field's section shows, so the field does; hiding the section would hide it (below).
            assertTrue(id, labels.isVisible("section.$section"))
            assertTrue(id, labels.field(id)!!.visible)
        }
        for (s in HouseStatus.entries) assertEquals(want["status.${s.name}"], labels.status(s))
        for (k in Checklist.keys) assertEquals(want["check.$k"], labels.check(k))
        assertNull(labels.check("playground"))
        assertEquals(KindLabel("house_cost", true), labels.section("cost"))
        assertEquals(KindLabel("house_price_rent", true), labels.field("money", "month"))
        assertEquals(KindLabels.HOUSE, KindLabels.of("house"))
    }

    @Test
    fun aHiddenSectionHidesItsFieldsAndAHiddenFieldKeepsItsLabel() {
        val kind = KindLabels(
            kind = "test",
            keys = mapOf("section.cost" to "house_cost", "section.details" to null, "field.deposit" to "house_deposit",
                "field.label" to "house_name", "field.notes" to "house_notes", "status.NEW" to "status_NEW"),
            byCadence = emptyMap(),
            sections = mapOf("deposit" to "cost", "label" to "details", "notes" to "details"),
            hidden = setOf("section.cost", "field.notes"),
        )
        assertEquals(KindLabel("house_cost", false), kind.section("cost"))
        assertEquals(KindLabel("house_deposit", false), kind.field("deposit"))
        assertEquals(KindLabel("house_notes", false), kind.field("notes"))
        assertEquals(KindLabel("house_name", true), kind.field("label"))
        assertEquals(KindLabel(null, true), kind.section("details"))
        assertEquals("status_NEW", kind.status(HouseStatus.NEW))
        assertNull(kind.field("money"))
        assertFalse(kind.isVisible("field.money"))
        assertFalse(kind.isVisible("section.photos"))
    }
}
