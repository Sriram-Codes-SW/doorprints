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

package app.doorprints.shared.sync

import app.doorprints.shared.api.ApiException
import app.doorprints.shared.api.ApiTimeoutException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncOutcomeTest {

    @Test
    fun roundTripsThroughItsStoredForm() {
        val outcome = SyncOutcome(SyncOutcome.Kind.OK, pushed = 3, pulled = 7, photosWaiting = 2)
        assertEquals("OK|3|7|2|0", outcome.encode()) // stored in DataStore: the format must not change
        assertEquals(outcome, SyncOutcome.decode(outcome.encode()))
        assertEquals(SyncOutcome(SyncOutcome.Kind.SERVER, httpCode = 503), SyncOutcome.decode("SERVER|0|0|0|503"))
        assertNull(SyncOutcome.decode("Synced: sent 1, received 2")) // v0.1 free text is ignored
        assertNull(SyncOutcome.decode("BOGUS|0|0|0|0"))
        assertNull(SyncOutcome.decode(null))
    }

    @Test
    fun aServerResetIsASixthFieldOnlyWhenItHappened() {
        // S4b-BL-20: every other outcome keeps the five-field form, so values stored before still read the same.
        val reset = SyncOutcome(SyncOutcome.Kind.OK, pushed = 40, pulled = 12, serverReset = true)
        assertEquals("OK|40|12|0|0|R", reset.encode())
        assertEquals(reset, SyncOutcome.decode(reset.encode()))
        assertEquals(false, SyncOutcome.decode("OK|1|2|0|0")?.serverReset)
        assertNull(SyncOutcome.decode("OK|1|2|0|0|X"))
        assertNull(SyncOutcome.decode("OK|1|2|0|0|R|R"))
    }

    @Test
    fun classifiesErrorsWithoutServerText() {
        assertEquals(SyncOutcome.Kind.AUTH, SyncOutcome.fromError(ApiException(ApiException.Kind.AUTH, 401)).kind)
        assertEquals(SyncOutcome.Kind.CAPTIVE_PORTAL,
            SyncOutcome.fromError(ApiException(ApiException.Kind.CAPTIVE_PORTAL, 200)).kind)
        assertEquals(SyncOutcome.Kind.RATE_LIMITED, SyncOutcome.fromError(ApiException(ApiException.Kind.RATE_LIMITED, 429)).kind)
        assertEquals(503, SyncOutcome.fromError(ApiException(ApiException.Kind.SERVER, 503)).httpCode)
        assertEquals(SyncOutcome.Kind.SERVER, SyncOutcome.fromError(ApiException(ApiException.Kind.CONFLICT, 409)).kind)
        assertEquals(SyncOutcome.Kind.NETWORK, SyncOutcome.fromError(IOException("timeout")).kind)
        assertEquals(SyncOutcome.Kind.NETWORK, SyncOutcome.fromError(ApiTimeoutException(240_000)).kind)
        assertEquals(SyncOutcome.Kind.UNKNOWN, SyncOutcome.fromError(IllegalStateException()).kind)
        assertEquals(SyncOutcome.Kind.UNKNOWN, SyncOutcome.fromError(SerializationException("bad json")).kind)
    }
}
