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

package app.doorprints.shared.voice

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * TC-U-250 (docs/06), the drain at Stop (S4b-BL-222): bytes a microphone still holds when the person presses Stop are in the
 * clip (the last word), the 2 MB cap still holds, and release discards. A fake microphone with a buffered tail; real threads.
 */
class PcmDrainTest {
    /** After [end] it hands out [tail] chunks more, then ends the stream. */
    private class DrainingMic(private val live: Int, private val tail: Int) : PcmSource {
        @Volatile var closed = 0
        @Volatile private var ended = false
        private var given = 0
        override fun open() {}
        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (ended) {
                if (given >= live + tail) return -1
            } else if (given >= live) {
                Thread.sleep(2)
                return 0
            }
            given++
            for (i in 0 until 3_200) into[offset + i] = 5
            return 3_200
        }
        override fun end(): Boolean { ended = true; return true }
        override fun close() { closed++ }
    }

    @Test fun theBufferedTailHandedOutAfterStopIsInTheClip() {
        val mic = DrainingMic(live = 3, tail = 2)
        val r = PcmVoiceRecorder(mic, Dispatchers.Default)
        r.start()
        Thread.sleep(100)
        val clip = r.stop()
        assertEquals(5 * 3_200 + 44, clip.bytes.size)
        assertEquals(1, mic.closed)
    }

    @Test fun theCapStillHoldsWithADrainingSource() {
        val r = PcmVoiceRecorder(DrainingMic(live = 10_000, tail = 10_000), Dispatchers.Default)
        r.start()
        Thread.sleep(300)
        assertTrue(r.stop().bytes.size <= AudioLimits.MAX_BYTES)
    }

    @Test fun releaseDiscardsEvenWithABufferedTail() {
        val mic = DrainingMic(live = 3, tail = 3)
        val r = PcmVoiceRecorder(mic, Dispatchers.Default)
        r.start()
        r.release()
        assertFailsWith<VoiceRecorderException> { r.stop() }
        assertEquals(1, mic.closed)
    }
}
