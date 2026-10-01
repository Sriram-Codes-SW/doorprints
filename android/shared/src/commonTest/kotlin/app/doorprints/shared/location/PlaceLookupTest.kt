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
import kotlin.test.assertNull

/** S4b-BL-83: the name a shared listing's locality is looked up by, and the answer kept, inside India only. */
class PlaceLookupTest {
    @Test
    fun theLocalityIsLookedUpInIndia() {
        assertEquals("Indiranagar, Bengaluru, India", PlaceLookup.query("  Indiranagar,\n Bengaluru "))
        assertEquals("12, MG Road, India", PlaceLookup.query(null, "12, MG Road"))
        assertEquals("Adyar, Chennai, India", PlaceLookup.query("Adyar, Chennai, India"))
        assertEquals(PlaceLookup.MAX_QUERY + ", India".length, PlaceLookup.query("x".repeat(300))!!.length)
        assertNull(PlaceLookup.query(" ", null))
        assertNull(PlaceLookup.query(null, null))
    }

    @Test
    fun theFirstPointInIndiaIsKept() {
        assertEquals(PlaceLookup.Found(12.9784, 77.6408), PlaceLookup.pick(listOf(51.5 to -0.12, 12.9784 to 77.6408)))
        assertNull(PlaceLookup.pick(listOf(0.0 to 0.0)))
        assertNull(PlaceLookup.pick(listOf(Double.NaN to 77.0)))
        assertNull(PlaceLookup.pick(emptyList()))
    }
}
