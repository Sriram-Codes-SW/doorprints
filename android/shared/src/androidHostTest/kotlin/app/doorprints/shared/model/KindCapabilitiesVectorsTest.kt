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

    @Test
    fun theHousesSetIsTodaysHardCodedBehaviour() {
        val house = KindCapabilities.of("house")
        val today = vectors.getValue("today").jsonObject
        assertEquals(today.getValue("huntAlertRadiusM").jsonPrimitive.int, AppSettings().alertRadiusM)
        assertEquals(KindCapabilities.Hunt(AppSettings().alertRadiusM, stayCreates = true, streetMemory = true, areas = true), house.hunt)
        assertEquals(Checklist.keys, house.compare?.criteria)
        assertEquals(Checklist.keys, files.getValue("house").getValue("checklist").jsonArray.map { it.jsonObject.getValue("key").jsonPrimitive.content })
        assertEquals(KindCapabilities.Contacts("broker", KindLabels.HOUSE.isVisible("field.brokerId")), house.contacts)
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
            assertEquals("$id broker", labels.getValue(id).isVisible("field.brokerId"), caps.contacts?.autoCreateFromPhone ?: false)
            val known = file.getValue("checklist").jsonArray.map { it.jsonObject.getValue("key").jsonPrimitive.content } +
                file.getValue("extras").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            for (c in caps.compare?.criteria.orEmpty()) assertTrue("$id criterion $c", c in known)
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
        assertEquals(listOf("house"), props.getValue("viewings").jsonObject.getValue("properties").jsonObject.getValue("questionsSeed").jsonObject.getValue("enum").strings())
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
