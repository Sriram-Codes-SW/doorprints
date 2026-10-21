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

import app.doorprints.data.AppSettings
import app.doorprints.shared.export.ExportTable
import app.doorprints.shared.export.TableOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The kind files' `capabilities` (docs/03 ADR-36 section 18.19), the twin of the website's `kind-capabilities.spec.ts`,
 * against `docs/schemas/kinds/capabilities-vectors.json`: each built-in kind's resolved set is what its kind file
 * declares and what the vectors pin; the house's is today's hard-coded behaviour (Hunt's 30 m default, the checklist keys,
 * the sections it shows, the broker rule); an unknown kind has the defaults; valid objects resolve as pinned and invalid
 * ones, a `__proto__` key among them, are refused by the strict reader. Not in commonTest: it reads repository files.
 */
class KindCapabilitiesVectorsTest {
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
    private val vectors = json("docs/schemas/kinds/capabilities-vectors.json")
    private val files = mapOf("house" to json("docs/schemas/kinds/house.json"), "school" to json("docs/schemas/kinds/school.json"))
    private val schema = json("docs/schemas/kinds/kind.schema.json")

    /** The resolved set in the vectors' shape: a feature left out is null. */
    private fun KindCapabilities.toJson(): JsonObject = JsonObject(
        mapOf(
            "map" to JsonPrimitive(map),
            "hunt" to (hunt?.let { JsonObject(mapOf("alertRadiusM" to JsonPrimitive(it.alertRadiusM), "stayCreates" to JsonPrimitive(it.stayCreates), "streetMemory" to JsonPrimitive(it.streetMemory), "areas" to JsonPrimitive(it.areas))) } ?: JsonNull),
            "rooms" to JsonPrimitive(rooms),
            "moveIn" to JsonPrimitive(moveIn),
            "contacts" to (contacts?.let { JsonObject(mapOf("role" to JsonPrimitive(it.role), "autoCreateFromPhone" to JsonPrimitive(it.autoCreateFromPhone))) } ?: JsonNull),
            "viewings" to (viewings?.let { JsonObject(mapOf("questionsSeed" to JsonPrimitive(it.questionsSeed))) } ?: JsonNull),
            "compare" to (compare?.let { JsonObject(mapOf("criteria" to JsonArray(it.criteria.map(::JsonPrimitive)))) } ?: JsonNull),
            "paths" to JsonPrimitive(paths),
            "identity" to (identity?.let { JsonObject(mapOf("icon" to JsonPrimitive(it.icon), "colour" to JsonPrimitive(it.colour))) } ?: JsonNull),
            "baseline" to JsonPrimitive(baseline),
            "filters" to JsonArray(filters.map(::JsonPrimitive)),
            "copyOrder" to JsonArray(copyOrder.map(::JsonPrimitive)),
        ),
    )

    private fun JsonElement.strings() = jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun theResolverAnswersWhatEachKindFileDeclaresAndTheVectorsPin() {
        assertEquals(files.keys, KindCapabilities.KINDS)
        assertEquals(files.keys, vectors.getValue("kinds").jsonObject.keys)
        for ((id, file) in files) {
            val declared = KindCapabilities.parse(file["capabilities"])
            assertNotNull(id, declared)
            assertEquals(id, declared, KindCapabilities.of(id))
            assertEquals(id, vectors.getValue("kinds").jsonObject.getValue(id), KindCapabilities.of(id).toJson())
        }
    }

