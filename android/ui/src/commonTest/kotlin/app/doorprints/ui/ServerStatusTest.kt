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

import app.doorprints.shared.sync.SyncOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings' *Save and test* / *Sync now* result is drawn in its outcome's tone (UX review, whole-app audit, round 8):
 * "Could not connect…" no longer looks like "Connected: 12 houses", and a failure is the one read assertively.
 */
class ServerStatusTest {

    @Test
    fun theLastTestDecidesTheToneWhenThereIsOne() {
        assertEquals(ResultTone.SUCCESS, serverStatusTone(testSucceeded = true, lastSync = null))
        assertEquals(ResultTone.ERROR, serverStatusTone(testSucceeded = false, lastSync = null))
        // As it decides the text: a failed test is an error even after a good sync, and the other way round.
        assertEquals(ResultTone.ERROR, serverStatusTone(testSucceeded = false, lastSync = SyncOutcome.Kind.OK))
        assertEquals(ResultTone.SUCCESS, serverStatusTone(testSucceeded = true, lastSync = SyncOutcome.Kind.NETWORK))
    }

    @Test
    fun otherwiseTheLastSyncDecidesIt() {
        assertEquals(ResultTone.SUCCESS, serverStatusTone(null, SyncOutcome.Kind.OK))
        // No server yet is not a failure.
        assertEquals(ResultTone.NEUTRAL, serverStatusTone(null, SyncOutcome.Kind.NOT_CONFIGURED))
        SyncOutcome.Kind.entries
            .filter { it != SyncOutcome.Kind.OK && it != SyncOutcome.Kind.NOT_CONFIGURED }
            .forEach { assertEquals(ResultTone.ERROR, serverStatusTone(null, it), it.name) }
        // Nothing to show.
        assertNull(serverStatusTone(null, null))
    }

    @Test
    fun aBusyRunKeepsTheCardsSlot() {
        // Round 9: the earlier card stays, dimmed, so what is below it does not jump up and back down.
        assertEquals(ServerStatusSlot.CARD_BUSY, serverStatusSlot(hasResult = true, busy = true, resultWithdrawn = false))
        assertEquals(ServerStatusSlot.CARD, serverStatusSlot(hasResult = true, busy = false, resultWithdrawn = false))
        // No earlier result: the bar alone, then the card.
        assertEquals(ServerStatusSlot.BAR, serverStatusSlot(hasResult = false, busy = true, resultWithdrawn = false))
        assertEquals(ServerStatusSlot.NONE, serverStatusSlot(hasResult = false, busy = false, resultWithdrawn = false))
    }

    @Test
    fun noCardUnderAnAddressThatWasNotChecked() {
        // A malformed or non-https address: the field's own error, and no "Connected: 12 houses" under it.
        assertEquals(ServerStatusSlot.NONE, serverStatusSlot(hasResult = true, busy = false, resultWithdrawn = true))
    }

    @Test
    fun aWithdrawnResultStaysWithdrawnUntilTheNextRunEnds() {
        // Round 10: tied to the run, not to the field's error. Rejected at run 3; typing clears the error but starts
        // no run, so the count is still 3 and the older result does not come back mid-typing.
        assertTrue(serverResultWithdrawn(withdrawnRun = 3, statusRun = 3))
        // Sync now (or a valid Save and test) after the rejection: busy, the bar alone, not the withdrawn card...
        assertEquals(ServerStatusSlot.BAR, serverStatusSlot(hasResult = true, busy = true, resultWithdrawn = true))
        // ...and once it ends the count is 4, so its result shows: the user-started run always ends with feedback.
        assertFalse(serverResultWithdrawn(withdrawnRun = 3, statusRun = 4))
        assertEquals(ServerStatusSlot.CARD, serverStatusSlot(hasResult = true, busy = false, resultWithdrawn = false))
        // Never rejected.
        assertFalse(serverResultWithdrawn(withdrawnRun = -1, statusRun = 0))
    }
}
