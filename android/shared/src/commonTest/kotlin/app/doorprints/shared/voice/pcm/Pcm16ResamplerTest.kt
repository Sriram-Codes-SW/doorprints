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
import kotlin.test.assertTrue

/** The arithmetic between the iPhone's microphone and the 16 kHz clip the transcribers take (S4b-BL-223, TC-U-253). */
class Pcm16ResamplerTest {
    private fun run(rate: Double, input: FloatArray, chunk: Int = input.size.coerceAtLeast(1)): List<Short> {
        val r = Pcm16Resampler(rate)
        val out = mutableListOf<Short>()
        var at = 0
        while (at < input.size) {
            val n = minOf(chunk, input.size - at)
            r.push(input.copyOfRange(at, at + n), n) { out += it }
            at += n
        }
        return out
    }

    @Test
    fun aConstantStaysConstantWhenDownsampledFrom48kHz() {
        val out = run(48_000.0, FloatArray(4800) { 0.5f })
        assertEquals(1600, out.size)
        assertTrue(out.all { it == 16384.toShort() }, "0.5 of full scale, rounded")
    }

    @Test
    fun eachOutputSampleIsTheMeanOfTheInputSamplesInItsSlot() {
        // Three input samples per output sample at 48 kHz.
        val out = run(48_000.0, floatArrayOf(0f, 0.3f, 0.6f, -0.3f, -0.3f, -0.3f))
        assertEquals(listOf(9830.toShort(), (-9830).toShort()), out)
    }

    @Test
    fun aRateThatDoesNotDivideEvenlyKeepsTheLengthWithinOneSample() {
        val out = run(44_100.0, FloatArray(44_100) { 0.25f })
        assertTrue(out.size in 15_999..16_000, "got ${out.size}")
        assertTrue(out.all { it == 8192.toShort() })
    }

    @Test
    fun cuttingTheInputIntoBuffersOfAnySizeGivesTheSameSamples() {
        val signal = FloatArray(10_000) { i -> ((i * 37) % 200 - 100) / 100f }
        val whole = run(44_100.0, signal)
        for (chunk in listOf(1, 7, 100, 441, 4096)) assertEquals(whole, run(44_100.0, signal, chunk), "chunk $chunk")
    }

    @Test
    fun aSourceAt16kHzPassesThroughUnchanged() {
        val out = run(16_000.0, floatArrayOf(0f, 0.25f, -0.25f, 1f, -1f))
        assertEquals(listOf<Short>(0, 8192, -8192, 32767, -32767), out)
    }

    @Test
    fun aSlowerSourceHoldsEachSample() {
        val out = run(8_000.0, floatArrayOf(0.25f, -0.25f))
        assertEquals(listOf<Short>(8192, 8192, -8192, -8192), out)
    }

    @Test
    fun loudNoiseIsClippedAndANotANumberIsSilence() {
        val out = run(16_000.0, floatArrayOf(2f, -3f, Float.NaN, Float.POSITIVE_INFINITY))
        assertEquals(listOf<Short>(32767, -32767, 0, 32767), out)
    }

    @Test
    fun loudNoiseIsClippedBeforeItIsAveragedWithItsNeighbours() {
        // One slot of three: a clipped 1.0 and two silent samples average to a third, not to the loud 3.0's whole.
        assertEquals(listOf<Short>(10922), run(48_000.0, floatArrayOf(3f, 0f, 0f)))
    }

    @Test
    fun pushToWritesLittleEndianBytes() {
        val queue = ByteQueue(16)
        Pcm16Resampler(16_000.0).pushTo(floatArrayOf(0.5f, -0.25f), 2, queue)
        val bytes = ByteArray(4)
        assertEquals(4, queue.read(bytes, 0, 4))
        assertContentEquals(byteArrayOf(0x00, 0x40, 0x00, 0xE0.toByte()), bytes)
    }

    @Test
    fun onlyCountSamplesAreRead() {
        val out = mutableListOf<Short>()
        Pcm16Resampler(16_000.0).push(floatArrayOf(0.5f, 0.5f, 0.5f), 1) { out += it }
        assertEquals(1, out.size)
        assertFailsWith<IllegalArgumentException> { Pcm16Resampler(16_000.0).push(FloatArray(2), 3) {} }
    }

    @Test
    fun aRateOutOfRangeIsRefusedAtOnce() {
        assertFailsWith<IllegalArgumentException> { Pcm16Resampler(0.0) }
        assertFailsWith<IllegalArgumentException> { Pcm16Resampler(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { Pcm16Resampler(1_000_000.0) }
        assertFailsWith<IllegalArgumentException> { Pcm16Resampler(48_000.0, 0) }
    }

    @Test
    fun channelsAreAveragedIntoMono() {
        val left = floatArrayOf(1f, 0f, -1f)
        val right = floatArrayOf(0f, 0f, -0.5f)
        val mono = FloatArray(3)
        mixToMono(2, 3, mono) { c, i -> if (c == 0) left[i] else right[i] }
        assertContentEquals(floatArrayOf(0.5f, 0f, -0.75f), mono)
        val one = FloatArray(3)
        mixToMono(1, 3, one) { _, i -> left[i] }
        assertContentEquals(left, one)
        assertFailsWith<IllegalArgumentException> { mixToMono(0, 1, FloatArray(1)) { _, _ -> 0f } }
        assertFailsWith<IllegalArgumentException> { mixToMono(1, 5, FloatArray(1)) { _, _ -> 0f } }
    }
}
