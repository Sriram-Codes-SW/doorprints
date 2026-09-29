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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Hunt mode's "been on this street before" alert rules. */
class StreetAlertsTest {

    private val hour = StreetAlerts.REPEAT_AFTER_MS

    @Test
    fun keyIgnoresCaseAndSpaces() {
        assertEquals("mg road", StreetAlerts.key("  MG Road "))
        assertTrue(StreetAlerts.sameStreet("Mg Road", "MG ROAD"))
        assertFalse(StreetAlerts.sameStreet("MG Road", "Brigade Road"))
        assertFalse(StreetAlerts.sameStreet("MG Road", null))
    }

    @Test
    fun unknownStreetNeverAlerts() {
        assertFalse(StreetAlerts.shouldAlert(houses = 0, visits = 0, lastAlertAt = null, now = 10 * hour))
    }

    @Test
    fun knownStreetAlertsOnceAnHour() {
        assertTrue(StreetAlerts.shouldAlert(houses = 1, visits = 0, lastAlertAt = null, now = 10 * hour))
        assertTrue(StreetAlerts.shouldAlert(houses = 0, visits = 2, lastAlertAt = null, now = 10 * hour))
        assertFalse(StreetAlerts.shouldAlert(houses = 1, visits = 2, lastAlertAt = 10 * hour, now = 10 * hour + hour - 1))
        assertTrue(StreetAlerts.shouldAlert(houses = 1, visits = 2, lastAlertAt = 10 * hour, now = 11 * hour))
    }
}
