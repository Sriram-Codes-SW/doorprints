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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The viewing reminders' rule (docs/11 5.8, slice 3b-2), vectors R1..R6: the web's `viewing-reminders.spec.ts` pins
 * the same cases under the same names.
 */
class ViewingRemindersTest {
    private val h = "11111111-1111-4111-8111-111111111111"
    private val min = 60_000L
    private val ten = 1_790_569_800_000L // 2026-09-28 10:00 IST (04:30 UTC)
    private fun v(id: String = "v_00000001", startsAt: Long = ten, remindMin: Int = 60, status: String = "PLANNED") =
        Viewing(id = id, houseId = h, startsAt = startsAt, remindMin = remindMin, status = status)

    @Test
    fun r1_sixtyMinutesBeforeTenIsNine() {
        val now = ten - 2 * 60 * min
        assertEquals(listOf("v_00000001" to ten - 60 * min), ViewingReminders.upcoming(listOf(v()), now).map { it.first.id to it.second })
        assertEquals(ten - 60 * min, ViewingReminders.fireAt(v()))
    }

    @Test
    fun r2_remindZeroGivesNone() {
        assertNull(ViewingReminders.fireAt(v(remindMin = 0)))
        assertTrue(ViewingReminders.upcoming(listOf(v(remindMin = 0)), ten - 120 * min).isEmpty())
    }

    @Test
    fun r3_doneOrCancelledGivesNone() {
        val list = listOf(v(id = "v_00000001", status = "DONE"), v(id = "v_00000002", status = "CANCELLED"))
        assertTrue(ViewingReminders.upcoming(list, ten - 120 * min).isEmpty())
    }

    @Test
    fun r4_aFireAtAtOrBeforeNowGivesNone() {
        val fire = ten - 60 * min
        assertTrue(ViewingReminders.upcoming(listOf(v()), fire).isEmpty())
        assertTrue(ViewingReminders.upcoming(listOf(v()), fire + 1).isEmpty())
        assertEquals(1, ViewingReminders.upcoming(listOf(v()), fire - 1).size)
    }

    @Test
    fun r5_seventyViewingsGiveTheEarliestSixty() {
        val list = (0 until 70).map { i -> v(id = "v_" + i.toString(16).padStart(8, '0'), startsAt = ten + (69 - i) * min) }
        val got = ViewingReminders.upcoming(list, ten - 120 * min)
        assertEquals(ViewingReminders.LIMIT, got.size)
        assertEquals(list.map { it.startsAt - 60 * min }.sorted().take(60), got.map { it.second })
        assertEquals(5, ViewingReminders.upcoming(list, ten - 120 * min, limit = 5).size)
    }

    @Test
    fun r6_tiesBreakById() {
        val list = listOf(v(id = "v_000000cc"), v(id = "v_000000aa"), v(id = "v_000000bb"))
        assertEquals(listOf("v_000000aa", "v_000000bb", "v_000000cc"), ViewingReminders.upcoming(list, ten - 120 * min).map { it.first.id })
    }

    @Test
    fun theMinutesAreRoundedToTheNearestAndAtLeastOne() {
        assertEquals(25, ViewingReminders.minutesUntil(ten, ten - 25 * min - 20_000))
        assertEquals(26, ViewingReminders.minutesUntil(ten, ten - 25 * min - 40_000))
        assertEquals(1, ViewingReminders.minutesUntil(ten, ten - 5_000))
        assertEquals(1, ViewingReminders.minutesUntil(ten, ten + 5 * min))
    }

    @Test
    fun aReminderIsShownOnlyWhileItsViewingIsStillAheadAndDue() {
        val fire = ten - 60 * min
        assertTrue(ViewingReminders.showNow(v(), fire))
        // The window alarm comes up to 10 minutes early.
        assertTrue(ViewingReminders.showNow(v(), fire - 10 * min))
        assertFalse(ViewingReminders.showNow(v(), fire - 30 * min))
        assertFalse(ViewingReminders.showNow(v(), ten))
        assertFalse(ViewingReminders.showNow(v(status = "CANCELLED"), fire))
        assertFalse(ViewingReminders.showNow(v(remindMin = 0), fire))
    }

    @Test
    fun theWindowStartsTenMinutesEarlyOrNow() {
        assertEquals(ten - 10 * min, ViewingReminders.windowStart(ten, ten - 60 * min))
        assertEquals(ten - 3 * min, ViewingReminders.windowStart(ten, ten - 3 * min))
    }
}
