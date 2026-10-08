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

package app.doorprints.shared.ai

import app.doorprints.shared.api.HouseDraftDto
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.HouseStatusRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * On-device AI treats text exactly as the server does (docs/03 §13.1, ADR-26): the shared vectors in
 * `docs/ai/evals/parity-vectors.json`, whose expected values are the server's own answers (backend
 * `ParityVectorsTest`), run through the Kotlin ports: contact removal, the listing checks, the Ask snippet and
 * citation markers, and the walking route. Common code, so it runs on the JVM and, on the iOS simulator, with
 * Kotlin/Native's regular expressions.
 */
class ParityVectorsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true; encodeDefaults = true }
    // The file's text, copied into the common tests so these run on iOS too (ParityVectorsFileTest keeps it current).
    private val root = json.parseToJsonElement(PARITY_VECTORS_JSON).jsonObject

    private fun str(e: kotlinx.serialization.json.JsonElement?) = (e as? JsonPrimitive)?.contentOrNull

    @Test
    fun contactRemovalMatchesTheServer() {
        val cases = root.getValue("redact").jsonArray
        for (c in cases) {
            val o = c.jsonObject
            val name = str(o["name"])
            val phone = str(o["phone"])
            val input = str(o["input"])!!
            val r = ContactRedactor.forContact(name, phone)
            val actual = when (str(o["method"])) {
                "place" -> r.place(input)
                "freeText" -> r.freeText(input)
                "scrub" -> ContactRedactor.scrubStoredText(input, name, phone)
                else -> ContactRedactor.redactGeneric(input)
            }
            assertEquals(str(o["expected"]), actual, "${o["method"]} $name / $phone: $input")
        }
        assertEquals(133, cases.size)
    }

    /**
     * S4b-BL-174: a known gap is written down, not hidden. `expected` is what the ports do today (the test above checks
     * it); `wanted` is what they should do. Closing the gap means copying `wanted` over `expected` and dropping both keys.
     */
    @Test
    fun theKnownGapsOfContactRemovalNameTheirBacklogRow() {
        val gaps = root.getValue("redact").jsonArray.map { it.jsonObject }.filter { "knownGap" in it }
        assertEquals(listOf("S4b-BL-174a"), gaps.map { str(it["knownGap"]) })
        for (g in gaps) assertNotEquals(str(g["expected"]), str(g["wanted"]))
    }

    @Test
    fun listingChecksMatchTheServer() {
        assertEquals(24, root.getValue("sanitize").jsonArray.size)
        for (c in root.getValue("sanitize").jsonArray) {
            val o = c.jsonObject
            val raw = o["raw"].takeUnless { it is JsonNull }?.let { json.decodeFromJsonElement<RawListing>(it) }
            val actual = json.encodeToJsonElement(DraftSanitizer.sanitize(raw, str(o["source"])))
            val expected = json.encodeToJsonElement(json.decodeFromJsonElement<HouseDraftDto>(o.getValue("expected")))
            assertEquals(expected, actual, "raw: ${o["raw"]}")
        }
    }

    @Test
    fun snippetsAndCitationMarkersMatchTheServer() {
        for (c in root.getValue("snippet").jsonArray) {
            val o = c.jsonObject
            assertEquals(str(o["expected"]), AskChecks.snippet(str(o["doc"]), str(o["question"]), 240))
        }
        for (c in root.getValue("inlineIds").jsonArray) {
            val o = c.jsonObject
            assertEquals(o.getValue("expected").jsonArray.map { str(it) }, AskChecks.inlineIds(str(o["input"])))
        }
    }

    @Test
    fun walkingRoutesMatchTheServer() {
        checkRoute(root.getValue("route").jsonObject)
    }

    /** S4b-BL-174: the routes across India; their expected legs come from an independent haversine, not from any port. */
    @Test
    fun walkingRoutesAcrossIndiaMatchTheirIndependentlyComputedLegs() {
        val routes = root.getValue("routes").jsonArray
        assertEquals(8, routes.size)
        for (r in routes) checkRoute(r.jsonObject)
    }

    private fun checkRoute(route: JsonObject) {
        val start = route.getValue("start").jsonArray
        val points = route.getValue("points").jsonArray.map {
            val p = it.jsonArray
            RouteOptimizer.Point(str(p[0])!!, p[1].jsonPrimitive.double, p[2].jsonPrimitive.double)
        }
        val name = str(route["name"]) ?: "route"
        fun check(key: String, legs: List<RouteOptimizer.Leg>) {
            val expected = route.getValue(key).jsonArray.map { it.jsonObject }
            assertEquals(expected.map { str(it["id"]) }, legs.map { it.to.id }, "$name $key")
            assertEquals(expected.map { it.getValue("meters").jsonPrimitive.long }, legs.map { RouteOptimizer.roundHalfUp(it.meters) }, "$name $key")
            assertEquals(expected.map { it.getValue("walkMinutes").jsonPrimitive.long.toInt() }, legs.map { it.walkMinutes }, "$name $key")
        }
        val lat = start[0].jsonPrimitive.double
        val lon = start[1].jsonPrimitive.double
        check("nearestNeighbour", RouteOptimizer.nearestNeighbour(lat, lon, points))
        check("inOrder", RouteOptimizer.legsInOrder(lat, lon, points))
    }

    @Test
    fun theStatusesInTheRunningMatchTheServer() {
        val cases = root.getValue("inTheRunning").jsonArray.map { it.jsonObject }
        assertEquals(5, cases.size)
        for (o in cases) {
            val status = str(o["status"])!!
            val expected = o.getValue("expected").jsonPrimitive.boolean
            assertEquals(expected, HouseStatusRules.inTheRunning(status), status)
            assertEquals(expected, HouseStatusRules.inTheRunning(HouseStatus.valueOf(status)), status)
        }
    }
}
