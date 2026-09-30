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

import app.doorprints.data.PhotoEntity
import app.doorprints.shared.model.HouseRoom
import app.doorprints.shared.model.MoveIn
import app.doorprints.shared.model.MoveInItem
import app.doorprints.shared.model.PhotoMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Moving in card's pure parts (docs/11 5.24, slice 5): an empty move-in is none, and the condition record's groups. */
class MovingInSectionTest {
    private fun photo(id: String, roomId: String?, tags: List<String>, at: Long) =
        PhotoEntity(id, "h1", "/p/$id.jpg", createdAt = at).withMeta(PhotoMeta(roomId, tags, null, 1), dirty = false)

    @Test fun anEmptyMoveInIsNoneAndAnyValueKeepsIt() {
        assertNull(moveInOf(null, "", emptyList()))
        assertEquals(MoveIn(notes = "n"), moveInOf(null, "n", emptyList()))
        assertEquals(MoveIn(date = 5, items = listOf(MoveInItem("a", "t"))), moveInOf(5, null, listOf(MoveInItem("a", "t"))))
    }

    @Test fun theConditionRecordIsTheMoveInPhotosByRoomInTheRoomsOrderThenTheRest() {
        val hall = HouseRoom("hall", "HALL", sort = 1)
        val kitchen = HouseRoom("kit", "KITCHEN", sort = 0)
        val photos = listOf(
            photo("p1", "hall", listOf("MOVE_IN"), 3), photo("p2", "kit", listOf("MOVE_IN", "LEAK"), 2),
            photo("p3", "kit", listOf("LEAK"), 1), photo("p4", null, listOf("MOVE_IN"), 4),
            photo("p5", "gone", listOf("MOVE_IN"), 5), photo("p6", "hall", listOf("MOVE_IN"), 1),
        )
        val record = conditionRecord(photos, listOf(hall, kitchen))
        assertEquals(listOf("kit", "hall", null), record.map { it.first?.id })
        assertEquals(listOf(listOf("p2"), listOf("p6", "p1"), listOf("p4", "p5")), record.map { g -> g.second.map { it.id } })
        assertEquals(emptyList(), conditionRecord(photos.filter { it.id == "p3" }, null))
    }
}
