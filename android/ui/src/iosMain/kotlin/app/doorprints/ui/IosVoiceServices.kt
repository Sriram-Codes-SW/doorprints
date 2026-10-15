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

package app.doorprints.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.doorprints.data.SettingsStore
import app.doorprints.shared.ai.Transcript
import app.doorprints.shared.voice.OwnKeyTranscription
import app.doorprints.shared.voice.PcmVoiceRecorder
import app.doorprints.shared.voice.RecordedClip
import app.doorprints.shared.voice.VoiceRecorder
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext

/**
 * *Voice input* on the iPhone (S4b-BL-223, docs/03 ADR-37): the microphone ([IosPcmSource] inside the shared
 * [PcmVoiceRecorder], in memory only), iOS's microphone permission ([requestMicrophone]) and the call to the person's own
 * AI ([OwnKeyTranscription], the same code Android runs, on the app's HTTP client). The permission prompt is reached only
 * through [rememberMicrophoneRequest], which the microphone button calls after the person's tap and a rationale; nothing
 * asks at install or start-up. One per process, in [IosAppServices].
 */
internal class IosVoiceServices(
    settings: SettingsStore,
    http: () -> HttpClient,
) : VoiceServices {
    private val transcription by lazy { OwnKeyTranscription(settings, http()) }

    /**
     * The one thread the capture loop blocks on (`read` waits for the next buffer): kotlinx.coroutines has no public IO
     * dispatcher on Kotlin/Native, and a blocked `Dispatchers.Default` thread would be one of few. One recording runs at a
     * time, and [IosPcmSource.close] wakes the read, so the thread is free again at once. Created on the first recording.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private val captureThread: CoroutineDispatcher by lazy { newSingleThreadContext("doorprints-voice") }

    override val supported: Boolean get() = true

    override val iphoneWording: Boolean get() = true

    override val recordable: Boolean get() = microphoneInputAvailable()

    override fun newRecorder(): VoiceRecorder = PcmVoiceRecorder(IosPcmSource(), captureThread)

    override fun microphoneGranted(): Boolean = microphonePermission() == MicrophonePermission.Granted

    @Composable
    override fun rememberMicrophoneRequest(onResult: (Boolean) -> Unit): () -> Unit {
        val latest by rememberUpdatedState(onResult)
        return remember { { requestMicrophone { granted -> latest(granted) } } }
    }

    override suspend fun transcribe(clip: RecordedClip, kind: String, appLanguage: String?): Transcript =
        transcription.transcribe(clip, kind, appLanguage)
}
