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

package app.doorprints.shared.model

import app.doorprints.shared.location.Geo
import app.doorprints.shared.records.RecordRules
import kotlin.math.abs

// The area wake-up (docs/11 "Design of slice 4b", 5.17, 5.18): which areas get a geofence, and when entering one may
// offer Hunt mode. Pure rules; the geofences themselves are registered by `:app` (`AreaGeofenceManager`), the iPhone's
// regions by `:ui`'s iosMain (`IosAreaWakeup`, S4b-BL-96) with [AreaRegions].

/** When entering an area may notify (5.17 *Cooldown*): once per area per [COOLDOWN_MS], never while Hunt mode runs. */
object AreaCooldown {
    /** Six hours: an area notifies again only when its last notification (or *Dismiss*) is at least this old. */
    const val COOLDOWN_MS = 6 * 60 * 60 * 1000L

    /**
     * True when the area last notified at [lastNotifiedAt] (null: never) may notify again at [nowMs]: the setting is
     * on ([wakeupOn]), Hunt mode is not running ([huntRunning]) and the last one is at least [COOLDOWN_MS] old
     * (inclusive: exactly six hours notifies). A stamp more than [COOLDOWN_MS] in the future (the clock was set back a
     * long way) does not silence the area for ever.
     */
    fun shouldNotify(lastNotifiedAt: Long?, nowMs: Long, huntRunning: Boolean, wakeupOn: Boolean): Boolean {
        if (!wakeupOn || huntRunning) return false
        if (lastNotifiedAt == null) return true
        val age = nowMs - lastNotifiedAt
        return age >= COOLDOWN_MS || age < -COOLDOWN_MS
    }
}

/** Which areas are watched (5.17 *Area wake-up*, *Re-registration*). */
object AreaWakeup {
    /** At most this many geofences: the areas' own cap (the Geofencing API allows 100 per app; iOS 20 regions). */
    const val MAX_GEOFENCES = Area.MAX_AREAS

    /**
     * The areas to register now: none unless the setting is on ([wakeupOn]) and background location is granted
     * ([backgroundGranted]); otherwise the enabled, valid areas of [areas] (one per id), in the screens' order
     * ([Area.BY_NAME]), at most [limit]. A disabled or deleted area (not in [areas]) gets no geofence.
     */
    fun geofencesFor(areas: List<Area>, wakeupOn: Boolean, backgroundGranted: Boolean, limit: Int = MAX_GEOFENCES): List<Area> {
        if (!wakeupOn || !backgroundGranted || limit <= 0) return emptyList()
        return areas.asSequence()
            .filter { it.enabled }
            .mapNotNull { it.coerced() }
            .distinctBy { it.id }
            .sortedWith(Area.BY_NAME)
            .take(limit)
            .toList()
    }
}

/**
 * The iPhone's region monitoring (S4b-BL-96): Core Location watches at most [MAX_REGIONS] circular regions per app,
 * all of the app's own kinds together, and keeps them across launches, so each registration works out what to stop and
 * what to start ([plan]) instead of starting everything again.
 */
object AreaRegions {
    /** Core Location's limit per app: every region the app monitors counts, whatever set it. */
    const val MAX_REGIONS = 20

    /** An area's region identifier is this and the area's id; regions without it are not the wake-up's. */
    const val ID_PREFIX = "doorprints.area."

    /** One circular region as Core Location keeps it: its identifier, centre and radius in metres. */
    data class Region(val identifier: String, val lat: Double, val lon: Double, val radiusM: Double)

    /** What a registration changes: the identifiers to stop monitoring, then the regions to start. */
    data class Plan(val stop: List<String>, val start: List<Region>)

    fun identifier(areaId: String): String = ID_PREFIX + areaId

    /** The area id in a region [identifier] (untrusted: Core Location hands it back), or null when it is not one. */
    fun areaId(identifier: String): String? =
        identifier.takeIf { it.startsWith(ID_PREFIX) }?.removePrefix(ID_PREFIX)?.takeIf(RecordRules::isValidId)

    /**
     * The regions wanted now: [AreaWakeup.geofencesFor] with no limit of its own, then, when the phone's last known
     * position [near] is there, the nearest first (ties in the screens' order), at most [MAX_REGIONS]. The radius is
     * the area's, at most [maxRadiusM] (Core Location's `maximumRegionMonitoringDistance`; ignored when not positive).
     */
    fun wanted(
        areas: List<Area>,
        wakeupOn: Boolean,
        alwaysGranted: Boolean,
        near: Pair<Double, Double>? = null,
        maxRadiusM: Double = 0.0,
    ): List<Region> {
        val set = AreaWakeup.geofencesFor(areas, wakeupOn, alwaysGranted, limit = Int.MAX_VALUE)
        val ordered = if (near == null) set else set.sortedBy { Geo.distanceM(near.first, near.second, it.lat, it.lon) }
        return ordered.take(MAX_REGIONS).map { a ->
            val radius = a.radiusM.toDouble()
            Region(identifier(a.id), a.lat, a.lon, if (maxRadiusM > 0.0) minOf(radius, maxRadiusM) else radius)
        }
    }

    /**
     * From the regions Core Location monitors now ([monitored]) to [wanted]: the wake-up's regions that are not wanted,
     * or have moved or changed size, stop; the wanted ones not already monitored as they are start. Regions without
     * [ID_PREFIX] are left alone and use up places under [MAX_REGIONS], so the nearest of [wanted] that fit are kept.
     */
    fun plan(monitored: List<Region>, wanted: List<Region>): Plan {
        val foreign = monitored.count { !it.identifier.startsWith(ID_PREFIX) }
        val fit = wanted.distinctBy { it.identifier }.take((MAX_REGIONS - foreign).coerceAtLeast(0))
        val ours = monitored.filter { it.identifier.startsWith(ID_PREFIX) }
        val kept = ours.filter { m -> fit.any { same(it, m) } }
        return Plan(
            stop = ours.filter { it !in kept }.map { it.identifier }.distinct(),
            start = fit.filter { w -> kept.none { same(it, w) } },
        )
    }

    /** The same region, allowing for the rounding of Core Location's own copy. */
    private fun same(a: Region, b: Region): Boolean =
        a.identifier == b.identifier && abs(a.lat - b.lat) < 1e-7 && abs(a.lon - b.lon) < 1e-7 && abs(a.radiusM - b.radiusM) < 0.5
}
