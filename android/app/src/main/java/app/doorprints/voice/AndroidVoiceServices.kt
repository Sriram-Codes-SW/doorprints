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
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import app.doorprints.data.SettingsStore
import app.doorprints.shared.ai.Transcript
import app.doorprints.shared.voice.OwnKeyTranscription
import app.doorprints.shared.voice.PcmVoiceRecorder
import app.doorprints.shared.voice.RecordedClip
import app.doorprints.shared.voice.VoiceRecorder
import app.doorprints.shared.voice.VoiceTierGate
import app.doorprints.ui.VoiceServices
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers

/**
 * *Voice input* on Android (S4b-BL-222, docs/03 ADR-37): the microphone ([AudioRecordPcmSource] inside a
 * [PcmVoiceRecorder], in memory only), the `RECORD_AUDIO` runtime permission and the call to the person's own AI
 * ([OwnKeyTranscription], the existing own-key path on the app-wide HTTP client). The permission is asked for only by
 * [rememberMicrophoneRequest], which the microphone button calls after the person's tap and a rationale; it is never
 * requested at install or start-up. One per process, in `AndroidAppServices`; it holds the application, never an activity.
 */
class AndroidVoiceServices(
    private val app: Application,
    settings: SettingsStore,
    http: () -> HttpClient,
) : VoiceServices {
    override val tierGate: VoiceTierGate by lazy { VoiceTierGate(settings, http()) }
    private val transcription by lazy { OwnKeyTranscription(settings, http(), tierGate) }

    override val supported: Boolean get() = true

    override val recordable: Boolean by lazy { app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) }

    override fun newRecorder(): VoiceRecorder = PcmVoiceRecorder(AudioRecordPcmSource(app), Dispatchers.IO)

    override fun microphoneGranted(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Composable
    override fun rememberMicrophoneRequest(onResult: (Boolean) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult(it) }
        return {
            try {
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            } catch (_: ActivityNotFoundException) {
                onResult(false)
            }
        }
    }

    override suspend fun transcribe(clip: RecordedClip, kind: String, appLanguage: String?): Transcript =
        transcription.transcribe(clip, kind, appLanguage)
}
