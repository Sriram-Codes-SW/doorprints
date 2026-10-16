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

import app.doorprints.shared.voice.AudioLimits
import app.doorprints.shared.voice.PcmSource
import app.doorprints.shared.voice.PcmVoiceRecorder
import app.doorprints.shared.voice.VoiceLimit
import app.doorprints.shared.voice.VoiceFailure
import app.doorprints.shared.voice.VoiceRecorderException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iPhone's capture path without AVFoundation (S4b-BL-223, TC-U-254): a fake "tap" feeds Float32 buffers at 48 kHz
 * through [Pcm16Resampler] into a [ByteQueue], a source with the same rules as `IosPcmSource` (the microphone is opened
 * in `open()` only; once ended, or closed, `read` reports it and the queue is cleared) serves them to the shared
 * [PcmVoiceRecorder], and the clip that comes out is a 16 kHz mono WAV with the tone in it. What the real
 * `AVAudioEngine` does is MT-104..MT-108.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IosCapturePipelineTest {
    /** The shape of `IosPcmSource` with the tap replaced by [tap]: samples are queued by the "audio thread", served by [read]. */
    private class TapSource(private val rate: Double, private val buffers: Int, private val frames: Int = 1024) : PcmSource {
        private val queue = ByteQueue(256 * 1024)
        private val resampler = Pcm16Resampler(rate)
        var opened = 0
        var closed = 0
        var ended = false
        private var delivered = 0
        private var phase = 0

        override fun open() {
            opened++
        }

        /** One tap buffer: a 440 Hz tone at half scale, as the engine would deliver it. */
        private fun tap() {
            val samples = FloatArray(frames) { i -> (0.5 * sin(2 * PI * 440.0 * (phase + i) / rate)).toFloat() }
            phase += frames
            if (!ended) resampler.pushTo(samples, frames, queue)
        }

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (queue.available == 0 && !ended && delivered < buffers) {
                delivered++
                tap()
            }
            if (ended) return -1
            if (queue.available == 0) return -1 // the fake microphone ran dry: the stream ends
            return queue.read(into, offset, length)
        }

        override fun close() {
            closed++
            ended = true
            queue.clear()
        }
    }

    private fun recorder(source: PcmSource) = PcmVoiceRecorder(source, UnconfinedTestDispatcher())

    private fun le16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xff) or (b[at + 1].toInt() shl 8)

    @Test
    fun aToneAt48kHzComesOutAs16kHzMonoWavWithTheToneInIt() = runTest {
        val source = TapSource(rate = 48_000.0, buffers = 47) // 47 * 1024 frames = 1.0027 s
        val r = recorder(source)
        r.start()
        val clip = r.stop()
        assertEquals("audio/wav", clip.mime)
        assertTrue(clip.durationMs in 990..1010, "duration ${clip.durationMs}")
        assertNull(clip.limit)
        assertEquals("RIFF", clip.bytes.decodeToString(0, 4))
        assertEquals(16_000, (0..3).sumOf { (clip.bytes[24 + it].toInt() and 0xff) shl (8 * it) })
        val peak = (44 until clip.bytes.size - 1 step 2).maxOf { abs(le16(clip.bytes, it).toShort().toInt()) }
        assertTrue(peak in 14_000..16_500, "a half-scale tone peaks near 16384, not $peak")
        assertEquals(1, source.opened)
        assertEquals(1, source.closed)
    }

    @Test
    fun theMicrophoneIsNotOpenedByBuildingARecorderAndIsLetGoByEveryExit() = runTest {
        val idle = TapSource(48_000.0, 5)
        recorder(idle)
        assertEquals(0, idle.opened)
        assertEquals(0, idle.closed)

        val released = TapSource(48_000.0, 5)
        recorder(released).also { it.start(); it.release() }
        assertEquals(1, released.closed)

        val stopped = TapSource(48_000.0, 5)
        recorder(stopped).also { it.start(); it.stop() }
        assertEquals(1, stopped.closed)
    }

    @Test
    fun onceTheSourceIsClosedByAnInterruptionItReadsAsGoneAndKeepsNoAudio() {
        val source = TapSource(44_100.0, 1_000)
        source.open()
        val got = source.read(ByteArray(4_096), 0, 4_096)
        assertTrue(got > 0, "audio was served")
        source.close() // what IosPcmSource does on a call, Siri, a route change or the app leaving the front
        source.close() // and again: safe
        assertEquals(-1, source.read(ByteArray(8), 0, 8), "a closed source reads as gone")
    }

    @Test
    fun aSourceThatDiesBeforeAnyAudioGivesNoClip() = runTest {
        val source = TapSource(48_000.0, 0)
        val r = recorder(source)
        r.start()
        assertEquals(VoiceFailure.FAILED, r.failure())
        assertFailsWith<VoiceRecorderException> { r.stop() }
        assertEquals(1, source.closed)
    }

    @Test
    fun aLongRecordingStopsAtTheCapNotLater() = runTest {
        val source = TapSource(48_000.0, 100_000)
        val r = recorder(source)
        r.start()
        assertTrue(r.sizeBytes() <= AudioLimits.MAX_BYTES)
        val clip = r.stop()
        assertTrue(clip.bytes.size <= AudioLimits.MAX_BYTES, "${clip.bytes.size}")
        assertEquals(VoiceLimit.SIZE, clip.limit)
        assertEquals(1, source.closed)
    }
}
