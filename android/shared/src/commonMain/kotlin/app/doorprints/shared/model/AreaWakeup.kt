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

// The area wake-up (docs/11 "Design of slice 4b", 5.17, 5.18): which areas get a geofence, and when entering one may
// offer Hunt mode. Pure rules; the geofences themselves are registered by `:app` (`AreaGeofenceManager`), and the
// iPhone has no wake-up yet (S4b-BL-96).

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
