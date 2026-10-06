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

package app.doorprints.shared.trace

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalkCodecTest {
    private fun p(latE6: Long, lonE6: Long, sec: Long, start: Long = 1_700_000_000_000) =
        TracePoint(latE6 / 1e6, lonE6 / 1e6, start + sec * 1000)

    @Test
    fun aWalkRoundTripsToTheQuantisation() {
        val walk = listOf(
            TracePoint(12.971599, 77.594566, 1_700_000_000_000),
            TracePoint(12.971650123, 77.594601987, 1_700_000_018_400), // 18.4 s: rounds to 18
            TracePoint(12.97, 77.59, 1_700_000_100_000, walkId = 42, resumed = true), // negative changes
        )
        val back = WalkCodec.decode(WalkCodec.encode(walk), walk.first().atMs, walk.size)!!
        assertEquals(3, back.size)
        walk.zip(back).forEach { (a, b) ->
            assertEquals(a.lat, b.lat, 5.1e-7)
            assertEquals(a.lon, b.lon, 5.1e-7)
        }
        assertEquals(1_700_000_018_000, back[1].atMs)
        assertEquals(1_700_000_100_000, back[2].atMs)
        assertEquals(1, WalkCodec.encode(walk)[0].toInt())
    }

    @Test
    fun varintBoundariesOfTheChangesRoundTrip() {
        for (d in longArrayOf(63, 64, 8191, 8192, -64, -65, -8192, -8193, 127, 128, 16383, 16384, 2_097_151, 2_097_152)) {
            for (dt in longArrayOf(0, 1, 127, 128, 16383, 16384)) {
                val walk = listOf(p(1_000_000, 2_000_000, 0), p(1_000_000 + d, 2_000_000 - d, dt))
                val back = WalkCodec.decode(WalkCodec.encode(walk), walk[0].atMs, 2)
                assertEquals(walk[1].lat, back!![1].lat, 1e-9, "d=$d dt=$dt")
                assertEquals(walk[1].lon, back[1].lon, 1e-9, "d=$d dt=$dt")
                assertEquals(walk[1].atMs, back[1].atMs, "d=$d dt=$dt")
            }
        }
    }

    @Test
    fun theWholeEarthsRangeRoundTrips() {
        val walk = listOf(p(-90_000_000, -180_000_000, 0), p(90_000_000, 180_000_000, 1), p(-90_000_000, -180_000_000, 2))
        val back = WalkCodec.decode(WalkCodec.encode(walk), walk[0].atMs, 3)!!
        assertEquals(walk.map { it.lat }, back.map { it.lat })
        assertEquals(walk.map { it.lon }, back.map { it.lon })
    }

    @Test
    fun fiveThousandTypicalPointsAreUnder40Kilobytes() {
        val rnd = Lcg(5)
        var lat = 12_971_599L
        var lon = 77_594_566L
        val walk = List(TraceConstants.MAX_WALK_POINTS) { i ->
            lat += ((rnd.next() - 0.5) * 400).toLong(); lon += ((rnd.next() - 0.5) * 400).toLong()
            p(lat, lon, i * 18L)
        }
        val bytes = WalkCodec.encode(walk)
        assertTrue(bytes.size < 40_000, "size ${bytes.size}")
        assertEquals(5000, WalkCodec.decode(bytes, walk.first().atMs, 5000)!!.size)
    }

    @Test
    fun aBlobThatIsNotExactlyTheWalkIsAFailureNotAShortWalk() {
        val walk = listOf(p(1, 1, 0), p(1000, 1000, 10), p(2000, 2000, 20))
        val bytes = WalkCodec.encode(walk)
        val start = walk[0].atMs
        assertNull(WalkCodec.decode(bytes.copyOf(bytes.size - 1), start, 3), "truncated")
        assertNull(WalkCodec.decode(bytes, start, 4), "more points than bytes")
        assertNull(WalkCodec.decode(bytes, start, 2), "bytes left over")
        assertNull(WalkCodec.decode(ByteArray(0), start, 0), "empty")
        assertNull(WalkCodec.decode(byteArrayOf(2) + bytes.copyOfRange(1, bytes.size), start, 3), "unknown version")
        assertNull(WalkCodec.decode(byteArrayOf(1, 0x80.toByte(), 0x80.toByte()), start, 1), "ends inside a varint")
        assertNull(WalkCodec.decode(bytes, start, -1))
        assertContentEquals(bytes, WalkCodec.encode(WalkCodec.decode(bytes, start, 3)!!))
    }

    @Test
    fun morePointsThanTheLimitAreRefusedAndOutOfOrderTimesToo() {
        val many = List(TraceConstants.MAX_WALK_POINTS + 1) { p(it.toLong(), 0, it.toLong()) }
        assertFailsWith<IllegalArgumentException> { WalkCodec.encode(many) }
        assertFailsWith<IllegalArgumentException> { WalkCodec.encode(listOf(p(0, 0, 10), p(1, 1, 5))) }
        assertEquals(0, WalkCodec.decode(WalkCodec.encode(emptyList()), 0, 0)!!.size)
    }
}
