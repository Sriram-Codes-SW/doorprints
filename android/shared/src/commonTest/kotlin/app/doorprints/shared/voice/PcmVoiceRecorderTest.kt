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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TC-U-250 (docs/06): the in-memory recorder (voice PR 5, S4b-BL-222) against a fake microphone: the microphone is opened
 * only in `start()`; the clip is a WAV in memory; the time length comes from the samples; the 2 MB cap ends the capture;
 * a refused permission, no device and a dead microphone are told apart; every exit (stop, release, a failed read, the cap)
 * closes the microphone; a recorder is used once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PcmVoiceRecorderTest {
    /** A microphone that hands out [chunks] chunks of [size] bytes of [fill], then ends the stream with [after]. */
    private class FakeMic(
        private val chunks: Int,
        private val size: Int = 3_200,
        private val fill: Byte = 7,
        private val after: Int = -1,
        private val openFails: VoiceFailure? = null,
    ) : PcmSource {
        var opened = 0
        var closed = 0
        private var given = 0

        override fun open() {
            opened++
            openFails?.let { throw VoiceRecorderException(it) }
        }

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (given >= chunks) return after
            given++
            val n = minOf(size, length)
            for (i in 0 until n) into[offset + i] = fill
            return n
        }

        override fun close() {
            closed++
        }
    }

    private fun recorder(mic: PcmSource) = PcmVoiceRecorder(mic, UnconfinedTestDispatcher())

    @Test
    fun theMicrophoneIsNotOpenedUntilStart() {
        val mic = FakeMic(chunks = 1)
        recorder(mic)
        assertEquals(0, mic.opened)
    }

    @Test
    fun aClipIsAWavInMemoryWithTheLengthOfTheSamples() {
        val mic = FakeMic(chunks = 10)
        val r = recorder(mic)
        r.start()
        assertEquals(1_000L, r.elapsedMs()) // 10 chunks of 3200 bytes = 32000 bytes = 1 s at 16 kHz mono 16-bit
        val clip = r.stop()
        assertEquals("audio/wav", clip.mime)
        assertEquals(1_000L, clip.durationMs)
        assertNull(clip.limit)
        assertEquals(44 + 32_000, clip.bytes.size)
        assertEquals("RIFF", clip.bytes.decodeToString(0, 4))
        assertEquals("WAVEfmt ", clip.bytes.decodeToString(8, 16))
        assertEquals("data", clip.bytes.decodeToString(36, 40))
        assertEquals(36 + 32_000, le(clip.bytes, 4))
        assertEquals(16_000, le(clip.bytes, 24))
        assertEquals(32_000, le(clip.bytes, 40))
        assertContentEquals(ByteArray(32_000) { 7 }, clip.bytes.copyOfRange(44, clip.bytes.size))
        assertEquals(1, mic.closed)
    }

    private fun le(b: ByteArray, at: Int) = (0..3).sumOf { (b[at + it].toInt() and 0xff) shl (8 * it) }

    @Test
    fun theCapEndsTheCaptureAndTheClipSaysItWasTheSize() {
        val mic = FakeMic(chunks = 10_000, size = 3_200)
        val r = recorder(mic)
        r.start()
        assertEquals(AudioLimits.MAX_BYTES, r.sizeBytes())
        val clip = r.stop()
        assertEquals(VoiceLimit.SIZE, clip.limit)
        assertTrue(clip.bytes.size <= AudioLimits.MAX_BYTES, "${clip.bytes.size} bytes")
    }

    @Test
    fun aMinuteOfSamplesIsUnderTheCapSoTheTimeLimitComesFirst() {
        val minute = AudioLimits.MAX_DURATION_MS / 1000 * 32_000
        assertTrue(minute + 44 < AudioLimits.MAX_BYTES)
        assertTrue(PcmVoiceRecorder.PCM_CAP > minute)
    }

    @Test
    fun aRefusedPermissionAndNoDeviceAreToldApartAndCloseTheMicrophone() {
        for (reason in listOf(VoiceFailure.DENIED, VoiceFailure.NO_DEVICE, VoiceFailure.FAILED)) {
            val mic = FakeMic(chunks = 1, openFails = reason)
            val e = assertFailsWith<VoiceRecorderException> { recorder(mic).start() }
            assertEquals(reason, e.reason)
            assertEquals(1, mic.closed, "$reason")
        }
    }

    @Test
    fun anOpenThatThrowsAnythingElseIsAFailureNotACrash() {
        val mic = object : PcmSource {
            var closed = 0
            override fun open() = throw IllegalStateException("boom")
            override fun read(into: ByteArray, offset: Int, length: Int) = -1
            override fun close() { closed++ }
        }
        assertEquals(VoiceFailure.FAILED, assertFailsWith<VoiceRecorderException> { recorder(mic).start() }.reason)
        assertEquals(1, mic.closed)
    }

    @Test
    fun aDeadMicrophoneIsAFailureTheSessionCanSee() {
        val r = recorder(FakeMic(chunks = 2, after = -3))
        r.start()
        assertEquals(VoiceFailure.FAILED, r.failure())
        r.release()
    }

    @Test
    fun releaseClosesTheMicrophoneDropsTheAudioAndIsSafeToRepeat() {
        val mic = FakeMic(chunks = 5)
        val r = recorder(mic)
        r.start()
        r.release()
        r.release()
        assertEquals(2, mic.closed) // close() is idempotent in the source; the recorder asks each time
        assertFailsWith<VoiceRecorderException> { r.stop() }
    }

    @Test
    fun stopLetsTheMicrophoneGoAndASecondStopHasNothing() {
        val mic = FakeMic(chunks = 3)
        val r = recorder(mic)
        r.start()
        r.stop()
        assertEquals(1, mic.closed)
        assertFailsWith<VoiceRecorderException> { r.stop() }
    }

    @Test
    fun nothingHeardIsAFailureToStopNotAnEmptyClip() {
        val r = recorder(FakeMic(chunks = 0))
        r.start()
        assertFailsWith<VoiceRecorderException> { r.stop() }
    }

    @Test
    fun aRecorderIsUsedOnce() {
        val r = recorder(FakeMic(chunks = 1))
        r.start()
        r.release()
        assertFailsWith<IllegalStateException> { r.start() }
    }

    @Test
    fun aClipNeverPrintsItsBytes() {
        val r = recorder(FakeMic(chunks = 2, fill = 90))
        r.start()
        val text = r.stop().toString()
        assertFalse(text.contains("ZZZZ"))
        assertNotNull(Regex("RecordedClip\\(\\d+ bytes, audio/wav, \\d+ ms\\)").matchEntire(text))
    }
}
