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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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

    @Test fun theBufferedTailHandedOutAfterStopIsInTheClip() = runBlocking<Unit> {
        val mic = DrainingMic(live = 3, tail = 2)
        val r = PcmVoiceRecorder(mic, Dispatchers.Default)
        r.start()
        Thread.sleep(100)
        val clip = r.stop()
        assertEquals(5 * 3_200 + 44, clip.bytes.size)
        assertEquals(1, mic.closed)
    }

    @Test fun theCapStillHoldsWithADrainingSource() = runBlocking<Unit> {
        val r = PcmVoiceRecorder(DrainingMic(live = 10_000, tail = 10_000), Dispatchers.Default)
        r.start()
        Thread.sleep(300)
        assertTrue(r.stop().bytes.size <= AudioLimits.MAX_BYTES)
    }

    @Test fun releaseDiscardsEvenWithABufferedTail() = runBlocking<Unit> {
        val mic = DrainingMic(live = 3, tail = 3)
        val r = PcmVoiceRecorder(mic, Dispatchers.Default)
        r.start()
        r.release()
        assertFailsWith<VoiceRecorderException> { r.stop() }
        assertEquals(1, mic.closed)
    }

    /** Gives one chunk, then blocks in [read] until closed: the capture loop never finishes by itself. */
    private class StuckMic : PcmSource {
        private val gate = java.util.concurrent.CountDownLatch(1)
        @Volatile var closed = 0
        private var given = false
        override fun open() {}
        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (!given) { given = true; into.fill(3, offset, offset + 3_200); return 3_200 }
            gate.await()
            return -1
        }
        override fun end(): Boolean = true
        override fun close() { closed++; gate.countDown() }
    }

    /** S4b-BL-317: Stop waits for the tail by suspending, so the calling thread runs other work meanwhile. */
    @Test fun stopWaitsForTheTailWithoutBlockingTheCallingThread() = runBlocking<Unit> {
        val r = PcmVoiceRecorder(StuckMic(), Dispatchers.Default)
        r.start()
        Thread.sleep(50) // the chunk is in; the loop is now stuck in read
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val began = System.nanoTime()
        val stopping = async { val clip = r.stop(); order += "stop"; clip }
        launch { order += "other" } // runs on this same single thread as soon as stop() suspends
        val clip = stopping.await()
        val tookMs = (System.nanoTime() - began) / 1_000_000
        assertEquals("the thread was free while Stop waited", listOf("other", "stop"), order)
        assertTrue("the bounded wait still happens ($tookMs ms)", tookMs >= 250)
        assertTrue("and it is bounded ($tookMs ms)", tookMs < 5_000)
        assertEquals("what was captured is the clip", 3_200 + 44, clip.bytes.size)
    }

    @Test fun cancellingTheCallerOfStopStillLetsTheMicrophoneGo() = runBlocking<Unit> {
        val mic = StuckMic()
        val r = PcmVoiceRecorder(mic, Dispatchers.Default)
        r.start()
        Thread.sleep(50)
        val stopping = launch { r.stop() }
        yield()
        stopping.cancelAndJoin()
        assertFailsWith<VoiceRecorderException> { r.stop() } // nothing is left of the recording
        assertEquals("the microphone was let go", 1, mic.closed)
    }

    @Test fun releaseDuringTheWaitDropsTheAudioAndStopHasNoClip() = runBlocking<Unit> {
        val r = PcmVoiceRecorder(StuckMic(), Dispatchers.Default)
        r.start()
        Thread.sleep(50)
        val stopping = async { runCatching { r.stop() } }
        yield()
        r.release()
        assertTrue("no clip after a release", stopping.await().exceptionOrNull() is VoiceRecorderException)
    }
}
