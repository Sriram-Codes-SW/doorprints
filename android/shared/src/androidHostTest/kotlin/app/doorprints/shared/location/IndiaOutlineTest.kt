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

package app.doorprints.shared.location

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S4b-BL-174c: the outline the place lookup tests against is the website's (`web/src/app/shared/india-outline.geojson`, the
 * same ring point for point), and the shared place vectors (`docs/schemas/india-place-vectors.json`) are read the same
 * way as by the website's `place-search.spec.ts`: every place of India, border town and held area is picked, every town
 * abroad is refused.
 */
class IndiaOutlineTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    @Test
    fun theRingsAreTheWebsitesPointForPoint() {
        val root = Json.parseToJsonElement(repoFile("web/src/app/shared/india-outline.geojson").readText()).jsonObject
        assertEquals("doorprints-india-outline/1", root.getValue("format").jsonPrimitive.content)
        val polygons = root.getValue("features").jsonArray[0].jsonObject.getValue("geometry").jsonObject.getValue("coordinates").jsonArray
        // GeoJSON rings are closed by repeating the first point; the app's are not.
        val web = polygons.map { polygon ->
            polygon.jsonArray[0].jsonArray.dropLast(1)
                .map { it.jsonArray[1].jsonPrimitive.content.toDouble() to it.jsonArray[0].jsonPrimitive.content.toDouble() }
        }
        assertEquals(web, IndiaOutline.points())
        assertEquals("the mainland, the Andaman and Nicobar Islands, Lakshadweep", 3, web.size)
        assertTrue(web[0].size > 1000)
    }

    @Test
    fun theSharedPlaceVectorsAreReadAsTheWebsiteReadsThem() {
        val root = Json.parseToJsonElement(repoFile("docs/schemas/india-place-vectors.json").readText()).jsonObject
        fun points(key: String) = root.getValue(key).jsonArray.map {
            val o = it.jsonObject
            o.getValue("name").jsonPrimitive.content to
                (o.getValue("lat").jsonPrimitive.content.toDouble() to o.getValue("lon").jsonPrimitive.content.toDouble())
        }
        val places = points("places") + points("borderTowns")
        assertTrue(places.size >= 70)
        for ((name, p) in places) assertEquals(name, PlaceLookup.Found(p.first, p.second), PlaceLookup.pick(listOf(p)))
        val abroad = points("abroad")
        assertTrue(abroad.size >= 20)
        for ((name, p) in abroad) assertEquals(name, null, PlaceLookup.pick(listOf(p)))
        assertNotNull(PlaceLookup.pick(listOf(abroad.first().second, places.first().second)))
    }
}
