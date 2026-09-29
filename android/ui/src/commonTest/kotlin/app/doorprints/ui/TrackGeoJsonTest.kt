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

import app.doorprints.data.TrackPointEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** The path trace as the map draws it ([trackGeoJson], docs/11 5.27): one line per walk, no leaps across a long gap. */
class TrackGeoJsonTest {
    private fun point(at: Long, lat: Double = 12.97, lon: Double = 77.64) = TrackPointEntity(at = at, lat = lat, lon = lon, accuracyM = 8f)

    private val minute = 60_000L

    @Test
    fun aGapOfThirtyMinutesStartsANewLineAndALonePointDrawsNothing() {
        val walks = splitTrack(
            listOf(
                point(0), point(5 * minute), point(10 * minute),
                // 40 minutes later: a new walk of one fix, which is not a line.
                point(50 * minute),
                // 31 minutes later still: another walk, of two.
                point(81 * minute), point(83 * minute),
            ),
        )
        assertEquals(listOf(listOf(0L, 5 * minute, 10 * minute), listOf(81 * minute, 83 * minute)), walks.map { w -> w.map { it.at } })
    }

    @Test
    fun theGeoJsonHasOneLineStringPerWalkWithLonLatCoordinates() {
        val json = Json.parseToJsonElement(trackGeoJson(listOf(point(0, 12.9, 77.6), point(minute, 12.91, 77.61)))).jsonObject
        assertEquals("FeatureCollection", json["type"]!!.jsonPrimitive.content)
        val feature = json["features"]!!.jsonArray.single().jsonObject
        val geometry = feature["geometry"]!!.jsonObject
        assertEquals("LineString", geometry["type"]!!.jsonPrimitive.content)
        assertEquals("[[77.6,12.9],[77.61,12.91]]", geometry["coordinates"].toString())
        assertEquals("0", feature["properties"]!!.jsonObject["from"]!!.jsonPrimitive.content)
    }

    @Test
    fun noPointsIsAnEmptyCollection() {
        assertEquals("""{"type":"FeatureCollection","features":[]}""", trackGeoJson(emptyList()))
    }
}
