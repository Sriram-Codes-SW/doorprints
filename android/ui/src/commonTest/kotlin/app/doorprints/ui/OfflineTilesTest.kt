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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The offline maps' tile estimate (docs/11 5.20): Web Mercator counts a phone can show before it downloads. */
class OfflineTilesTest {
    /** Indiranagar, about 2.5 km by 2.5 km: one or two tiles per zoom from 0 to 14, never more than a few dozen. */
    private val neighbourhood = GeoBounds(south = 12.96, west = 77.63, north = 12.985, east = 77.655)

    /** Bengaluru, about 40 km by 40 km. */
    private val city = GeoBounds(south = 12.83, west = 77.45, north = 13.15, east = 77.78)

    @Test
    fun aNeighbourhoodIsAFewDozenTilesAndACityAFewHundred() {
        val small = OfflineTiles.count(neighbourhood)
        assertTrue(small in 15..40, "neighbourhood: $small tiles")
        val big = OfflineTiles.count(city)
        assertTrue(big in 300..800, "city: $big tiles")
        assertTrue(big <= OfflineTiles.MAX_TILES, "a whole city fits the limit")
    }

    @Test
    fun theWholeCountryIsOverTheLimit() {
        val india = GeoBounds(south = 6.5, west = 68.0, north = 37.5, east = 97.5)
        assertTrue(OfflineTiles.count(india) > OfflineTiles.MAX_TILES)
    }

    @Test
    fun zoomZeroIsOneTileAndEachZoomAddsAtLeastOne() {
        assertEquals(1, OfflineTiles.count(neighbourhood, maxZoom = 0))
        assertEquals(2, OfflineTiles.count(neighbourhood, maxZoom = 1))
        assertEquals(15, OfflineTiles.count(GeoBounds(12.97, 77.64, 12.9701, 77.6401)), "a point: one tile per zoom")
    }

    @Test
    fun theEstimateAndItsTextAreReadable() {
        assertEquals(50_000L * 400, OfflineTiles.estimateBytes(400))
        assertEquals("20", megabytesText(OfflineTiles.estimateBytes(400)))
        assertEquals("2.5", megabytesText(2_450_000))
        assertEquals("0.1", megabytesText(12))
    }
}
