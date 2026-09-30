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

import app.doorprints.shared.records.RecordRules
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure rules of viewings (docs/11 5.8, slice 3b-1): coercion on read, the file check, *Missed?*, the visit link,
 * the timeline, the next viewing, the search and the second viewing's re-check list. The web's `viewing.spec.ts` pins
 * the same cases.
 */
class ViewingsTest {
    private val h = "11111111-1111-4111-8111-111111111111"
    private val hour = 3_600_000L
    private val t = 1_790_501_400_000L // 2026-09-27 09:30 UTC
    private fun v(id: String = "v_a1b2c3d4", startsAt: Long = t, status: String = "PLANNED", durationMin: Int = 30) =
        Viewing(id = id, houseId = h, startsAt = startsAt, durationMin = durationMin, status = status)

    @Test
    fun aStoredRowIsCoercedOnReadAndAnUntrustedOneSkipped() {
        val odd = Viewing(
            id = "v_00000001", houseId = h, startsAt = t, durationMin = 999, kind = "THIRD", status = "MISSED",
            remindMin = 45, withWhom = "  ", notes = " " + "n".repeat(2_100), visitId = " ",
        ).coerced()!!
        assertEquals(listOf("30", "FIRST", "PLANNED", "60"), listOf(odd.durationMin.toString(), odd.kind, odd.status, odd.remindMin.toString()))
        assertNull(odd.withWhom)
        assertNull(odd.visitId)
        assertEquals(Viewing.MAX_NOTES, odd.notes!!.length)
        assertEquals("Ravi", v().copy(withWhom = " Ravi ").coerced()!!.withWhom)
        // Skipped as untrusted: a bad id, a blank or over-long house id, no positive start, an over-long visit id.
        assertNull(v(id = "a/b").coerced())
        assertNull(v().copy(houseId = " ").coerced())
        assertNull(v().copy(houseId = "h".repeat(65)).coerced())
        assertNull(v(startsAt = 0).coerced())
        assertNull(v().copy(visitId = "x".repeat(65)).coerced())
        // A general record id is read too; the app itself makes `v_` + 8 hex.
        assertEquals("imported.id-1", v(id = "imported.id-1").coerced()!!.id)
    }

    @Test
    fun theFileCheckRefusesEveryValueOutOfRange() {
        assertTrue(v().isValid)
        val bad = listOf(
            v(id = ".."), v().copy(houseId = ""), v(startsAt = -1), v(durationMin = 4), v(durationMin = 481),
            v().copy(kind = "THIRD"), v(status = "MISSED"), v().copy(remindMin = 45), v().copy(withWhom = "w".repeat(201)),
            v().copy(notes = "n".repeat(2_001)), v().copy(houseId = "h".repeat(65)), v().copy(visitId = "x".repeat(65)),
        )
        for (b in bad) assertFalse(b.isValid, b.toString())
        assertTrue(v(durationMin = 5).isValid && v(durationMin = 480).isValid)
        for (m in Viewing.REMIND_CHOICES) assertTrue(v().copy(remindMin = m).isValid)
    }

    @Test
    fun theRecordWritesItsKeysInTheFormatsOrderAndTheOptionalOnesOnlyWhenSet() {
        assertEquals(
            """{"houseId":"$h","startsAt":$t,"durationMin":30,"kind":"FIRST","status":"PLANNED","remindMin":60}""",
            ViewingType.encode(v()),
        )
        assertEquals(
            """{"houseId":"$h","startsAt":$t,"durationMin":45,"kind":"SECOND","status":"DONE","remindMin":30,""" +
                """"huntReminder":true,"withWhom":"Ravi","notes":"Bring a tape","visitId":"vis1"}""",
            ViewingType.encode(
                v(durationMin = 45, status = "DONE").copy(kind = "SECOND", remindMin = 30, huntReminder = true, withWhom = "Ravi", notes = "Bring a tape", visitId = "vis1"),
            ),
        )
        assertEquals(v().copy(id = ""), RecordRules.json.decodeFromString(Viewing.serializer(), ViewingType.encode(v())))
    }

    @Test
    fun missedIsAPlannedViewingThatEndedMoreThanTwoHoursAgo() {
        val end = t + 30 * 60_000L
        assertFalse(Viewings.missed(v(), end + 2 * hour))
        assertTrue(Viewings.missed(v(), end + 2 * hour + 1))
        assertFalse(Viewings.missed(v(status = "DONE"), end + 10 * hour))
        assertFalse(Viewings.missed(v(status = "CANCELLED"), end + 10 * hour))
        assertEquals(ViewingGroup.MISSED, Viewings.groupOf(v(), end + 3 * hour))
        assertEquals(ViewingGroup.UPCOMING, Viewings.groupOf(v(), t - hour))
    }

