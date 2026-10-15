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

/**
 * What one voice recording is, and what a recorder must promise (voice input, docs/03 ADR-37, docs/ai/voice-input.md).
 * The platform part (Android: `AudioRecord`; the iPhone later) implements [VoiceRecorder]; the rules that keep the
 * microphone safe (the limits, every way out, the words never auto-sent) are in [VoiceSession], in common code, and are
 * tested with a fake recorder and no microphone.
 */

/** Why a recording ended by itself: the time limit ([AudioLimits.MAX_DURATION_MS]) or the size cap. */
enum class VoiceLimit { TIME, SIZE }

/** Why a recorder could not start or kept going no longer. */
enum class VoiceFailure { DENIED, NO_DEVICE, FAILED }

/** A recorder that could not start or stopped working; [reason] only, never a sample of the audio. */
class VoiceRecorderException(val reason: VoiceFailure) : RuntimeException("voice recorder: ${reason.name.lowercase()}")

/**
 * The audio of one recording, in memory only (T-I48). [mime] is a type the transcribers accept ([AudioClip.mimeOf]);
 * [limit] is set when the recording ended at a limit. [toString] gives the size, never the bytes, so a clip printed by
 * mistake does not reach a log.
 */
class RecordedClip(val bytes: ByteArray, val mime: String, val durationMs: Long, val limit: VoiceLimit? = null) {
    override fun toString(): String = "RecordedClip(${bytes.size} bytes, $mime, $durationMs ms)"
}

/** The limits of one recording (T-I47, T-D14), the same as the website's. */
object AudioLimits {
    /** The hard stop: the transcribers refuse a longer clip ([app.doorprints.shared.ai.AudioClip.MAX_DURATION_MS]). */
    const val MAX_DURATION_MS: Long = app.doorprints.shared.ai.AudioClip.MAX_DURATION_MS

    /** Sarvam's hard stop: its REST call takes up to 30 s ([app.doorprints.shared.ai.AudioClip.SARVAM_MAX_DURATION_MS]). */
    const val SARVAM_MAX_DURATION_MS: Long = app.doorprints.shared.ai.AudioClip.SARVAM_MAX_DURATION_MS

    /** The size cap: the transcribers refuse a larger clip ([app.doorprints.shared.ai.AudioClip.MAX_BYTES]). */
    const val MAX_BYTES: Int = app.doorprints.shared.ai.AudioClip.MAX_BYTES
}

/**
 * A microphone recorder that keeps the audio in memory and nowhere else. A recorder is used once: [start], then [stop]
 * (the clip) or [release] (nothing), and then it is finished. The microphone is opened in [start] only, which the person's
 * tap reaches and nothing else does.
 *
 * Every way out lets the microphone go: [stop], [release] and a failure all release it, and [release] is safe to call
 * again. The recorder writes no file, log line or preference.
 */
interface VoiceRecorder {
    /** Opens the microphone and starts keeping the audio; throws [VoiceRecorderException] ([VoiceFailure.DENIED] without the permission). */
    fun start()

    /** How long the recording has run, in milliseconds, from the audio kept (not from a clock). */
    fun elapsedMs(): Long

    /** The audio kept so far, in bytes. */
    fun sizeBytes(): Int

    /** Non-null once the recorder can no longer record (the microphone was taken, the device went away): the session ends the recording. */
    fun failure(): VoiceFailure?

    /** Stops, lets the microphone go and returns the clip; throws [VoiceRecorderException] when there is none. */
    fun stop(): RecordedClip

    /** Stops, lets the microphone go and drops the audio. Idempotent. */
    fun release()
}
