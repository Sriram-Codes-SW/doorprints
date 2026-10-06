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
import kotlin.test.assertSame
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

    // ---- thinning for the drawing only (S4b-FR-31) ----

    /** A street with a 6 m zigzag on every second point: thinning it shortens it, so a detection run on thinned walks would differ. */
    private fun line(id: Long, n: Int, east: Double = 77.5, zigzag: Double = 0.0) =
        List(n) { TracePoint(12.9 + it * 0.00002, east + if (it % 2 == 1) zigzag else 0.0, id + it * 15_000L, id) }

    @Test
    fun withinTheBudgetNothingIsThinnedAndTheWalksAreTheSameList() {
        val walks = listOf(line(1_000, 300), line(900_000_000, 500))
        assertSame(walks, thinForDrawing(walks, budget = 800), "800 points in a budget of 800")
        assertSame(walks, thinForDrawing(walks), "and in the default budget")
        // the drawing of a typical set is the plain one: the base lines whole and the pieces from the detection
        val lists = walks
        val repeats = app.doorprints.shared.trace.RepeatDetector.detect(lists)
        val shown = lists.indices.flatMap { app.doorprints.shared.trace.RepeatDetector.pieces(lists[it], repeats[it].shown) }
        assertEquals(trackGeoJson(lists, shown), TraceDrawing.of(lists.map { TraceWalk(it) }).geoJson)
    }

    @Test
    fun overTheBudgetTheOlderWalksKeepTheirEndsAndEveryKthAndTheNewestStayWhole() {
        // 200 saved walks of 5 000 points (the worst case: 1 000 000 points), the newest walk has the highest id.
        val walks = List(200) { line(1_000L + it * 100_000_000L, 5_000) }
        val thin = thinForDrawing(walks)
        assertEquals(200, thin.size)
        assertTrue(thin.sumOf { it.size } <= DRAW_POINT_BUDGET + 2 * 200, "about the budget, not a million: ${thin.sumOf { it.size }}")
        for ((i, w) in thin.withIndex()) {
            assertSame(walks[i].first(), w.first(), "walk $i keeps its first point")
            assertSame(walks[i].last(), w.last(), "walk $i keeps its last point")
            assertTrue(w.size >= 2)
        }
        assertSame(walks[199], thin[199], "the newest walk is whole")
        assertTrue(thin[0].size < walks[0].size / 2, "an old walk is thinned")
        // every kept point is one of the walk's own, in order
        val ids = thin[0].map { walks[0].indexOf(it) }
        assertEquals(ids.sorted(), ids)
        assertTrue(ids.none { it < 0 })
    }

    @Test
    fun thinningNeverChangesTheRepeatPiecesOnlyTheBaseLines() {
        val walks = listOf(line(1_000, 400, zigzag = 0.00006), line(900_000_000, 400, east = 77.50003, zigzag = 0.00006), line(1_800_000_000, 400, east = 77.50001, zigzag = 0.00006)).map { TraceWalk(it) }
        val whole = features(TraceDrawing.of(walks, drawBudget = 100_000).geoJson)
        val thin = features(TraceDrawing.of(walks, drawBudget = 600).geoJson)
        assertTrue(whole.any { kind(it) == TRACK_KIND_REPEAT })
        assertEquals(
            whole.filter { kind(it) == TRACK_KIND_REPEAT }.map { it.toString() },
            thin.filter { kind(it) == TRACK_KIND_REPEAT }.map { it.toString() },
            "the repeat pieces come from the whole walks",
        )
        val wholeBase = whole.filter { kind(it) == TRACK_KIND_BASE }
        val thinBase = thin.filter { kind(it) == TRACK_KIND_BASE }
        assertEquals(3, thinBase.size)
        assertTrue(thinBase.sumOf { coordinates(it).size } < wholeBase.sumOf { coordinates(it).size } / 2 + 10)
        for (k in 0 until 3) {
            assertEquals(coordinates(wholeBase[k]).first(), coordinates(thinBase[k]).first())
            assertEquals(coordinates(wholeBase[k]).last(), coordinates(thinBase[k]).last())
        }
    }
}
