package com.househunt.app

import com.househunt.app.ui.IndiaViewRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * India's boundary file (owner issue P0, 2026-09-24): the phone and the web draw the same outline, so the file the
 * app bundles (android/app/src/main/assets/geo/) and the one the web serves (web/public/geo/) must be the same bytes,
 * and those bytes are the reviewed build of web/scripts/geo/build_in_boundaries.py (Natural Earth, commit ca96624;
 * since 2026-09-24 with the SHARED stretches of find_shared_stretches.py and the Assam-Arunachal Pradesh state line).
 * A change to either file without the other, or a rebuild nobody reviewed, fails here.
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
        const val EXPECTED_SHA256 = "2c497e2ea08069bd44fce9d72ed23000f133cc66974462dcb0f04228bc1656d7"
    }
}
