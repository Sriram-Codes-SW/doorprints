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

package app.doorprints.shared.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shared vectors of the path trace (`docs/schemas/trace-repeat-vectors.json`, docs/schemas/README.md 6.4, TC-U-147
 * and TC-U-153): every `split`, `repeats` and `alert` case and every `placeChecks` case, the same here as in the
 * website's `trace-repeats-vectors.spec.ts` and `trace-place-check-vectors.spec.ts`. The file is read from the
 * repository (`commonTest` has no file API), walking up from the working directory as `DriveVectorsTest` does.
 * A case that fails means the code or the text is wrong, never the vector.
 */
class TraceVectorsTest {
    private val root: JsonObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, VECTORS).exists()) dir = dir.parentFile
        val file = File(checkNotNull(dir) { "$VECTORS not found" }, VECTORS)
        Json.parseToJsonElement(file.readText()).jsonObject.also {
            assertEquals("doorprints-trace-repeat-vectors/1", it.getValue("format").jsonPrimitive.content)
        }
    }

    private val cases: List<JsonObject> by lazy { root.getValue("cases").jsonArray.map { it.jsonObject } }
    private val placeChecks: List<JsonObject> by lazy { root.getValue("placeChecks").jsonArray.map { it.jsonObject } }

    private fun JsonObject.id() = getValue("id").jsonPrimitive.content

    /** `[lat, lon, atMs]`, `[lat, lon, atMs, walkId]` or `[lat, lon, atMs, walkId, resumed]`. */
    private fun point(e: JsonElement): TracePoint {
        val a = e.jsonArray
        return TracePoint(
            a[0].jsonPrimitive.double, a[1].jsonPrimitive.double, a[2].jsonPrimitive.long,
            walkId = if (a.size > 3) a[3].jsonPrimitive.long else 0,
            resumed = a.size > 4 && a[4].jsonPrimitive.int == 1,
        )
    }

    private fun walk(e: JsonElement): List<TracePoint> = e.jsonArray.map(::point)

    private fun stretches(e: JsonElement) = e.jsonArray.map {
        Stretch(it.jsonObject.getValue("fromM").jsonPrimitive.double, it.jsonObject.getValue("toM").jsonPrimitive.double)
    }

    private fun assertStretches(message: String, expected: List<Stretch>, actual: List<Stretch>) {
        assertEquals("$message: count (actual $actual)", expected.size, actual.size)
        for ((i, e) in expected.withIndex()) {
            assertEquals("$message[$i].fromM (actual $actual)", e.fromM, actual[i].fromM, 0.5)
            assertEquals("$message[$i].toM (actual $actual)", e.toM, actual[i].toM, 0.5)
        }
    }

    @Test
    fun theFileHasEveryCase() {
        assertEquals(37, cases.size)
        assertEquals(21, placeChecks.size)
        assertEquals(6, cases.count { it.getValue("kind").jsonPrimitive.content == "split" })
        assertEquals(23, cases.count { it.getValue("kind").jsonPrimitive.content == "repeats" })
        assertEquals(8, cases.count { it.getValue("kind").jsonPrimitive.content == "alert" })
    }

    @Test
    fun theConstantsOfTheFileAreTheConstantsOfTheCode() {
        val c = root.getValue("constants").jsonObject
        fun d(k: String) = c.getValue(k).jsonPrimitive.double
        assertEquals(TraceConstants.EARTH_RADIUS_M, d("earthRadiusM"), 0.0)
        assertEquals(TraceConstants.DENSIFY_M, d("densifyM"), 0.0)
        assertEquals(TraceConstants.TOLERANCE_M, d("toleranceM"), 0.0)
        assertEquals(TraceConstants.BRIDGE_M, d("bridgeM"), 0.0)
        assertEquals(TraceConstants.MIN_RUN_M, d("minRunM"), 0.0)
        assertEquals(TraceConstants.WALK_GAP_MS, c.getValue("walkGapMs").jsonPrimitive.long)
        assertEquals(TraceConstants.ALERT_MIN_RUN_M, d("alertMinRunM"), 0.0)
        assertEquals(TraceConstants.ALERT_COOLDOWN_MS, c.getValue("alertCooldownMs").jsonPrimitive.long)
        assertEquals(TraceConstants.MAX_WALK_POINTS, c.getValue("maxWalkPoints").jsonPrimitive.int)
        assertEquals(TraceConstants.MAX_DETECTION_POINTS, c.getValue("maxDetectionPoints").jsonPrimitive.int)
        assertEquals(TraceConstants.NEAR_BAND_M, d("nearBandM"), 0.0)
        assertEquals(TraceConstants.MAX_FIX_ACCURACY_M, d("maxFixAccuracyM"), 0.0)
        assertEquals("every constant of the file is checked", 12, c.size)
    }

    @Test
    fun everySplitCase() {
        for (c in cases.filter { it.getValue("kind").jsonPrimitive.content == "split" }) {
            val points = walk(c.getValue("points"))
            val expected = c.getValue("expect").jsonObject.getValue("walks").jsonArray.map { w -> w.jsonArray.map { it.jsonPrimitive.int } }
            assertEquals(c.id(), expected, WalkSplitter.splitIndexes(points))
        }
    }

    @Test
    fun everyRepeatsCase() {
        val repeats = cases.filter { it.getValue("kind").jsonPrimitive.content == "repeats" }
        for (c in repeats) {
            val expect = c.getValue("expect").jsonObject
            val walks: List<List<TracePoint>> = if ("walks" in c) {
                c.getValue("walks").jsonArray.map(::walk)
            } else {
                splitWalks(walk(c.getValue("points"))).also {
                    assertEquals("${c.id()}: walkCount", expect.getValue("walkCount").jsonPrimitive.int, it.size)
                }
            }
            val expectedRepeated = expect.getValue("repeated").jsonArray
            val expectedShown = expect.getValue("shown").jsonArray
            for (useIndex in listOf(true, false)) {
                val got = RepeatDetector.detect(walks, useIndex)
                assertEquals("${c.id()}: one result per walk", walks.size, got.size)
                for (i in walks.indices) {
                    assertStretches("${c.id()} (index=$useIndex) repeated[$i]", stretches(expectedRepeated[i]), got[i].repeated)
                    assertStretches("${c.id()} (index=$useIndex) shown[$i]", stretches(expectedShown[i]), got[i].shown)
                }
            }
        }
    }

    @Test
    fun everyAlertCase() {
        for (c in cases.filter { it.getValue("kind").jsonPrimitive.content == "alert" }) {
            val live = walk(c.getValue("live"))
            val others = c.getValue("others").jsonArray.map(::walk)
            val alert = RepeatAlert(others)
            val rang = live.indices.filter { alert.onPoint(live[it]) != null }
            val expected = c.getValue("expect").jsonObject.getValue("alertAtIndexes").jsonArray.map { it.jsonPrimitive.int }
            assertEquals(c.id(), expected, rang)
        }
    }

    private fun walkSources(c: JsonObject, n: Int): List<WalkSource> =
        c["walkSources"]?.jsonArray?.map { if (it.jsonPrimitive.content == "saved") WalkSource.SAVED else WalkSource.TRACE }
            ?: List(n) { WalkSource.TRACE }

    @Test
    fun everyPlaceCheckCase() {
        for (c in placeChecks) {
            val place = c.getValue("place").jsonArray
            val walks = c.getValue("walks").jsonArray.map(::walk)
            val sources = walkSources(c, walks.size)
            val accuracy = c["fixAccuracyM"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.double
            val expect = c.getValue("expect").jsonObject
            for (box in listOf(true, false)) {
                val got = PlaceCheck.check(
                    place[0].jsonPrimitive.double, place[1].jsonPrimitive.double,
                    walks.mapIndexed { i, w -> TraceWalk(w, sources[i]) }, accuracy, boxRejection = box,
                )
                val id = "${c.id()} (box=$box)"
                assertEquals("$id: status", statusOf(expect.getValue("status").jsonPrimitive.content), got.status)
                assertEquals("$id: fuzzy", expect.getValue("fuzzy").jsonPrimitive.booleanOrNull, got.fuzzy)
                val nearest = expect.getValue("nearestM").takeIf { it !is JsonNull }?.jsonPrimitive?.double
                if (nearest == null) assertEquals("$id: nearestM", null, got.nearestM) else assertEquals("$id: nearestM", nearest, got.nearestM!!, 0.5)
                val rows = expect.getValue("walks").jsonArray.map { it.jsonObject }
                assertEquals("$id: rows (actual ${got.rows})", rows.size, got.rows.size)
                for ((i, r) in rows.withIndex()) {
                    val a = got.rows[i]
                    assertEquals("$id: row $i walkIndex", r.getValue("walkIndex").jsonPrimitive.int, a.walkIndex)
                    assertEquals("$id: row $i distanceM", r.getValue("distanceM").jsonPrimitive.double, a.distanceM, 0.5)
                    assertEquals("$id: row $i atMs", r.getValue("atMs").jsonPrimitive.long.toDouble(), a.atMs.toDouble(), 1000.0)
                    assertEquals("$id: row $i band", r.getValue("band").jsonPrimitive.content.uppercase(), a.band.name)
                    assertEquals("$id: row $i source", r.getValue("source").jsonPrimitive.content.uppercase(), a.source.name)
                }
            }
        }
    }

    private fun statusOf(s: String) = when (s) {
        "walked" -> PlaceCheckStatus.WALKED
        "close" -> PlaceCheckStatus.CLOSE
        "none" -> PlaceCheckStatus.NONE
        "empty" -> PlaceCheckStatus.EMPTY
        "imprecise" -> PlaceCheckStatus.IMPRECISE
        "invalidPlace" -> PlaceCheckStatus.INVALID_PLACE
        else -> error("unknown status $s")
    }

    private companion object {
        const val VECTORS = "docs/schemas/trace-repeat-vectors.json"
    }
}
