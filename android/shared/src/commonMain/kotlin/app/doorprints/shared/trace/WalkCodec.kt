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

import kotlin.math.floor

/**
 * `WalkCodec/1` (docs/03 §6.2, Room 11): the compact bytes of a saved walk's points. Byte 0 is the version `1`; then,
 * for each point in time order, the zigzag varints of `dLatE6` and `dLonE6` (the change in `round(lat * 1e6)` and
 * `round(lon * 1e6)` from the previous point; the first point's change is from 0) and the varint of `dtSeconds` (whole
 * seconds since the previous point, 0 for the first, whose time is the walk's `startedAt`). Accuracy and the walk id are
 * not kept. A decoded walk has coordinates rounded to 1e-6 degrees (about 0.11 m) and times rounded to the second
 * counted from `startedAt`. A typical point takes 5 bytes, so 5 000 points are about 25 KB.
 *
 * Pure common code. A blob that does not decode (wrong version, truncated, wrong count, bytes left over, a value out of
 * range) is a failure (`null`), never a short walk.
 */
object WalkCodec {
    const val VERSION = 1
    private const val E6 = 1_000_000.0

    /** The bytes of [points] (in time order, at most [TraceConstants.MAX_WALK_POINTS]). */
    fun encode(points: List<TracePoint>): ByteArray {
        require(points.size <= TraceConstants.MAX_WALK_POINTS) { "a walk holds at most ${TraceConstants.MAX_WALK_POINTS} points" }
        val out = ByteSink()
        out.byte(VERSION)
        var prevLat = 0L
        var prevLon = 0L
        var prevSec = 0L
        val t0 = points.firstOrNull()?.atMs ?: 0L
        for (p in points) {
            val lat = floor(p.lat * E6 + 0.5).toLong()
            val lon = floor(p.lon * E6 + 0.5).toLong()
            val sec = floor((p.atMs - t0) / 1000.0 + 0.5).toLong()
            require(sec >= prevSec) { "points must be in time order" }
            out.varint(zigzag(lat - prevLat))
            out.varint(zigzag(lon - prevLon))
            out.varint(sec - prevSec)
            prevLat = lat; prevLon = lon; prevSec = sec
        }
        return out.toByteArray()
    }

    /**
     * The points of [bytes] for a walk that started at [startedAtMs] and holds [pointCount] points, or null when the
     * blob is not exactly that walk.
     */
    fun decode(bytes: ByteArray, startedAtMs: Long, pointCount: Int): List<TracePoint>? {
        if (bytes.isEmpty() || bytes[0].toInt() != VERSION || pointCount < 0 || pointCount > TraceConstants.MAX_WALK_POINTS) return null
        val src = ByteSource(bytes, 1)
        val points = ArrayList<TracePoint>(pointCount)
        var lat = 0L
        var lon = 0L
        var sec = 0L
        for (i in 0 until pointCount) {
            val dLat = src.varint() ?: return null
            val dLon = src.varint() ?: return null
            val dSec = src.varint()?.takeIf { it >= 0 } ?: return null
            lat += unzigzag(dLat)
            lon += unzigzag(dLon)
            sec += dSec
            if (lat !in -90_000_000L..90_000_000L || lon !in -180_000_000L..180_000_000L) return null
            points.add(TracePoint(lat / E6, lon / E6, startedAtMs + sec * 1000))
        }
        return if (src.atEnd) points else null
    }

    private fun zigzag(v: Long): Long = (v shl 1) xor (v shr 63)
    private fun unzigzag(v: Long): Long = (v ushr 1) xor -(v and 1)

    private class ByteSink {
        private var data = ByteArray(256)
        private var size = 0
        fun byte(b: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = b.toByte()
        }
        fun varint(value: Long) {
            var v = value
            while (v and 0x7fL.inv() != 0L) {
                byte(((v and 0x7f) or 0x80).toInt())
                v = v ushr 7
            }
            byte(v.toInt())
        }
        fun toByteArray(): ByteArray = data.copyOf(size)
    }

    private class ByteSource(private val data: ByteArray, private var pos: Int) {
        val atEnd get() = pos == data.size
        /** The next varint, or null when the bytes end inside it or it is longer than ten bytes. */
        fun varint(): Long? {
            var result = 0L
            var shift = 0
            while (shift < 70) {
                if (pos >= data.size) return null
                val b = data[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return null
        }
    }
}
