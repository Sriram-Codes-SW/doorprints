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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** The phone number as a comparison key (docs/11 5.25): the same vectors as `phoneKey` in `broker.spec.ts`. */
class PhoneKeyTest {
    @Test
    fun theSameNumberWrittenThreeWaysIsOneKey() {
        val written = listOf("+91 98400 11111", "098400-11111", "9840011111", "(91) 9840011111", "98400 11111 ")
        assertEquals(written.map { "9840011111" }, written.map { PhoneKey.of(it) })
    }

    @Test
    fun onlyTheLastTenDigitsCount() {
        assertEquals("9840011111", PhoneKey.of("00 91 98400 11111"))
        assertEquals("1234567890", PhoneKey.of("12345678901234567890"))
    }

    @Test
    fun fewerThanSixDigitsIsNoKeyAndSixIsTheLeast() {
        assertNull(PhoneKey.of(null))
        assertNull(PhoneKey.of(""))
        assertNull(PhoneKey.of("   "))
        assertNull(PhoneKey.of("12"))
        assertNull(PhoneKey.of("+9 1234"))
        assertNull(PhoneKey.of("no digits"))
        assertEquals("123456", PhoneKey.of("12-34-56"))
    }

    @Test
    fun differentNumbersAreDifferentKeys() {
        assertNotEquals(PhoneKey.of("98400 11111"), PhoneKey.of("98400 11112"))
        // Only ASCII digits count: a number typed in Devanagari digits is no key, and is never matched by accident.
        assertNull(PhoneKey.of("९८४००११११११"))
    }
}