    /** The synthetic kind of the conformance suite resolves to the set the vectors pin, and its copy order is the vectors' (docs/03 section 18.26, 18.29). */
    @Test
    @OptIn(TestOnlyKinds::class)
    fun theSyntheticKindResolvesToTheSetTheVectorsPinAndTheCopyOrderIsTheirs() {
        val file = json("docs/schemas/kinds/test-all.json")
        val parsed = KindCapabilities.parse(file["capabilities"])
        assertNotNull(parsed)
        assertEquals(vectors.getValue("synthetic").jsonObject.getValue("zz_all"), parsed!!.copy(baseline = false).toJson())
        assertEquals(listOf("board", "includesMeals", "seats", "fees"), parsed.filters)
        val order = vectors.getValue("copyOrder").jsonObject
        assertEquals(order.getValue("blocks").strings(), KindCapabilities.COPY_BLOCKS)
        for (case in order.getValue("effective").jsonArray.map { it.jsonObject }) {
            val caps = KindCapabilities.parse(JsonObject(mapOf("copyOrder" to case.getValue("copyOrder"))))
            assertNotNull(caps)
            val undo = KindCapabilities.registerForTest("zz_order", caps!!)
            try {
                assertEquals(case.toString(), case.getValue("order").strings(), KindCapabilities.copyBlockOrder("zz_order"))
            } finally {
                undo()
            }
        }
        for (id in listOf("house", "school", "nothing", null)) assertEquals(id, KindCapabilities.COPY_BLOCKS, KindCapabilities.copyBlockOrder(id))
        // The CSV files and the XLSX sheets (the web's `table-order.ts` reads the same vectors).
        val tables = order.getValue("tables").jsonObject
        assertEquals(tables.getValue("block").jsonObject.mapValues { it.value.jsonPrimitive.content }, TableOrder.TABLE_BLOCK)
        val fixed = tables.getValue("fixed").strings()
        val given = fixed.map { ExportTable(it, it, emptyList(), emptyList()) }
        for (case in tables.getValue("effective").jsonArray.map { it.jsonObject }) {
            val caps = KindCapabilities.parse(JsonObject(mapOf("copyOrder" to case.getValue("copyOrder"))))
            assertNotNull(caps)
            val undo = KindCapabilities.registerForTest("zz_order", caps!!)
            try {
                assertEquals(case.toString(), case.getValue("order").strings(), TableOrder.inCopyOrder(given, "zz_order").map { it.name })
            } finally {
                undo()
            }
        }
        for (id in listOf("house", "school", "nothing", null)) assertEquals(id, fixed, TableOrder.inCopyOrder(given, id).map { it.name })
        // Nothing stays registered: a kind a test made up is unknown again.
        assertEquals(KindCapabilities.DEFAULT, KindCapabilities.of("zz_order"))
    }

    @Test
    fun theHousesSetIsTodaysHardCodedBehaviour() {
        val house = KindCapabilities.of("house")
        val today = vectors.getValue("today").jsonObject
        assertEquals(today.getValue("huntAlertRadiusM").jsonPrimitive.int, AppSettings().alertRadiusM)
        assertEquals(KindCapabilities.Hunt(AppSettings().alertRadiusM, stayCreates = true, streetMemory = true, areas = true), house.hunt)
        assertEquals(Checklist.keys, house.compare?.criteria)
        assertEquals(Checklist.keys, files.getValue("house").getValue("checklist").jsonArray.map { it.jsonObject.getValue("key").jsonPrimitive.content })
        assertEquals(KindCapabilities.Contacts("broker", autoCreateFromPhone = true), house.contacts)
        assertEquals(KindCapabilities.Viewings("house"), house.viewings)
        assertTrue(house.map)
    }

    @Test
    fun everyKindsSwitchesFollowItsSectionsAndItsContactTheBrokerField() {
        val follow = vectors.getValue("today").jsonObject.getValue("sectionsFollow").jsonObject
        val labels = mapOf("house" to KindLabels.HOUSE, "school" to SchoolKind.LABELS)
        for ((id, file) in files) {
            val shown = file.getValue("sections").jsonArray.map { it.jsonObject }
                .filter { it["visible"]?.jsonPrimitive?.boolean != false }.map { it.getValue("id").jsonPrimitive.content }
            fun has(switch: String) = follow.getValue(switch).jsonPrimitive.content in shown
            val caps = KindCapabilities.of(id)
            assertEquals("$id rooms", has("rooms"), caps.rooms)
            assertEquals("$id moveIn", has("moveIn"), caps.moveIn)
            assertEquals("$id viewings", has("viewings"), caps.viewings != null)
            assertEquals("$id paths", has("paths"), caps.paths)
            assertEquals("$id questions", has("questions"), caps.viewings != null)
            // The broker field is the file's own, and a broker is made from a phone only where the file's contact says so (kinds PR 29).
            val brokerField = file.getValue("core").jsonArray.map { it.jsonObject }
                .any { it.getValue("id").jsonPrimitive.content == "brokerId" && it["visible"]?.jsonPrimitive?.boolean != false }
            assertEquals("$id broker field", brokerField, caps.contacts?.role == "broker")
            assertEquals("$id broker", caps.contacts?.role == "broker", caps.contacts?.autoCreateFromPhone ?: false)
            // The labels show a place a capability switches only while the capability is on and the file lists it.
            val l = labels.getValue(id)
            assertEquals("$id rooms shown", caps.rooms && has("rooms"), l.isVisible("section.rooms"))
            assertEquals("$id moveIn shown", caps.moveIn && has("moveIn"), l.isVisible("section.moveIn"))
            assertEquals("$id viewings shown", caps.viewings != null && has("viewings"), l.isVisible("section.viewings"))
            assertEquals("$id questions shown", caps.viewings != null && has("questions"), l.isVisible("section.questions"))
            assertEquals("$id broker shown", caps.contacts?.role == "broker", l.isVisible("field.brokerId"))
            val known = file.getValue("checklist").jsonArray.map { it.jsonObject.getValue("key").jsonPrimitive.content } +
                file.getValue("extras").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            for (c in caps.compare?.criteria.orEmpty()) assertTrue("$id criterion $c", c in known)
        }
    }

