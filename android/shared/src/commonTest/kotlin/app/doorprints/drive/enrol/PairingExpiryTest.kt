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

package app.doorprints.drive.enrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pairing request's ten-minute life (docs/15 §9.5 i; web twin: `pairing-code.spec.ts`), pure so it also runs on iOS. */
class PairingExpiryTest {
    @Test
    fun theLifeIsTenMinutes() {
        assertEquals(600_000L, PairingCode.PAIRING_TTL_MS)
    }

    @Test
    fun expiresAfterTenMinutesNotBefore() {
        val t0 = 1_000_000L
        assertFalse(PairingCode.pairingExpired(t0, t0))
        assertFalse(PairingCode.pairingExpired(t0, t0 + PairingCode.PAIRING_TTL_MS))
        assertTrue(PairingCode.pairingExpired(t0, t0 + PairingCode.PAIRING_TTL_MS + 1))
        assertTrue(PairingCode.pairingExpired(t0, t0 - 1))
    }
}
