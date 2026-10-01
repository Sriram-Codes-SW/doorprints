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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hunt mode's iPhone adapter (S4b-BL-69): the fix rate it hands the engine, and the deep link a tapped alert carries. */
class IosHuntTest {
    private val second = 1_000L

    @Test
    fun walkingAFixEveryFifteenSecondsOrFiveMetresAfterFiveSeconds() {
        val throttle = HuntFixThrottle()
        assertTrue(throttle.accept(12.97, 77.64, 0), "the first fix always passes")
        assertFalse(throttle.accept(12.97, 77.64, 10 * second), "10 s later, not moved: too soon")
        // 0.00006 degrees of latitude is about 6.7 m: moved, 16 s after the last, so it passes.
        assertTrue(throttle.accept(12.97 + 0.00006, 77.64, 16 * second))
        assertFalse(throttle.accept(12.97 + 0.00012, 77.64, 19 * second), "3 s after the last: sooner than 5 s")
        assertTrue(throttle.accept(12.97 + 0.00012, 77.64, 31 * second), "15 s after the last, even without moving")
    }

    @Test
    fun stayingAFixEverySixtySecondsOrTenMetresAfterThirtySeconds() {
        val throttle = HuntFixThrottle().apply { stationary = true }
        assertTrue(throttle.accept(12.97, 77.64, 0))
        assertFalse(throttle.accept(12.97 + 0.0001, 77.64, 20 * second), "moved 11 m but sooner than 30 s")
        assertTrue(throttle.accept(12.97 + 0.0001, 77.64, 31 * second), "moved 11 m after 30 s")
        assertFalse(throttle.accept(12.97 + 0.0001, 77.64, 60 * second), "29 s after the last, not moved")
        assertTrue(throttle.accept(12.97 + 0.0001, 77.64, 91 * second), "60 s after the last")
        throttle.reset()
        assertTrue(throttle.accept(12.97 + 0.0001, 77.64, 92 * second), "the first fix after a reset passes")
    }

    @Test
    fun aTappedAlertOpensItsHouseOrTheNewHouseFormAndNothingElse() {
        val id = "2b1f0a6e-9c1d-4e5f-8a7b-1c2d3e4f5a6b"
        assertEquals(DeepLink.OpenHouse(id), notificationDeepLink(mapOf(IosHunt.KEY_OPEN_HOUSE to id)))
        assertNull(notificationDeepLink(mapOf(IosHunt.KEY_OPEN_HOUSE to "../not-a-uuid")), "only a UUID opens a house")
        assertEquals(
            DeepLink.NewHouse(12.97, 77.64, id),
            notificationDeepLink(mapOf(IosHunt.KEY_NEW_LAT to "12.97", IosHunt.KEY_NEW_LON to "77.64", IosHunt.KEY_VISIT_ID to id)),
        )
        assertEquals(
            DeepLink.NewHouse(12.97, 77.64, null),
            notificationDeepLink(mapOf(IosHunt.KEY_NEW_LAT to "12.97", IosHunt.KEY_NEW_LON to "77.64", IosHunt.KEY_VISIT_ID to "x")),
        )
        assertNull(notificationDeepLink(mapOf(IosHunt.KEY_NEW_LAT to "91", IosHunt.KEY_NEW_LON to "77.64")), "out of range")
        assertNull(notificationDeepLink(emptyMap<Any?, Any?>()))
        // A Hunt mode reminder (slice 3c) opens its viewing; only a valid record id.
        assertEquals(DeepLink.OpenViewing("v_00000001"), notificationDeepLink(mapOf(IosHunt.KEY_OPEN_VIEWING to "v_00000001")))
        assertNull(notificationDeepLink(mapOf(IosHunt.KEY_OPEN_VIEWING to "..")), "not a record id")
        // A Hunt mode reminder (S4b-BL-94c): the Map's offer, for a valid record id only.
        assertEquals(DeepLink.OfferHunt, notificationDeepLink(mapOf(IosHunt.KEY_OFFER_HUNT_VIEWING to "v_00000001")))
        assertNull(notificationDeepLink(mapOf(IosHunt.KEY_OFFER_HUNT_VIEWING to "..")), "not a record id")
    }
}