    // Kinds PR 29 (docs/03 section 18.23): a capability switches the places it owns, whatever the sections list.
    @Test
    fun aPlaceACapabilitySwitchesIsAllowedOnlyWhileItIsOnAndEveryOtherPlaceAlways() {
        val gated = listOf("section.rooms", "section.moveIn", "section.viewings", "section.questions", "field.brokerId")
        assertEquals(listOf(true, true, true, true, true), gated.map { KindCapabilities.of("house").allows(it) })
        assertEquals(listOf(false, false, false, false, false), gated.map { KindCapabilities.DEFAULT.allows(it) })
        assertEquals("visits and their questions on; no rooms, moving in or broker", listOf(false, false, true, true, false), gated.map { KindCapabilities.of("school").allows(it) })
        for (ref in listOf("section.checklist", "field.label", "section.photos")) assertTrue(ref, KindCapabilities.DEFAULT.allows(ref))
        assertEquals(KindCapabilities.Contacts("office", false), KindCapabilities.of("school").contacts)
    }

    // Kinds PR 30 (docs/03 section 18.24): the baseline kind and the list's filter rows are the kind file's.
    @Test
    fun onlyTheHouseIsTheBaselineKindAndTheFilterRowsAreTheFilesFilters() {
        assertEquals(listOf("house"), KindCapabilities.KINDS.filter { KindCapabilities.of(it).baseline })
        assertEquals(false, KindCapabilities.of("villa").baseline)
        assertEquals(false, KindCapabilities.of(null).baseline)
        assertEquals(emptyList<String>(), KindCapabilities.of("house").filters)
        assertEquals(listOf("board", "medium", "busService"), KindCapabilities.of("school").filters)
        assertEquals(KindCapabilities.of("school").filters, KindFilter.rows(SchoolKind.FIELDS).map { it.id })
        assertEquals(emptyList<KindExtra>(), KindFilter.rows(KindFields.HOUSE))
        for ((id, file) in files) {
            val extras = file.getValue("extras").jsonArray.map { it.jsonObject }
            for (f in KindCapabilities.of(id).filters) {
                assertTrue("$id $f", extras.first { it.getValue("id").jsonPrimitive.content == f }.getValue("type").jsonPrimitive.content in listOf("enum", "bool"))
            }
        }
    }

    @Test
    fun aKindThisBuildDoesNotKnowHasTheDefaults() {
        assertEquals(vectors.getValue("defaults"), KindCapabilities.DEFAULT.toJson())
        assertEquals(KindCapabilities.DEFAULT, KindCapabilities.parse(JsonObject(emptyMap())))
        for (id in vectors.getValue("unknown").strings()) assertSame(id, KindCapabilities.DEFAULT, KindCapabilities.of(id))
        assertSame(KindCapabilities.DEFAULT, KindCapabilities.of(null))
        assertNull(KindCapabilities.parse(null))
    }

    @Test
    fun validObjectsResolveAsPinned() {
        for (case in vectors.getValue("valid").jsonArray.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val parsed = KindCapabilities.parse(case["capabilities"])
            assertNotNull(name, parsed)
            assertEquals(name, case.getValue("resolved"), parsed!!.toJson())
        }
    }

