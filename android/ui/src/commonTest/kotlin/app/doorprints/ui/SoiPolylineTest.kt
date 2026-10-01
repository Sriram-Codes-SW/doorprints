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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The Survey of India decoder (S4b-BL-99) in common code, on the JVM host and (compiled) Kotlin/Native alike: known
 * vectors, the same ones as the web spec and web/scripts/geo/test_build_in_boundaries.py, and the file checks. The
 * shipped file's counts and checksums: IndiaBoundaryDataTest (it reads the file; common tests cannot).
 */
class SoiPolylineTest {
    @Test
    fun decodesGooglesExampleAtOneTenMillionthOfADegree() {
        // Google's documented example, written at 1e-5: the same integers, longitude first.
        assertContentEquals(
            longArrayOf(-12020000, 3850000, -12095000, 4070000, -12645300, 4325200),
            SoiPolyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@"),
        )
    }

    @Test
    fun decodesWhatTheBuildWritesExactly() {
        // build_in_boundaries_soi.encode_polyline([(68.1985123, 23.8132456), (97.4, 28.2), (-1e-7, -89.9999999),
        // (179.9999999, 0)]), the same string as the web spec's.
        val e7 = SoiPolyline.decode("ommeeMeeaxsg@oxmtrAypd~kP|vpnmeA`wfw`y@}nsrst@__hfhjB")
        assertContentEquals(longArrayOf(681985123, 238132456, 974000000, 282000000, -1, -899999999, 1799999999, 0), e7)
        assertEquals(
            listOf("68.1985123", "23.8132456", "97.4", "28.2", "-0.0000001", "-89.9999999", "179.9999999", "0"),
            e7.map(SoiPolyline::decimal),
        )
        assertEquals(0, SoiPolyline.decode("").size)
        assertFailsWith<IllegalArgumentException> { SoiPolyline.decode("_p~iF~ps|") }
        assertFailsWith<IllegalArgumentException> { SoiPolyline.decode("_p~iF ps|U") }
    }

    @Test
    fun readsOnlyTheSurveyOfIndiaFileAndWritesItsVerticesUnchanged() {
        fun file(vararg props: String) = "{\"type\":\"FeatureCollection\",\"features\":[" +
            props.joinToString(",") { "{\"type\":\"Feature\",\"geometry\":null,\"properties\":{$it}}" } + "]}"
        val run = "\"kind\":\"claim\",\"state\":\"X\",\"vertices\":2,\"polyline7\":\"_p~iF~ps|U_ulLnnqC\""
        val runs = SoiPolyline.runs(file(run))!!
        assertEquals(listOf("claim" to 2), runs.map { it.kind to it.size })
        val geo = Json.parseToJsonElement(SoiPolyline.geoJson(runs)).jsonObject
        assertEquals(
            "[[-1.202,0.385],[-1.2095,0.407]]",
            geo.getValue("features").jsonArray[0].jsonObject.getValue("geometry").jsonObject.getValue("coordinates").toString(),
        )
        assertEquals(SoiPolyline.geoJson(runs), SoiPolyline.geoJson(file(run)))
        assertNull(SoiPolyline.runs(file(run.replace("\"vertices\":2", "\"vertices\":3"))))
        assertNull(SoiPolyline.runs(file(run.replace("claim", "world"))))
        assertNull(SoiPolyline.runs(file(run.replace("_p~iF~ps|U_ulLnnqC", "_p~iF~ps|"))))
        assertNull(SoiPolyline.runs(file(run.replace("\"vertices\":2", "\"vertices\":1").replace("_ulLnnqC", ""))))
        assertNull(SoiPolyline.runs(file()))
        assertNull(SoiPolyline.runs("not json"))
        assertNull(SoiPolyline.geoJson("{\"type\":\"Feature\"}"))
    }

    @Test
    fun theChecksumIsTheWebsAndThePythonTests() {
        // Two vertices: s1 and s2 by hand, mod 2^31 - 1, a negative value taken mod m first.
        val run = SoiPolyline.Run("claim", "X", longArrayOf(10, 20, -1, 5))
        val m = 2147483647L
        var s1 = 0L
        var s2 = 0L
        for (v in listOf(10L, 20L, m - 1, 5L)) {
            s1 = (s1 + v) % m
            s2 = (s2 + s1) % m
        }
        assertEquals(s1 to s2, SoiPolyline.checksum(listOf(run)))
    }
}
