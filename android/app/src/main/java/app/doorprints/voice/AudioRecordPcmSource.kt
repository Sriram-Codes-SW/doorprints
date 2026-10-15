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

package app.doorprints.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import app.doorprints.shared.voice.PcmSource
import app.doorprints.shared.voice.PcmVoiceRecorder
import app.doorprints.shared.voice.VoiceFailure
import app.doorprints.shared.voice.VoiceRecorderException

/**
 * Android's microphone for *Voice input* (S4b-BL-222, docs/03 ADR-37): an [AudioRecord] of 16 kHz mono 16-bit samples that
 * [PcmVoiceRecorder] keeps in memory. Opened only by `open()`, which only the person's tap reaches, and only while the
 * `RECORD_AUDIO` permission is held (it is checked here as well as by the screen). It writes no file: there is no
 * `MediaRecorder` output path, no temp file and no stream. [close] stops and releases the microphone, also from another
 * thread while a [read] waits, and is safe to call again.
 */
class AudioRecordPcmSource(private val context: Context) : PcmSource {
    @Volatile private var record: AudioRecord? = null

    override fun open() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw VoiceRecorderException(VoiceFailure.DENIED)
        }
        val rate = PcmVoiceRecorder.SAMPLE_RATE
        val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw VoiceRecorderException(VoiceFailure.NO_DEVICE)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, rate))
        } catch (_: SecurityException) {
            throw VoiceRecorderException(VoiceFailure.DENIED)
        } catch (_: IllegalArgumentException) {
            throw VoiceRecorderException(VoiceFailure.FAILED)
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw VoiceRecorderException(VoiceFailure.NO_DEVICE)
        }
        try {
            r.startRecording()
        } catch (_: SecurityException) {
            r.release()
            throw VoiceRecorderException(VoiceFailure.DENIED)
        } catch (_: IllegalStateException) {
            r.release()
            throw VoiceRecorderException(VoiceFailure.FAILED)
        }
        // Another app may hold the microphone (a call): startRecording then leaves the state as stopped.
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release()
            throw VoiceRecorderException(VoiceFailure.FAILED)
        }
        record = r
    }

    override fun read(into: ByteArray, offset: Int, length: Int): Int {
        val r = record ?: return -1
        return try {
            r.read(into, offset, length)
        } catch (_: IllegalStateException) {
            -1
        }
    }

    override fun close() {
        val r = record ?: return
        record = null
        try {
            r.stop()
        } catch (_: IllegalStateException) {
            // Already stopped.
        }
        r.release()
    }
}