    @Test
    fun invalidObjectsAreRefusedAProtoKeyAmongThem() {
        val cases = vectors.getValue("invalid").jsonArray.map { it.jsonObject }
        assertTrue(cases.size > 55)
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            assertNull(name, KindCapabilities.parse(Json.parseToJsonElement(case.getValue("json").jsonPrimitive.content)))
        }
    }

    @Test
    fun theSchoolHuntsAtOneHundredMetresAndAHuntBlockNeedsAllFourMembers() {
        assertEquals(KindCapabilities.Hunt(100, stayCreates = true, streetMemory = true, areas = true), KindCapabilities.of("school").hunt)
        val hunt = schema.getValue("properties").jsonObject.getValue("capabilities").jsonObject.getValue("properties").jsonObject.getValue("hunt").jsonObject
        assertEquals(listOf("alertRadiusM", "stayCreates", "streetMemory", "areas"), hunt.getValue("required").strings())
        assertNull(KindCapabilities.parse(Json.parseToJsonElement("""{"hunt":{"alertRadiusM":29,"stayCreates":true,"streetMemory":true,"areas":true}}""")))
        assertNull(KindCapabilities.parse(Json.parseToJsonElement("""{"hunt":{"alertRadiusM":301,"stayCreates":true,"streetMemory":true,"areas":true}}""")))
        assertNotNull(KindCapabilities.parse(Json.parseToJsonElement("""{"hunt":{"alertRadiusM":300,"stayCreates":false,"streetMemory":false,"areas":false}}""")))
    }

    @Test
    fun theListsAndLimitsAreTheSchemas() {
        val props = schema.getValue("properties").jsonObject.getValue("capabilities").jsonObject.getValue("properties").jsonObject
        val identity = props.getValue("identity").jsonObject.getValue("properties").jsonObject
        assertEquals(KindCapabilities.ICONS, identity.getValue("icon").jsonObject.getValue("enum").strings())
        assertEquals(KindCapabilities.COLOURS, identity.getValue("colour").jsonObject.getValue("enum").strings())
        assertEquals(KindCapabilities.ICONS, vectors.getValue("icons").strings())
        assertEquals(KindCapabilities.COLOURS, vectors.getValue("colours").strings())
        assertEquals(24, KindCapabilities.ICONS.size)
        val radius = props.getValue("hunt").jsonObject.getValue("properties").jsonObject.getValue("alertRadiusM").jsonObject
        assertEquals(KindCapabilities.MIN_HUNT_RADIUS_M, radius.getValue("minimum").jsonPrimitive.int)
        assertEquals(KindCapabilities.MAX_HUNT_RADIUS_M, radius.getValue("maximum").jsonPrimitive.int)
        val criteria = props.getValue("compare").jsonObject.getValue("properties").jsonObject.getValue("criteria").jsonObject
        assertEquals(KindCapabilities.MAX_COMPARE_CRITERIA, criteria.getValue("maxItems").jsonPrimitive.int)
        assertEquals(KindCapabilities.ROLES, props.getValue("contacts").jsonObject.getValue("properties").jsonObject.getValue("role").jsonObject.getValue("enum").strings())
        assertEquals(KindCapabilities.QUESTION_SEEDS, props.getValue("viewings").jsonObject.getValue("properties").jsonObject.getValue("questionsSeed").jsonObject.getValue("enum").strings())
    }

    /** Owner constraint: India's boundaries apply to every kind (ADR-22), so a kind file carries no map configuration. */
    @Test
    fun aKindFileHasNoMapConfiguration() {
        val forbidden = vectors.getValue("forbiddenMapKeys").strings()
        assertEquals(14, forbidden.size)
        val defs = schema.getValue("\$defs").jsonObject
        val capabilities = schema.getValue("properties").jsonObject.getValue("capabilities").jsonObject
        val identity = capabilities.getValue("properties").jsonObject.getValue("identity").jsonObject
        val shapes = listOf("root" to schema, "section" to defs.getValue("section").jsonObject, "capabilities" to capabilities, "identity" to identity)
        for ((where, shape) in shapes) {
            assertEquals(where, JsonPrimitive(false), shape["additionalProperties"])
            val declared = shape["properties"]?.jsonObject?.keys.orEmpty()
            for (key in forbidden) assertTrue("$where declares $key", key !in declared)
        }
        assertEquals(JsonPrimitive("boolean"), capabilities.getValue("properties").jsonObject.getValue("map").jsonObject["type"])
        for (key in forbidden) {
            assertNull(key, KindCapabilities.parse(JsonObject(mapOf(key to JsonPrimitive("x")))))
            assertNull(key, KindCapabilities.parse(JsonObject(mapOf("map" to JsonObject(mapOf(key to JsonPrimitive("x")))))))
        }
        for (file in files.values) for (key in forbidden) assertTrue(key !in file && key !in file.getValue("capabilities").jsonObject)
    }

    /** Tripwire: the map-rules files know nothing of a candidate's kind, so no kind can change the style or the boundary lines. */
    @Test
    fun theMapRulesFilesDoNotReadAKind() {
        val files = listOf(
            "web/src/app/shared/map-style-url.ts", "web/src/app/shared/india-boundaries.ts",
            "android/ui/src/commonMain/kotlin/app/doorprints/ui/IndiaViewRules.kt",
            "android/ui/src/commonMain/kotlin/app/doorprints/ui/MapStyleJson.kt",
            "android/ui/src/commonMain/kotlin/app/doorprints/ui/IndiaViewOps.kt",
        )
        val symbols = Regex("""capabilitiesOf|KindCapabilities|kind-capabilities|DevKinds|BuiltInKind|KindLabels|KindSetting|LocalSpace|\bSpace\b|kindId|candidate\.kind|house\.kind""")
        for (f in files) assertNull("$f reads a kind: ${symbols.find(repoFile(f).readText())?.value}", symbols.find(repoFile(f).readText()))
    }
}
