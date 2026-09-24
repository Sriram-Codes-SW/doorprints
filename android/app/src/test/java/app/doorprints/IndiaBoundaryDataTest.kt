package app.doorprints

import app.doorprints.ui.IndiaViewRules
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
 * India's boundary file (owner issue P0, 2026-09-24): the phone and the web draw the same outline, so the file the
 * app bundles (android/app/src/main/assets/geo/) and the one the web serves (web/public/geo/) must be the same bytes,
 * and those bytes are the reviewed build of web/scripts/geo/build_in_boundaries.py (Natural Earth, commit ca96624;
 * since 2026-09-24 with the SHARED stretches of find_shared_stretches.py, their hand-overs tidied (S4b-BL-17), and the
 * Assam-Arunachal Pradesh state line).
 * A change to either file without the other, or a rebuild nobody reviewed, fails here. The same holds for the held
 * areas' polygon (in-held-areas.geojson, web/scripts/geo/build_in_held_areas.py, S4b-BL-12), which both apps use to
 * leave the Pakistani and Chinese admin lines out of `boundary_3`; the web spec pins the same sha256.
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
    fun theFileHoldsTheThreeKindsTheMapLayersFilterOn() {
        assertEquals("app/src/main/assets/" + IndiaViewRules.ASSET_PATH, APP_COPY.removePrefix("android/"))
        val root = Json.parseToJsonElement(locate(APP_COPY).readText(Charsets.UTF_8)).jsonObject
        assertEquals("FeatureCollection", root.getValue("type").jsonPrimitive.content)
        val kinds = root.getValue("features").jsonArray.map {
            it.jsonObject.getValue("properties").jsonObject.getValue("kind").jsonPrimitive.content
        }
        assertEquals(listOf("world", "claim", "state"), kinds)
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
        const val EXPECTED_SHA256 = "c3cdf5fb79cf3620526b6ec9906c8c6a38999091eb5821a799f3cc1efa8bf63f"
        const val HELD_APP_COPY = "android/app/src/main/assets/geo/in-held-areas.geojson"
        const val HELD_WEB_COPY = "web/public/geo/in-held-areas.geojson"
        const val HELD_SHA256 = "8fa2db123b4c4370a88ca9b3da31b212b847205c2ba529ca43b4afbb27b780c3"
    }
}
