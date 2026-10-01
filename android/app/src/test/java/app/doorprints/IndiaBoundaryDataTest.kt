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

package app.doorprints

import app.doorprints.ui.IndiaViewRules
import app.doorprints.ui.SoiPolyline
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * India's boundary files (owner issue P0, 2026-09-24): the phone and the web draw the same outline, so the files the
 * app bundles (android/app/src/main/assets/geo/) and the ones the web serves (web/public/geo/) must be the same bytes,
 * and those bytes are the reviewed builds: the Survey of India's lines (in-boundaries-soi.json, OVSF/1M/7, built by
 * web/scripts/geo/build_in_boundaries_soi.py and checked against the source by tools/soi-verify.py; S4b-BL-99), the
 * corridor around them (in-soi-corridor.geojson, build_in_soi_corridor.py) and Natural Earth's world lines
 * (in-boundaries.geojson, build_in_boundaries.py, Natural Earth commit ca96624; since S4b-BL-99 without the stretches the
 * Survey of India draws). A change to either copy without the other, or a rebuild nobody reviewed, fails here. The same
 * holds for the held areas' polygon (in-held-areas.geojson, web/scripts/geo/build_in_held_areas.py, S4b-BL-12), which
 * both apps use to leave the Pakistani and Chinese admin lines out of `boundary_3`; the web spec pins the same sha256s.
 * The decoder parity with the web (india-boundaries.spec.ts) and web/scripts/geo/test_build_in_boundaries.py: the same
 * vertex counts and checksums from the same file.
 *
 * Like CanonicalSampleTest it walks up from the working directory to the repository root, because the web copy is
 * outside android/. The Android workflow's path filters list the web/public/geo folder for this test, so a change
 * to the web copy alone runs it too (.github/workflows/android.yml; android/shared/README.md section 9, item 35).
 */
class IndiaBoundaryDataTest {

    @Test
    fun theAppAndTheWebBundleTheSameReviewedFile() {
        val app = locate(APP_COPY).readBytes()
        val web = locate(WEB_COPY).readBytes()
        assertEquals(EXPECTED_SHA256, sha256(app))
        assertEquals(EXPECTED_SHA256, sha256(web))
        assertArrayEquals(app, web)
    }

    @Test
    fun theNaturalEarthFileHoldsOnlyTheWorldLines() {
        assertEquals("app/src/main/assets/" + IndiaViewRules.ASSET_PATH, APP_COPY.removePrefix("android/"))
        val root = Json.parseToJsonElement(locate(APP_COPY).readText(Charsets.UTF_8)).jsonObject
        assertEquals("FeatureCollection", root.getValue("type").jsonPrimitive.content)
        val kinds = root.getValue("features").jsonArray.map {
            it.jsonObject.getValue("properties").jsonObject.getValue("kind").jsonPrimitive.content
        }
        assertEquals(listOf("world"), kinds)
    }

    @Test
    fun theAppAndTheWebBundleTheSameSurveyOfIndiaFileAndCorridor() {
        for ((path, sha) in listOf(SOI_COPY to SOI_SHA256, CORRIDOR_COPY to CORRIDOR_SHA256)) {
            val app = locate("android/app/src/main/assets/$path").readBytes()
            val web = locate("web/public/$path").readBytes()
            assertEquals(path, sha, sha256(app))
            assertArrayEquals(path, app, web)
        }
        assertEquals(SOI_COPY, IndiaViewRules.SOI_ASSET_PATH)
        assertEquals(CORRIDOR_COPY, IndiaViewRules.SOI_CORRIDOR_ASSET_PATH)
    }

    @Test
    fun theSurveyOfIndiaFileDecodesToTheSameVerticesAsOnTheWeb() {
        val runs = SoiPolyline.runs(locate("android/app/src/main/assets/$SOI_COPY").readText(Charsets.UTF_8))
        assertNotNull("the decoder reads the bundled file", runs)
        val claim = runs!!.filter { it.kind == "claim" }
        val state = runs.filter { it.kind == "state" }
        // The same counts and sums as the web spec and web/scripts/geo/test_build_in_boundaries.py.
        assertEquals(listOf(6, 28751), listOf(claim.size, claim.sumOf { it.size }))
        assertEquals(listOf(1, 8252), listOf(state.size, state.sumOf { it.size }))
        assertEquals(176943357L to 571665185L, SoiPolyline.checksum(claim))
        assertEquals(1805079313L to 1863970484L, SoiPolyline.checksum(state))
        // The first vertex of Jammu and Kashmir's run, as tools/soi-verify.py checks it, and its text in the map's data.
        assertEquals(753322322L to 323266830L, claim[0].lonE7(0) to claim[0].latE7(0))
        val geoJson = SoiPolyline.geoJson(runs)
        assertTrue(geoJson.contains("[75.3322322,32.326683]"))
        val features = Json.parseToJsonElement(geoJson).jsonObject.getValue("features").jsonArray
        assertEquals(37003, features.sumOf { it.jsonObject.getValue("geometry").jsonObject.getValue("coordinates").jsonArray.size })
    }

