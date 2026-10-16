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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Offline maps (docs/11 5.20, S4b-FR-6): the map's tiles for an area the person chose, kept on the phone so the map
 * draws with no network, down to street level. The model and the estimate are common; the download and the store
 * are MapLibre's own offline packs on each phone ([OfflineMapsServices]: Android `AndroidOfflineMaps` over
 * `OfflineManager`, iPhone `IosOfflineMapsServices` over `MLNOfflineStorage` through the Swift shell), so no new
 * library and one tile database with the map's own cache. India's boundary rules apply on every style load, offline
 * as online (ADR-22). The website is S4b-BL-79.
 */

/** A box on the map, in degrees. */
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    val centerLat: Double get() = (south + north) / 2
    val centerLon: Double get() = (west + east) / 2
}

/**
 * How many tiles a box needs at every zoom from 0 to [OfflineTiles.MAX_ZOOM], and what they weigh, so the person sees
 * the size before the download and an area too big for a phone is refused with a number. Web Mercator tile maths,
 * the same for both phones' MapLibre.
 */
object OfflineTiles {
    /**
     * Street level: OpenFreeMap's Liberty tiles stop at zoom 14 and the map overzooms them, so 0 to 14 is the whole
     * map of the area, at every zoom.
     */
    const val MAX_ZOOM = 14

    /**
     * The most tiles one area may take: a whole large city at zoom 14 is a few hundred tiles, so 2,000 (about
     * 100 MB) is generous for a hunting area and small beside a phone's storage, and keeps one person's use of
     * OpenFreeMap's public tiles ordinary (docs/03 §11.2).
     */
    const val MAX_TILES = 2_000

    /** What a tile weighs on average, for the estimate shown before the download (Liberty's city tiles, gzipped). */
    const val AVERAGE_TILE_BYTES = 50_000L

    /** The tiles of [bounds] from zoom 0 to [maxZoom], each zoom's box counted whole. */
    fun count(bounds: GeoBounds, maxZoom: Int = MAX_ZOOM): Long {
        var total = 0L
        for (zoom in 0..maxZoom) {
            val n = 2.0.pow(zoom)
            val xMin = tileX(bounds.west, n)
            val xMax = tileX(bounds.east, n)
            val yMin = tileY(bounds.north, n)
            val yMax = tileY(bounds.south, n)
            total += (xMax - xMin + 1) * (yMax - yMin + 1)
        }
        return total
    }

    /** The estimated download for [tiles] tiles, in bytes. */
    fun estimateBytes(tiles: Long): Long = tiles * AVERAGE_TILE_BYTES

    /** The tile column of [lon] on a grid of [n] tiles across, kept on the grid. */
    private fun tileX(lon: Double, n: Double): Long =
        floor((lon.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * n).toLong().coerceIn(0, (n - 1).toLong())

    /**
     * The tile row of [lat] on a grid of [n] tiles across (Web Mercator, so latitudes beyond its edge are clamped),
     * kept on the grid.
     */
    private fun tileY(lat: Double, n: Double): Long {
        val rad = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
        val y = (1.0 - ln(tan(rad) + 1.0 / kotlin.math.cos(rad)) / PI) / 2.0 * n
        return floor(y).toLong().coerceIn(0, (n - 1).toLong())
    }

    /** Web Mercator's edge. */
    private const val MAX_LAT = 85.05112878
}

/** Megabytes as the app shows them: one decimal under 10 ("2.4"), whole above ("12"); never less than "0.1". */
fun megabytesText(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return when {
        mb < 0.1 -> "0.1"
        mb < 10 -> Formats.oneDecimal(mb)
        else -> mb.roundToInt().toString()
    }
}

/** One saved area, as the phone's offline store reports it. */
data class OfflineArea(
    val id: String,
    val name: String,
    val bounds: GeoBounds,
    val state: OfflineAreaState,
    /** Bytes on the phone so far (all of them once [OfflineAreaState.READY]). */
    val bytes: Long,
)

/** Where a saved area is: still downloading, ready to use offline, or failed. */
enum class OfflineAreaState { SAVING, READY, FAILED }

/**
 * What the Map and Settings ask of the phone's offline map store. Each phone implements it over MapLibre's offline
 * packs; the calls return at once and [areas] reports the outcome. Main thread.
 */
interface OfflineMapsServices {
    /** False where the platform has no offline store (previews, tests): no button, no Settings section. */
    val supported: Boolean

    /** Every saved area with its state, newest first; refreshed as downloads progress. */
    val areas: StateFlow<List<OfflineArea>>

    /** Starts saving [bounds] as [name]; the new area appears in [areas] as [OfflineAreaState.SAVING]. */
    fun save(name: String, bounds: GeoBounds)

    /** Removes the area [id] and its tiles. */
    fun delete(id: String)

    /** True on mobile data or a metered hotspot; null when the phone cannot tell. */
    fun networkMetered(): Boolean?
}

/** No offline store: the platform without one, previews and tests. */
object NoOfflineMaps : OfflineMapsServices {
    override val supported: Boolean = false
    override val areas: StateFlow<List<OfflineArea>> = MutableStateFlow(emptyList())
    override fun save(name: String, bounds: GeoBounds) = Unit
    override fun delete(id: String) = Unit
    override fun networkMetered(): Boolean? = null
}
