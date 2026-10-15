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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.yield
import kotlin.concurrent.Volatile
import kotlinx.coroutines.launch

/**
 * The microphone as a stream of 16-bit mono PCM at [PcmVoiceRecorder.SAMPLE_RATE], little-endian: Android's `AudioRecord`
 * behind a few lines, a fake in the tests. No method here writes a file.
 */
interface PcmSource {
    /** Opens the microphone and starts it; throws [VoiceRecorderException] ([VoiceFailure.DENIED], [VoiceFailure.NO_DEVICE] or [VoiceFailure.FAILED]). */
    fun open()

    /** Blocks until up to [length] bytes of samples are in [into] from [offset]; returns how many, or a negative number when the microphone is gone. */
    fun read(into: ByteArray, offset: Int, length: Int): Int

    /** Stops and releases the microphone; a [read] waiting on another thread returns. Safe to call again. */
    fun close()
}

/**
 * A [VoiceRecorder] that keeps raw samples in one array in memory, up to the 2 MB cap, and hands them over as a WAV clip
 * (`audio/wav`, which Gemini, OpenAI, Groq and OpenRouter all take). A minute at 16 kHz is 1.92 MB, under the cap, so the
 * time limit comes first. The capture runs on [dispatcher] (the IO threads; a test passes an unconfined one and a source
 * that ends). The array is the only place the audio is: nothing is written to disk, no stream or temp file is made.
 *
 * Every exit lets the microphone go: [stop], [release], a source that fails, and the array full (the capture then ends and
 * [failure] stays null, the session stops at [sizeBytes]).
 */
class PcmVoiceRecorder(
    private val source: PcmSource,
    private val dispatcher: CoroutineDispatcher,
) : VoiceRecorder {
    // One writer (the capture loop) and any number of readers: the loop writes the samples, then publishes [length];
    // a reader that sees a length sees the samples before it. [state] and [failed] are written once or twice.
    @Volatile private var pcm: ByteArray? = ByteArray(PCM_CAP)
    @Volatile private var length = 0
    @Volatile private var state = NEW
    @Volatile private var failed: VoiceFailure? = null
    private var scope: CoroutineScope? = null

    override fun start() {
        check(state == NEW) { "a recorder is used once" }
        state = RUNNING
        try {
            source.open()
        } catch (e: VoiceRecorderException) {
            finish()
            throw e
        } catch (e: Throwable) {
            finish()
            throw VoiceRecorderException(VoiceFailure.FAILED)
        }
        val s = CoroutineScope(dispatcher)
        scope = s
        s.launch { loop() }
    }

    private suspend fun loop() {
        val buffer = pcm ?: return
        val chunk = ByteArray(CHUNK)
        while (state == RUNNING) {
            val n = source.read(chunk, 0, CHUNK)
            if (state != RUNNING) return
            if (n < 0) {
                failed = VoiceFailure.FAILED
                return
            }
            val take = minOf(n, PCM_CAP - length)
            if (take > 0) {
                chunk.copyInto(buffer, length, 0, take)
                length += take
            }
            if (take < n || length >= PCM_CAP) return
            if (n == 0) yield()
        }
    }

    override fun elapsedMs(): Long = length.toLong() * 1000 / BYTES_PER_SECOND

    override fun sizeBytes(): Int = WAV_HEADER + length

    override fun failure(): VoiceFailure? = failed

    override fun stop(): RecordedClip {
        check(state != NEW) { "not started" }
        val buffer = pcm
        if (state != RUNNING || buffer == null) throw VoiceRecorderException(VoiceFailure.FAILED)
        finish()
        val took = length
        if (took == 0) throw VoiceRecorderException(VoiceFailure.FAILED)
        val limit = if (took >= PCM_CAP) VoiceLimit.SIZE else null
        return RecordedClip(wav(buffer, took), "audio/wav", took.toLong() * 1000 / BYTES_PER_SECOND, limit)
    }

    override fun release() {
        finish()
    }

    /** Ends the capture, lets the microphone go and drops the recorder's hold on the audio. */
    private fun finish() {
        state = DONE
        source.close()
        scope?.cancel()
        scope = null
        pcm = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val NEW = 0
        private const val RUNNING = 1
        private const val DONE = 2
        private const val BYTES_PER_SECOND = SAMPLE_RATE * 2
        private const val WAV_HEADER = 44
        private const val CHUNK = 3_200 // 100 ms
        /** The most samples kept, so the whole WAV stays within the 2 MB cap. */
        internal const val PCM_CAP = AudioLimits.MAX_BYTES - WAV_HEADER

        /** [pcm]'s first [size] bytes as a 16 kHz mono 16-bit WAV file. */
        internal fun wav(pcm: ByteArray, size: Int): ByteArray {
            val out = ByteArray(WAV_HEADER + size)
            fun int(at: Int, v: Int) { for (i in 0..3) out[at + i] = (v ushr (8 * i)).toByte() }
            fun short(at: Int, v: Int) { out[at] = v.toByte(); out[at + 1] = (v ushr 8).toByte() }
            "RIFF".encodeToByteArray().copyInto(out, 0)
            int(4, 36 + size)
            "WAVEfmt ".encodeToByteArray().copyInto(out, 8)
            int(16, 16)
            short(20, 1) // PCM
            short(22, 1) // mono
            int(24, SAMPLE_RATE)
            int(28, BYTES_PER_SECOND)
            short(32, 2)
            short(34, 16)
            "data".encodeToByteArray().copyInto(out, 36)
            int(40, size)
            pcm.copyInto(out, WAV_HEADER, 0, size)
            return out
        }
    }
}