    @Test
    fun suggestedVisitForTakesTheClosestVisitOfTheHouseWithinTwoHoursEitherSide() {
        val refs = listOf(
            Viewings.VisitRef("before", h, t - 2 * hour),
            Viewings.VisitRef("after", h, t + 30 * 60_000L),
            Viewings.VisitRef("other-house", "22222222-2222-4222-8222-222222222222", t),
            Viewings.VisitRef("too-late", h, t + 2 * hour + 1),
        )
        assertEquals("after", Viewings.suggestedVisitFor(v(), refs)?.id)
        assertEquals("before", Viewings.suggestedVisitFor(v(), refs.filter { it.id != "after" })?.id)
        assertNull(Viewings.suggestedVisitFor(v(), refs.filter { it.id == "other-house" || it.id == "too-late" }))
        assertNull(Viewings.suggestedVisitFor(v(), emptyList()))
    }

    @Test
    fun theTimelineGroupsUpcomingSoonestFirstThenMissedDoneAndCancelledNewestFirst() {
        val now = t
        val list = listOf(
            v("v_up2", t + 2 * hour), v("v_up1", t + hour), v("v_miss", t - 10 * hour),
            v("v_done1", t - 48 * hour, "DONE"), v("v_done2", t - 24 * hour, "DONE"), v("v_cx", t - hour, "CANCELLED"),
        )
        val timeline = Viewings.timeline(list, now)
        assertEquals(listOf(ViewingGroup.UPCOMING, ViewingGroup.MISSED, ViewingGroup.DONE, ViewingGroup.CANCELLED), timeline.map { it.first })
        assertEquals(listOf("v_up1", "v_up2"), timeline[0].second.map { it.id })
        assertEquals(listOf("v_done2", "v_done1"), timeline[2].second.map { it.id })
        assertTrue(Viewings.timeline(emptyList(), now).isEmpty())
    }

    @Test
    fun theNextViewingIsTheEarliestPlannedOneAtOrAfterNow() {
        val list = listOf(v("v_b", t + hour), v("v_a", t), v("v_past", t - hour), v("v_done", t + 30_000, "DONE"))
        assertEquals("v_a", Viewings.nextOf(list, h, t)?.id)
        assertEquals("v_b", Viewings.nextOf(list, h, t + 1)?.id)
        assertNull(Viewings.nextOf(list, "other", t))
    }

    /** The one search case every stack shares: house label, street, locality, with whom and notes, any case. */
    @Test
    fun theSearchMatchesTheHousesPlaceAndTheViewingsWords() {
        val x = v().copy(withWhom = "Ravi Kumar", notes = "Ask for the WATER bill")
        for (q in listOf("green", "mg road", "ADYAR", "ravi", "water", "  ")) {
            assertTrue(Viewings.matches(x, "Green View 2BHK", "MG Road", "Adyar", q), q)
        }
        assertFalse(Viewings.matches(x, "Green View 2BHK", "MG Road", "Adyar", "terrace"))
        assertFalse(Viewings.matches(v(), null, null, null, "green"))
    }

    @Test
    fun theReCheckListCountsOpenQuestionsAndNamesCriteriaScoredTwoOrLess() {
        val answers = listOf(
            HouseAnswer("a1", text = "Lift?", status = "OPEN"), HouseAnswer("a2", text = "Water?", answer = "Borewell", status = "ANSWERED"),
            HouseAnswer("a3", text = "Pets?", status = "OPEN"),
        )
        val r = Viewings.recheck(answers, mapOf("water" to 2, "noise" to 1, "parking" to 3, "unknownKey" to 1), Scoring.DEFAULT)
        assertEquals(2, r.openQuestions)
        assertEquals(setOf("water", "noise"), r.lowCriteria.toSet())
        assertTrue(Viewings.recheck(null, emptyMap(), Scoring.DEFAULT).isEmpty)
    }

    @Test
    fun aNewIdIsVPlusEightHexAndNeverOneAlreadyTaken() {
        val taken = mutableSetOf<String>()
        val random = Random(7)
        repeat(50) { taken += Viewing.newId({ it in taken }, random) }
        assertEquals(50, taken.size)
        assertTrue(taken.all { Viewing.isAppId(it) })
        val first = Viewing.newId({ false }, Random(1))
        assertEquals(Viewing.newId({ it == first }, Random(1)).length, 10)
        assertFalse(Viewing.newId({ it == first }, Random(1)) == first)
    }
}