    @Test
    fun theCorridorHoldsEverySurveyOfIndiaLandVertex() {
        val polygons = IndiaViewRules.soiCorridorGeometries(locate("android/app/src/main/assets/$CORRIDOR_COPY").readText(Charsets.UTF_8))
        assertNotNull("the rule reads the bundled file", polygons)
        val rings = polygons!!.map { g ->
            Json.parseToJsonElement(g).jsonObject.getValue("coordinates").jsonArray.map { ring ->
                ring.jsonArray.map { p -> p.jsonArray.let { it[0].jsonPrimitive.double to it[1].jsonPrimitive.double } }
            }
        }
        assertEquals(3, rings.size)
        assertTrue(rings.sumOf { r -> r.sumOf { it.size } } <= 1300)
        val runs = SoiPolyline.runs(locate("android/app/src/main/assets/$SOI_COPY").readText(Charsets.UTF_8))!!
        runs.filter { it.kind == "claim" }.forEach { run ->
            // A chain's two ends lie on its polygon's flat ends; every other vertex is inside one polygon.
            for (i in 1 until run.size - 1) {
                val p = run.lonE7(i) / 1e7 to run.latE7(i) / 1e7
                assertTrue("${run.state} vertex $i", rings.any { inside(p, it) })
            }
        }
    }

    @Test
    fun theAppAndTheWebBundleTheSameReviewedHeldAreasPolygon() {
        val app = locate(HELD_APP_COPY).readBytes()
        val web = locate(HELD_WEB_COPY).readBytes()
        assertEquals(HELD_SHA256, sha256(app))
        assertEquals(HELD_SHA256, sha256(web))
        assertArrayEquals(app, web)
        assertEquals("app/src/main/assets/" + IndiaViewRules.HELD_AREAS_ASSET_PATH, HELD_APP_COPY.removePrefix("android/"))
    }

    @Test
    fun theHeldAreasPolygonHoldsTheHeldAreasAndNoIndianTown() {
        val geometry = IndiaViewRules.heldAreasGeometry(locate(HELD_APP_COPY).readText(Charsets.UTF_8))
        assertNotNull("the rule reads the bundled file", geometry)
        val rings = Json.parseToJsonElement(geometry!!).jsonObject.getValue("coordinates").jsonArray.map { ring ->
            ring.jsonArray.map { p -> p.jsonArray.let { it[0].jsonPrimitive.double to it[1].jsonPrimitive.double } }
        }
        // Modest, so the filter stays cheap per boundary_3 feature on a phone.
        assertTrue(rings.sumOf { it.size } <= 700)
        // Pakistan- and China-held towns and areas are inside (their admin lines are not drawn) ...
        mapOf(
            "Gilgit" to (74.31 to 35.92), "Hunza" to (74.65 to 36.32), "Skardu" to (75.63 to 35.30),
            "Muzaffarabad" to (73.47 to 34.37), "Mirpur" to (73.75 to 33.15), "Shaksgam" to (76.5 to 36.1),
            "Aksai Chin" to (79.3 to 35.0),
        ).forEach { (name, p) -> assertTrue(name, inside(p, rings)) }
        // ... and India's are outside, also the ones a few km from the LoC or the LAC (Uri, Poonch, Kargil, Turtuk).
        mapOf(
            "Srinagar" to (74.80 to 34.08), "Jammu" to (74.86 to 32.73), "Leh" to (77.58 to 34.16),
            "Kargil" to (76.13 to 34.56), "Dras" to (75.75 to 34.43), "Uri" to (74.03 to 34.08),
            "Poonch" to (74.09 to 33.77), "Turtuk" to (76.83 to 34.85), "Pangong (Lukung)" to (78.18 to 33.98),
        ).forEach { (name, p) -> assertFalse(name, inside(p, rings)) }
    }

    /** Ray casting in longitude and latitude (the renderers work in Mercator; for whole towns the answer is the same). */
    private fun inside(p: Pair<Double, Double>, rings: List<List<Pair<Double, Double>>>): Boolean {
        var odd = false
        for (r in rings) for (i in 0 until r.size - 1) {
            val (x1, y1) = r[i]
            val (x2, y2) = r[i + 1]
            if ((y1 > p.second) != (y2 > p.second) && p.first < (x2 - x1) * (p.second - y1) / (y2 - y1) + x1) odd = !odd
        }
        return odd
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun locate(path: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("$path not found above ${File("").absolutePath}")
    }

    private companion object {
        const val APP_COPY = "android/app/src/main/assets/geo/in-boundaries.geojson"
        const val WEB_COPY = "web/public/geo/in-boundaries.geojson"
        const val EXPECTED_SHA256 = "f6514483e68104e22b79a9d45aa17edeafb912025af2a638d7fb95f2b4e388be"
        const val SOI_COPY = "geo/in-boundaries-soi.json"
        const val SOI_SHA256 = "d12d8ed85dd1847c976f62b5451f6ef47efd698858431e29d7ff00a8fc20dac6"
        const val CORRIDOR_COPY = "geo/in-soi-corridor.geojson"
        const val CORRIDOR_SHA256 = "aa95954874604f88201bae862f73d4047231c52b1de8293c6478ed23efb50fba"
        const val HELD_APP_COPY = "android/app/src/main/assets/geo/in-held-areas.geojson"
        const val HELD_WEB_COPY = "web/public/geo/in-held-areas.geojson"
        const val HELD_SHA256 = "8fa2db123b4c4370a88ca9b3da31b212b847205c2ba529ca43b4afbb27b780c3"
    }
}
