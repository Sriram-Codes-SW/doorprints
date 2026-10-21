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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure rules of the deletion safeguards (PR 3): the stamp, the ids a space's delete takes, the list, the bulk pause. */
class DeleteScopeTest {
    @Test
    fun aStampIsNeverAtOrBeforeThePreviousOne() {
        assertEquals(1_000L, DeleteScope.nextStamp(10, 1_000))
        assertEquals(1_001L, DeleteScope.nextStamp(1_000, 500))
        assertEquals(1_001L, DeleteScope.nextStamp(1_000, 1_000))
    }

    @Test
    fun aSpaceDeleteTakesTheLiveRowsOfThatSpaceOnly() {
        val rows = listOf(
            ScopedCandidate("h", null), ScopedCandidate("s1", "school"), ScopedCandidate("s2", "school", deleted = true),
            ScopedCandidate("c", "clinic"),
        )
        val school = SpaceScope.holdsFor("school", true, "house", listOf("house", "school"))
        val house = SpaceScope.holdsFor("house", true, "house", listOf("house", "school"))
        assertEquals(listOf("s1"), DeleteScope.liveIdsOf(rows, school))
        assertEquals(listOf("h", "c"), DeleteScope.liveIdsOf(rows, house)) // a kind with no space is the default space's
    }

    @Test
    fun theHousesDeleteAndCountLeaveASchoolAloneWhileTheSchoolIsReleasedButNotShown() {
        // The release: the school is offered (available) and the house alone is enabled. What you track scopes each kind's row by
        // its own kind (multi = true), so a school that arrived by sync or import is neither counted under Houses nor deleted by it.
        val available = listOf("house", "school")
        val rows = listOf(ScopedCandidate("h1", null), ScopedCandidate("h2", "house"), ScopedCandidate("s1", "school"))
        assertEquals(listOf("h1", "h2"), DeleteScope.liveIdsOf(rows, SpaceScope.holdsFor("house", true, "house", available)))
        assertEquals(listOf("s1"), DeleteScope.liveIdsOf(rows, SpaceScope.holdsFor("school", true, "house", available)))
        val counts = spaceCountsOf(listOf(KindTally("house", 2, 0L), KindTally("school", 1, 0L)), "house", available)
        assertEquals(2, counts["house"])
        assertEquals(1, counts["school"])
    }

    @Test
    fun theListHoldsThisSpacesTombstonesUnder30DaysNewestFirstTiesById() {
        val now = 100L * 24 * 3600 * 1000
        val day = 24L * 3600 * 1000
        val rows = listOf(
            ScopedCandidate("b", "school", deleted = true, updatedAt = now - day),
            ScopedCandidate("a", "school", deleted = true, updatedAt = now - day),
            ScopedCandidate("old", "school", deleted = true, updatedAt = now - 30 * day),
            ScopedCandidate("edge", "school", deleted = true, updatedAt = now - 30 * day + 1),
            ScopedCandidate("live", "school", updatedAt = now),
            ScopedCandidate("h", null, deleted = true, updatedAt = now),
        )
        val school = SpaceScope.holdsFor("school", true, "house", listOf("house", "school"))
        assertEquals(listOf("a", "b", "edge"), DeleteScope.binRows(rows, school, now).map { it.id })
    }

    @Test
    fun oneBulkActionPerTenSeconds() {
        var t = 1_000_000L
        val guard = BulkGuard { t }
        assertEquals(0, guard.waitSeconds())
        assertTrue(guard.tryStart())
        assertFalse(guard.tryStart())
        t += 9_001
        assertEquals(1, guard.waitSeconds())
        assertFalse(guard.tryStart())
        t += 999
        assertEquals(0, guard.waitSeconds())
        assertTrue(guard.tryStart())
    }

    @Test
    fun aClockThatGoesBackDoesNotLockTheGuardForEver() {
        var t = 5_000_000L
        val guard = BulkGuard { t }
        assertTrue(guard.tryStart())
        t = 1_000L
        assertEquals(0, guard.waitSeconds())
        assertTrue(guard.tryStart())
    }
}
