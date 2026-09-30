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

import app.doorprints.shared.model.HuntReminders.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Hunt mode reminder's rule (docs/11 5.16, design of slice 3c), vectors H1..H7. */
class HuntRemindersTest {
    private val h = "11111111-1111-4111-8111-111111111111"
    private val min = 60_000L
    private val ten = 1_790_569_800_000L // 2026-09-28 10:00 IST (04:30 UTC)
    private fun v(id: String = "v_00000001", startsAt: Long = ten, remindMin: Int = 0, hunt: Boolean = true, status: String = "PLANNED") =
        Viewing(id = id, houseId = h, startsAt = startsAt, remindMin = remindMin, huntReminder = hunt, status = status)

    @Test
    fun h1_fifteenMinutesBeforeTenIsNineFortyFive() {
        assertEquals(ten - 15 * min, HuntReminders.fireAt(v(), 15))
        assertEquals(listOf("v_00000001" to ten - 15 * min), HuntReminders.upcoming(listOf(v()), 15, ten - 60 * min).map { it.first.id to it.second })
        val merged = HuntReminders.merged(listOf(v()), 15, ten - 60 * min).single()
        assertEquals(Kind.HUNT, merged.kind)
        assertEquals(ten - 15 * min, merged.at)
    }

    @Test
    fun h2_huntReminderFalseGivesNone() {
        assertNull(HuntReminders.fireAt(v(hunt = false), 15))
        assertTrue(HuntReminders.upcoming(listOf(v(hunt = false)), 15, ten - 60 * min).isEmpty())
        assertTrue(HuntReminders.merged(listOf(v(hunt = false)), 15, ten - 60 * min).isEmpty())
    }

    @Test
    fun h3_doneOrCancelledGivesNone() {
        val list = listOf(v(id = "v_00000001", status = "DONE", remindMin = 60), v(id = "v_00000002", status = "CANCELLED", remindMin = 60))
        assertTrue(HuntReminders.upcoming(list, 15, ten - 120 * min).isEmpty())
        assertTrue(HuntReminders.merged(list, 15, ten - 120 * min).isEmpty())
    }

    @Test
    fun h4_aPastTimeGivesNone() {
        val fire = ten - 15 * min
        assertTrue(HuntReminders.upcoming(listOf(v()), 15, fire).isEmpty())
        assertTrue(HuntReminders.merged(listOf(v()), 15, fire + 1).isEmpty())
        assertEquals(1, HuntReminders.merged(listOf(v()), 15, fire - 1).size)
    }

    @Test
    fun h5_aViewingReminderAtNineAndAHuntReminderAtNineFortyFiveStayTwo() {
        val got = HuntReminders.merged(listOf(v(remindMin = 60)), 15, ten - 120 * min)
        assertEquals(listOf(Kind.VIEWING to ten - 60 * min, Kind.HUNT to ten - 15 * min), got.map { it.kind to it.at })
    }

    @Test
    fun h6_atNineFiftyAndNineFortyFiveTheyMergeIntoOneAtNineFortyFive() {
        // A viewing reminder 10 min before (a stored value from a newer app is coerced away; the rule itself takes any).
        val got = HuntReminders.of(v().copy(remindMin = 10), 15)
        assertEquals(listOf(Kind.BOTH to ten - 15 * min), got.map { it.kind to it.at })
        // 15 min before and a lead of 15: the same time, one notification.
        assertEquals(listOf(Kind.BOTH to ten - 15 * min), HuntReminders.merged(listOf(v(remindMin = 15)), 15, ten - 60 * min).map { it.kind to it.at })
        // 30 and 20: ten minutes apart is still one, at the earlier.
        assertEquals(listOf(Kind.BOTH to ten - 30 * min), HuntReminders.merged(listOf(v(remindMin = 30)), 20, ten - 60 * min).map { it.kind to it.at })
        assertTrue(HuntReminders.isMerged(v(remindMin = 15), 15))
        assertFalse(HuntReminders.isMerged(v(remindMin = 60), 15))
        // The Hunt setting off leaves the viewing reminder alone; the viewing setting off leaves the Hunt one.
        assertEquals(listOf(Kind.VIEWING), HuntReminders.merged(listOf(v(remindMin = 15)), 15, ten - 60 * min, huntReminders = false).map { it.kind })
        assertEquals(listOf(Kind.HUNT), HuntReminders.merged(listOf(v(remindMin = 15)), 15, ten - 60 * min, viewingReminders = false).map { it.kind })
    }

    @Test
    fun h7_theCapOfSixtyHoldsOverBothKinds() {
        // 40 viewings, each with a reminder an hour before and a Hunt reminder 15 min before: 80 notifications, 60 kept.
        val list = (0 until 40).map { i -> v(id = "v_" + i.toString(16).padStart(8, '0'), startsAt = ten + i * 120 * min, remindMin = 60) }
        val got = HuntReminders.merged(list, 15, ten - 120 * min)
        assertEquals(ViewingReminders.LIMIT, got.size)
        assertEquals(30, got.count { it.kind == Kind.HUNT })
        assertEquals(30, got.count { it.kind == Kind.VIEWING })
        assertEquals(got.sortedBy { it.at }, got)
        assertEquals(5, HuntReminders.merged(list, 15, ten - 120 * min, limit = 5).size)
    }

    @Test
    fun theLeadTimeIsOneOfTheChoicesOrFifteen() {
        assertEquals(listOf(5, 10, 15, 20, 30, 45, 60), HuntReminders.LEAD_CHOICES)
        for (m in HuntReminders.LEAD_CHOICES) assertEquals(m, HuntReminders.validLead(m))
        for (m in listOf(null, 0, 7, 61, -5, 1440)) assertEquals(15, HuntReminders.validLead(m))
        assertEquals(ten - 15 * min, HuntReminders.fireAt(v(), 7))
    }

    @Test
    fun aHuntReminderIsShownOnlyWhileItsViewingIsStillAheadAndDue() {
        val fire = ten - 15 * min
        assertTrue(HuntReminders.showNow(v(), 15, fire))
        assertTrue(HuntReminders.showNow(v(), 15, fire - 10 * min))
        assertFalse(HuntReminders.showNow(v(), 15, fire - 30 * min))
        assertFalse(HuntReminders.showNow(v(), 15, ten))
        assertFalse(HuntReminders.showNow(v(hunt = false), 15, fire))
        assertFalse(HuntReminders.showNow(v(status = "CANCELLED"), 15, fire))
        // Merged at the viewing reminder's earlier time (30 min before, lead 20): its window may open at 40 min before.
        assertFalse(HuntReminders.showNow(v(remindMin = 30), 20, ten - 40 * min))
        assertTrue(HuntReminders.showNow(v(remindMin = 30), 20, ten - 40 * min, withViewing = true))
    }
}
