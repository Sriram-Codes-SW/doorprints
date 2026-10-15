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

package app.doorprints.shared.voice.pcm

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The in-memory hand-over between the audio thread and the reader (S4b-BL-223, TC-U-253). */
class ByteQueueTest {
    @Test
    fun bytesComeOutInTheOrderTheyWentIn() {
        val q = ByteQueue(8)
        q.write(0x0201.toShort())
        q.write(0x0403.toShort())
        assertEquals(4, q.available)
        val out = ByteArray(8)
        assertEquals(4, q.read(out, 2, 6))
        assertContentEquals(byteArrayOf(0, 0, 1, 2, 3, 4, 0, 0), out)
        assertEquals(0, q.available)
        assertEquals(0, q.read(out, 0, 8))
    }

    @Test
    fun theQueueWrapsAroundItsArray() {
        val q = ByteQueue(4)
        val out = ByteArray(2)
        repeat(5) { round ->
            q.write((round * 256 + round + 1).toShort())
            assertEquals(2, q.read(out, 0, 2))
            assertContentEquals(byteArrayOf((round + 1).toByte(), round.toByte()), out)
        }
    }

    @Test
    fun aFullQueueDropsWholeSamplesAndCountsThem() {
        val q = ByteQueue(5)
        q.write(1)
        q.write(2)
        q.write(3)
        assertEquals(4, q.available)
        assertEquals(2, q.dropped)
        val out = ByteArray(8)
        assertEquals(4, q.read(out, 0, 8))
        assertContentEquals(byteArrayOf(1, 0, 2, 0), out.copyOf(4))
    }

    @Test
    fun clearForgetsEverything() {
        val q = ByteQueue(8)
        q.write(0x7f7f)
        q.clear()
        assertEquals(0, q.available)
        assertEquals(0, q.read(ByteArray(4), 0, 4))
    }

    @Test
    fun badArgumentsAreRefused() {
        assertFailsWith<IllegalArgumentException> { ByteQueue(0) }
        assertFailsWith<IllegalArgumentException> { ByteQueue(4).read(ByteArray(2), 1, 2) }
    }
}
