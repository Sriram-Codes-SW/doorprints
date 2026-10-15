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

import kotlin.math.roundToInt

/**
 * Turns the microphone's samples into what the transcribers take: 16 kHz, mono, 16-bit, little-endian PCM (the format of
 * `PcmVoiceRecorder`'s WAV clip). The iPhone's `AVAudioEngine` hands out Float32 samples at the hardware's rate (44.1 or
 * 48 kHz, sometimes 16 kHz or 24 kHz) and its channels; this does the arithmetic in plain Kotlin so it is tested on
 * every machine, and the iPhone's source only copies the samples out of the buffer (voice input, docs/03 ADR-37).
 *
 * The resampling is by area averaging: each output sample is the mean of the input samples that fall in its slot, with
 * the two edge samples weighted by how much of them is inside. For a downsample that is a box filter, which keeps the
 * out-of-band noise from folding into the speech; for an upsample (an 8 kHz source) it holds each sample. State carries
 * over between calls, so a clip cut into buffers of any size gives the same samples as the same clip in one piece.
 * Nothing is written anywhere: the samples go to [push]'s sink only.
 */
class Pcm16Resampler(private val inputRate: Double, private val outputRate: Int = RATE) {
    init {
        require(inputRate >= MIN_RATE && inputRate <= MAX_RATE) { "sample rate out of range" }
        require(outputRate > 0) { "sample rate out of range" }
    }

    /** Input samples that make one output sample. */
    private val step = inputRate / outputRate
    private var sum = 0.0
    private var weight = 0.0

    /**
     * Feeds [count] mono samples of [samples] (floats in -1 to 1; anything outside is clipped) and calls [sink] with each
     * finished 16-bit output sample.
     */
    fun push(samples: FloatArray, count: Int, sink: (Short) -> Unit) {
        require(count in 0..samples.size) { "count out of range" }
        for (i in 0 until count) {
            val x = samples[i].toDouble().let { if (it.isNaN()) 0.0 else it.coerceIn(-1.0, 1.0) }
            var left = 1.0
            while (left > 1e-12) {
                val take = minOf(step - weight, left)
                sum += x * take
                weight += take
                left -= take
                if (weight >= step - 1e-9) {
                    sink(toShort(sum / step))
                    sum = 0.0
                    weight = 0.0
                }
            }
        }
    }

    /** Like [push], appending each sample to [out] as two little-endian bytes. */
    fun pushTo(samples: FloatArray, count: Int, out: ByteQueue) {
        push(samples, count) { s -> out.write(s) }
    }

    companion object {
        /** The rate the transcribers are given (the same as `PcmVoiceRecorder.SAMPLE_RATE`). */
        const val RATE = 16_000
        private const val MIN_RATE = 4_000.0
        private const val MAX_RATE = 384_000.0

        /** A sample in -1 to 1 as a 16-bit value, clipped, rounded to the nearest. */
        internal fun toShort(x: Double): Short = (x.coerceIn(-1.0, 1.0) * 32767.0).roundToInt().toShort()
    }
}

/**
 * Mixes the channels of one buffer into mono: the mean of [channels] samples at each position, for [count] positions.
 * [read] gives channel [c] sample [i]; a one-channel source is copied. [into] is the destination.
 */
fun mixToMono(channels: Int, count: Int, into: FloatArray, read: (channel: Int, index: Int) -> Float) {
    require(channels >= 1 && count in 0..into.size) { "buffer shape out of range" }
    for (i in 0 until count) {
        var total = 0f
        for (c in 0 until channels) total += read(c, i)
        into[i] = total / channels
    }
}
