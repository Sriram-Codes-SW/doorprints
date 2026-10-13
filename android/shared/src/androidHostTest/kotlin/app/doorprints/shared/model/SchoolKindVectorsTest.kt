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

import app.doorprints.data.toDto
import app.doorprints.data.toEntity
import app.doorprints.data.toExport
import app.doorprints.shared.api.HouseDto
import app.doorprints.shared.export.BackupWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The hidden schools kind on the phone and the iPhone (kinds PR 5, S4b-BL-209; docs/03 ADR-36 §18.9, docs/06 TC-U-211),
 * against `docs/schemas/kinds/school.json` and `school-vectors.json`, as the website's `school-kind.spec.ts` does: the
 * copy in [SchoolKind] is the file's Android keys and extras; every key shows the vectors' words in the four Compose
 * dictionaries; the hidden places resolve to nothing; [DevKinds] offers and draws the school only while the flag is on;
 * and a school goes through a backup (read into the import's rows, a Room row and back, byte for byte), the sync API's
 * candidate and back unchanged. Not in commonTest: it reads repository files.
 */
class SchoolKindVectorsTest {
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
    private val school = json("docs/schemas/kinds/school.json")
    private val vectors = json("docs/schemas/kinds/school-vectors.json")
    private val kind = vectors.getValue("kind").jsonObject

    private fun JsonObject.str(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonElement.android(): String? = jsonObject.getValue("android").let { if (it is JsonNull) null else it.jsonPrimitive.content }
    private fun JsonObject.list(key: String) = this[key]?.jsonArray?.map { it.jsonPrimitive.content }

    /** Every label of the kind file under its vector ref (`option.<extra>.<id>` for an enum's options), Android keys. */
    private fun labelRefs(): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        school.getValue("labels").jsonObject.forEach { (id, l) -> out["kind.$id"] = l.android() }
        school.getValue("statuses").jsonArray.forEach { out["status.${it.jsonObject.str("id")}"] = it.jsonObject.getValue("label").android() }
        school.getValue("sections").jsonArray.forEach { out["section.${it.jsonObject.str("id")}"] = it.jsonObject.getValue("label").android() }
        for (f in school.getValue("core").jsonArray + school.getValue("extras").jsonArray) {
            val field = f.jsonObject
            out["field.${field.str("id")}"] = field.getValue("label").android()
            field["options"]?.jsonArray?.forEach { o -> out["option.${field.str("id")}.${o.jsonObject.str("id")}"] = o.jsonObject.getValue("label").android() }
        }
        return out
    }

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
    fun theCopyIsTheKindFilesAndroidKeysAndExtras() {
        val want = labelRefs().filterKeys { !it.startsWith("option.") }
        assertEquals(want.keys.sorted(), SchoolKind.LABELS.refs.sorted())
        for ((ref, key) in want) assertEquals(ref, key, SchoolKind.LABELS.resolve(ref)!!.key)
        val extras = school.getValue("extras").jsonArray.map { it.jsonObject }
        assertEquals(extras.map { it.str("id") }, SchoolKind.FIELDS.extras.map { it.id })
        for ((f, e) in extras.zip(SchoolKind.FIELDS.extras)) {
            assertEquals(e.id, f.str("type"), e.type)
            assertEquals(e.id, f.str("section"), e.section)
            assertEquals(e.id, f.str("order")?.toInt(), e.order)
            assertEquals(e.id, f.str("min")?.toLong(), e.min)
            assertEquals(e.id, f.str("max")?.toLong(), e.max)
            assertEquals(e.id, f.str("unit"), e.unit)
            assertEquals(e.id, f.str("maxLength")?.toInt(), e.maxLength)
            assertEquals(e.id, f.list("cadences"), e.cadences)
            assertNull(e.id, e.was)
            assertEquals(e.id, f["options"]?.jsonArray?.map { it.jsonObject.str("id") to it.jsonObject.getValue("label").android() } ?: emptyList<Pair<String?, String?>>(),
                e.options.map { it.id to it.label })
        }
    }

    @Test
    fun everyKeyShowsTheVectorsWordsInTheFourLanguages() {
        val words = kind.getValue("labels").jsonObject.getValue("android").jsonObject
        for (lang in listOf("en", "hi", "ta", "te")) {
            val dict = composeStrings(lang)
            val want = words.getValue(lang).jsonObject
            for ((ref, key) in labelRefs()) {
                val text = key?.let { assertNotNull("$lang $ref: $key", dict[it]); dict[it] }
                assertEquals("$lang $ref", want.getValue(ref).let { if (it is JsonNull) null else it.jsonPrimitive.content }, text)
            }
        }
    }

    @Test
    fun theHiddenPlacesResolveToNothingAndTheRendererShowsTheVectorsRows() {
        val hidden = kind.getValue("hidden").jsonObject
        for (s in hidden.list("sections")!!) assertFalse(s, SchoolKind.LABELS.isVisible("section.$s"))
        for (f in hidden.list("fields")!!) assertNull(f, SchoolKind.LABELS.field(f))
        for (r in vectors.getValue("rows").jsonArray.map { it.jsonObject }.filter { it.str("stack") == "android" }) {
            val want = r.getValue("rows").jsonArray.flatMap { row -> row.jsonArray.map { it.jsonPrimitive.content } }
            assertEquals(r.str("section"), want, SchoolKind.FIELDS.shown(r.str("section")!!, null).map { it.id })
        }
        for (v in vectors.getValue("fields").jsonArray.map { it.jsonObject }) {
            val e = SchoolKind.FIELDS.extras.single { it.id == v.str("id") }
            assertEquals(e.id, v.str("control")!!.uppercase(), e.control.name)
            assertEquals(e.id, v.getValue("android").jsonObject.str("label"), SchoolKind.FIELDS.label(e))
        }
    }

    @Test
    fun theFlagOffOffersAndDrawsOnlyTheHouse() {
        assertEquals(listOf(BuiltInKind.HOUSE), DevKinds.creatable(false))
        assertEquals(BuiltInKind.HOUSE, DevKinds.of("school", false))
        assertEquals(BuiltInKind.HOUSE, DevKinds.of(null, true))
        assertNull(KindLabels.of("school"))
        assertNull(KindFields.of("school"))
        assertEquals(listOf("house", "school"), DevKinds.creatable(true).map { it.id })
        val on = DevKinds.of("school", true)
        assertEquals(SchoolKind.LABELS, on.labels)
        // The listing extract fills a school since kinds PR 7 (S4b-BL-211): the kind file's `ai.extract` is not empty.
        assertEquals(school["ai"]!!.jsonObject["extract"]!!.jsonArray.isNotEmpty(), on.extracts)
        assertTrue(on.extracts)
        assertTrue(BuiltInKind.HOUSE.extracts)
    }

    @Test
    fun aSchoolInABackupGoesThroughTheStoreAndIsWrittenBackByteForByte() {
        val backup = vectors.getValue("backup").jsonObject
        val data = BackupWire.decodeData(backup)
        val stored = data.houses.map { it.toEntity(dirty = false) }
        assertEquals("school", stored.single().kind)
        val again = data.copy(houses = stored.map { it.toExport() })
        val written = Json.parseToJsonElement(BackupWire.encodeData(again)).jsonObject
        assertEquals(backup.getValue("candidates").toString(), written.getValue("candidates").toString())
    }

    @Test
    fun aSchoolOnTheWireIsKeptByTheSyncApisMapping() {
        val wire = vectors.getValue("candidate").jsonObject
        val api = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val back = api.decodeFromJsonElement(HouseDto.serializer(), wire).toEntity().toDto()
        assertEquals("school", back.kind)
        assertEquals(wire.getValue("extras"), back.extras)
        assertNull(back.money)
        assertEquals(wire.getValue("label").jsonPrimitive.content, back.label)
        assertEquals(wire.getValue("rating").jsonPrimitive.longOrNull?.toInt(), back.rating)
    }
}
