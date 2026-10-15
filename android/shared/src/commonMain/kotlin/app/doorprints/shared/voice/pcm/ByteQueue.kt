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

/**
 * A small first-in first-out queue of bytes in memory, for the microphone's samples between the thread the audio system
 * calls and the one that reads them (the iPhone's source holds a lock around it). Bounded: bytes that do not fit are
 * dropped and counted in [dropped] rather than growing without limit, so a reader that stalls cannot make a recording
 * eat the phone's memory (T-D14, the 2 MB cap sits above this in the recorder). It is not thread-safe by itself and
 * writes nowhere but its own array (T-I48). [clear] zeroes what it held.
 */
class ByteQueue(private val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val data = ByteArray(capacity)
    private var head = 0
    private var size = 0

    /** Bytes waiting to be read. */
    val available: Int get() = size

    /** How many bytes were refused because the queue was full. */
    var dropped: Long = 0
        private set

    /** Adds a 16-bit sample, little-endian; both bytes or neither. */
    fun write(sample: Short) {
        if (capacity - size < 2) {
            dropped += 2
            return
        }
        put(sample.toInt().toByte())
        put((sample.toInt() shr 8).toByte())
    }

    /** Moves up to [length] bytes into [into] from [offset]; returns how many were moved (0 when empty). */
    fun read(into: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= into.size) { "range out of bounds" }
        val n = minOf(length, size)
        for (i in 0 until n) {
            into[offset + i] = data[head]
            data[head] = 0
            head = (head + 1) % capacity
        }
        size -= n
        return n
    }

    /** Forgets everything and zeroes the array. */
    fun clear() {
        data.fill(0)
        head = 0
        size = 0
    }

    private fun put(b: Byte) {
        data[(head + size) % capacity] = b
        size++
    }
}
