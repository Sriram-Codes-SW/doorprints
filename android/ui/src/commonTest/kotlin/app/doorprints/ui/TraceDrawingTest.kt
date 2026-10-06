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

package app.doorprints.ui

import app.doorprints.shared.trace.TracePoint
import app.doorprints.shared.trace.TraceWalk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the Map draws of the walks (docs/11 5.27.4), pure. */
class TraceDrawingTest {
    private fun walk(id: Long, n: Int, east: Double = 77.5, step: Double = 0.0002) =
        TraceWalk(List(n) { TracePoint(12.9 + it * step, east, id + it * 15_000L, id) })

    internal fun features(json: String): List<JsonObject> =
        (Json.parseToJsonElement(json).jsonObject["features"] as JsonArray).map { it.jsonObject }

    internal fun kind(f: JsonObject) = f["properties"]!!.jsonObject["kind"]!!.jsonPrimitive.content

    internal fun coordinates(f: JsonObject): JsonArray = f["geometry"]!!.jsonObject["coordinates"]!!.jsonArray

    @Test
    fun aWalkOfOnePointDrawsNothingAndDoesNotShiftTheOthers() {
        assertTrue(TraceDrawing.of(listOf(walk(1_000, 1))).isEmpty, "a lone point is no line")
        val two = walk(1_000, 12)
        val drawing = TraceDrawing.of(listOf(walk(500, 1), two, walk(2_000_000, 12)))
        val fs = features(drawing.geoJson)
        assertEquals(2, fs.count { kind(it) == TRACK_KIND_BASE }, "the lone point is skipped, the two lines are drawn")
        assertTrue(fs.any { kind(it) == TRACK_KIND_REPEAT }, "the two lines on one street are a repeat: the lone point did not misalign them")
    }
}
