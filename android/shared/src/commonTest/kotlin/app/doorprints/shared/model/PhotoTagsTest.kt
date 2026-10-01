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

/** Photo tags and meta (docs/11 5.7, slice 5): the vector M6, what a reader keeps and what is refused. */
class PhotoTagsTest {
    @Test fun m6_aCustomTagEqualToAFixedKeyIsRefusedARepeatIsDroppedAndMoreThanTenAreRefused() {
        // Refused (the server's PUT and a file).
        assertEquals(TagProblem.FIXED_KEY, PhotoTags.validate(listOf("damp")))
        assertEquals(TagProblem.FIXED_KEY, PhotoTags.validate(listOf("Move_In")))
        assertEquals(TagProblem.DUPLICATE, PhotoTags.validate(listOf("Corner", "corner")))
        assertEquals(TagProblem.TOO_MANY, PhotoTags.validate((1..11).map { "t$it" }))
        assertEquals(TagProblem.TOO_LONG, PhotoTags.validate(listOf("x".repeat(31))))
        assertEquals(TagProblem.EMPTY, PhotoTags.validate(listOf(" ")))
        assertNull(PhotoTags.validate(listOf("KITCHEN_FITTINGS", "MOVE_IN", "damp corner")))
        assertNull(PhotoTags.validate((1..10).map { "t$it" }))
        // Coerced (a reader): a repeat is dropped, a fixed key in another case is that key, more than ten keep the first ten.
        assertEquals(listOf("DAMP", "corner"), PhotoTags.coerced(listOf("damp", "DAMP", " corner ", "Corner")))
        assertEquals((1..10).map { "t$it" }, PhotoTags.coerced((1..12).map { "t$it" }))
        assertEquals(listOf("ok"), PhotoTags.coerced(listOf("", "x".repeat(31), "ok")))
    }

    @Test fun theEditorAddsAndRemovesWithoutBreakingTheRules() {
        assertEquals(listOf("MOVE_IN"), PhotoTags.with(emptyList(), "move_in"))
        assertEquals(listOf("MOVE_IN"), PhotoTags.with(listOf("MOVE_IN"), "MOVE_IN"))
        val ten = (1..10).map { "t$it" }
        assertEquals(ten, PhotoTags.with(ten, "eleventh"))
        assertEquals(listOf("t2"), PhotoTags.without(listOf("T1", "t2"), "t1"))
        assertEquals(15, PhotoTags.FIXED.size)
        assertTrue("MOVE_IN" in PhotoTags.FIXED)
    }

    @Test fun theMetaIsCoercedLikeTheWebsAndRefusedLikeTheServers() {
        val meta = PhotoMeta.coerced("r".repeat(65), listOf("leak", "leak"), "c".repeat(250), -3)
        assertEquals(PhotoMeta(null, listOf("LEAK"), "c".repeat(200), 0), meta)
        assertEquals(PhotoMeta(), PhotoMeta.coerced("", null, "  ", null))
        assertTrue(PhotoMeta("r1", listOf("MOVE_IN"), "ok", 5).isValid)
        for (bad in listOf(
            PhotoMeta(roomId = ""), PhotoMeta(roomId = "r".repeat(65)), PhotoMeta(caption = "c".repeat(201)),
            PhotoMeta(metaUpdatedAt = -1), PhotoMeta(tags = listOf("damp")),
        )) assertFalse(bad.isValid, bad.toString())
        assertTrue(PhotoMeta.incomingWins(5, 6))
        assertFalse(PhotoMeta.incomingWins(6, 6))
        assertFalse(PhotoMeta.incomingWins(6, 5))
    }
}
