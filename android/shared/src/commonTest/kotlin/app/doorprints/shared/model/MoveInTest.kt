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

/** Moving in (docs/11 5.24, slice 5): the vectors M4 and M5, what a reader keeps and what a file must hold. */
class MoveInTest {
    @Test fun m4_startMovingInAddsTheSixItemsInOrderAndAgainAddsNothing() {
        val first = MoveIn.addDefaults(null, "en")
        assertEquals(listOf("mi_agreement", "mi_police", "mi_id", "mi_deposit", "mi_meters", "mi_keys"), first.map { it.id })
        assertEquals((0..5).toList(), first.map { it.sort })
        assertEquals("Rental agreement signed and registered", first[0].text)
        assertTrue(first.none { it.isDone })
        assertEquals(first, MoveIn.addDefaults(first, "en"))
        assertFalse(MoveIn.canAddDefaults(first))
        // In the app's language; an item already there (by id) is skipped and the rest go after the largest sort.
        val own = listOf(MoveInItem("mi_keys", "Chaabi", true, 7), MoveInItem("mi_x1", "Paint", sort = 2))
        val tamil = MoveIn.addDefaults(own, "ta")
        assertEquals(own + listOf("mi_agreement", "mi_police", "mi_id", "mi_deposit", "mi_meters").mapIndexed { i, id ->
            MoveInItem(id, MoveIn.DEFAULTS.first { it.id == id }.text.getValue("ta"), sort = 8 + i)
        }, tamil)
        assertEquals("Keys received", MoveIn.addDefaults(null, "fr").last().text, "anything but hi, ta or te is English")
    }

    @Test fun m5_theCapOfThirtyItems() {
        val many = (0 until 28).map { MoveInItem("mi_$it", "Item $it", sort = it) }
        assertEquals(30, MoveIn.addDefaults(many, "en").size)
        val thirty = (0 until 30).map { MoveInItem("mi_$it", "Item $it", sort = it) }
        assertEquals(thirty, MoveIn.addDefaults(thirty, "en"))
        assertNull(MoveIn.add(thirty, "One more"))
        assertEquals(30, MoveIn.add(thirty.dropLast(1), "Last", newId = { "mi_last" })!!.size)
        // A reader keeps the first 30 by sort; a file with 31 is refused.
        val file = (0 until 31).map { MoveInItem("mi_$it", "Item $it", sort = 30 - it) }
        assertEquals(30, MoveIn.coerced(MoveIn(items = file))!!.items!!.size)
        assertFalse(MoveIn(items = file).isValid)
        assertTrue(MoveIn(items = file.take(30)).isValid)
    }

    @Test fun aReaderKeepsWhatIsUsableAndNoneWhenNothingIs() {
        assertNull(MoveIn.coerced(null))
        assertNull(MoveIn.coerced(MoveIn(date = 0, notes = "  ", items = emptyList())))
        val coerced = MoveIn.coerced(
            MoveIn(
                date = -5, notes = "n".repeat(2100),
                items = listOf(
                    MoveInItem("b", "Second", done = false, sort = 1),
                    MoveInItem("a", "First", done = true, sort = -3),
                    MoveInItem("a", "Repeat", sort = 0),
                    MoveInItem("../x", "Bad id"),
                    MoveInItem("c", " "),
                    MoveInItem("d", "x".repeat(201)),
                ),
            ),
        )!!
        assertNull(coerced.date)
        assertEquals(2000, coerced.notes!!.length)
        assertEquals(listOf(MoveInItem("a", "First", true, 0), MoveInItem("b", "Second", null, 1)), coerced.items)
        assertEquals(1 to 2, coerced.progress)
    }

    @Test fun aFileIsRefusedForABadIdARepeatABlankTextLongNotesABadDateOrANegativeSort() {
        val good = MoveIn(1_790_812_800_000L, "Keys", listOf(MoveInItem("mi_keys", "Keys", true, 0)))
        assertTrue(good.isValid)
        for (bad in listOf(
            good.copy(date = 0), good.copy(date = -1), good.copy(notes = "n".repeat(2001)),
            good.copy(items = listOf(MoveInItem("a/b", "t"))), good.copy(items = listOf(MoveInItem("a", "t"), MoveInItem("a", "u"))),
            good.copy(items = listOf(MoveInItem("a", " "))), good.copy(items = listOf(MoveInItem("a", "x".repeat(201)))),
            good.copy(items = listOf(MoveInItem("a", "t", sort = -1))),
        )) assertFalse(bad.isValid, bad.toString())
    }

    @Test fun theJsonHasTheFormatsKeysInOrderAndDoneOnlyWhenTrue() {
        val m = MoveIn(1_790_812_800_000L, "Keys", listOf(MoveInItem("mi_agreement", "Signed", true, 0), MoveInItem("mi_police", "Police", sort = 1)))
        val text = MoveIn.encode(m)!!
        assertEquals(
            """{"date":1790812800000,"notes":"Keys","items":[{"id":"mi_agreement","text":"Signed","done":true,"sort":0},{"id":"mi_police","text":"Police","sort":1}]}""",
            text,
        )
        assertEquals(m, MoveIn.decode(text))
        assertNull(MoveIn.decode("not json"))
        assertNull(MoveIn.encode(null))
    }
}
