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

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Android review, round 12: the list join used to stop at three items, silently dropping a fourth. It also builds the
 * Export screen's partial-backup note, whose whole job is to say what a backup leaves out, so the next `BackupGap` must
 * appear in the note rather than vanish. The English patterns are used here; every language has the same three
 * strings. Common since CMP-6 (was `:app`'s, on `ImportWorker.joinList`).
 */
class JoinListTest {

    private fun join(vararg items: String) = joinList(
        items.toList(),
        two = { a, b -> "$a and $b" },
        three = { a, b, c -> "$a, $b and $c" },
        middle = { a, b -> "$a, $b" },
    )

    @Test
    fun everyItemIsKeptWhateverTheCount() {
        assertEquals("", join())
        assertEquals("a", join("a"))
        assertEquals("a and b", join("a", "b"))
        assertEquals("a, b and c", join("a", "b", "c"))
        assertEquals("a, b, c and d", join("a", "b", "c", "d"))
        assertEquals("a, b, c, d and e", join("a", "b", "c", "d", "e"))
    }
}
