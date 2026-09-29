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

/**
 * Pure rules for Hunt mode's "you've been on this street before" alert. The Android HuntService (in :app) turns the
 * decision into a localised notification.
 */
object StreetAlerts {
    /** At most one alert per street per hour. */
    const val REPEAT_AFTER_MS = 60 * 60_000L

    /** Streets from the geocoder vary in case ("MG Road" / "Mg Road"); one key per street. */
    fun key(street: String): String = street.trim().lowercase()

    /** Same street as before (ignoring case and surrounding spaces), so no new lookup or alert is needed. */
    fun sameStreet(street: String, current: String?): Boolean =
        current != null && key(street) == key(current)

    /** Alert only for a street with saved houses or visits, and not again within [REPEAT_AFTER_MS]. */
    fun shouldAlert(houses: Int, visits: Int, lastAlertAt: Long?, now: Long): Boolean {
        if (houses <= 0 && visits <= 0) return false
        return lastAlertAt == null || now - lastAlertAt >= REPEAT_AFTER_MS
    }
}
