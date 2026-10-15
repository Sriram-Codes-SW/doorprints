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

import app.doorprints.data.AiProviderChoice
import app.doorprints.data.SettingsStore
import app.doorprints.shared.ai.Transcript
import app.doorprints.shared.api.ApiException
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first

/**
 * The one place a recorded clip becomes text (voice input, ADR-37): the person's **own** AI key, from the slot *AI
 * features* already holds, straight to the provider, with the app-wide HTTP client. There is no server route and no proxy:
 * where the server answers AI, or the provider takes no audio, the call is refused with [ApiException.Kind.AI_OFF] and
 * nothing is sent. The settings are read at the call, so a switch turned off a moment ago is honoured. The clip and the
 * transcript are returned to the caller and kept nowhere here, logged nowhere, put in no error (T-I48).
 */
class OwnKeyTranscription(private val settings: SettingsStore, private val http: HttpClient) {
    /** The words in [clip]; [kind] is the space on screen (key terms for the model), [appLanguage] the app's language. */
    suspend fun transcribe(clip: RecordedClip, kind: String, appLanguage: String?): Transcript {
        val choice = settings.voiceChoice.first()
        val s = settings.current()
        val ownKey = s.aiProvider == AiProviderChoice.DEVICE && s.geminiKey.isNotBlank()
        val transcriber = if (choice.enabled && s.aiFeatures && VoiceInput.providerOk(ownKey, s.aiProviderConfig)) {
            VoiceInput.transcriberFor(http, s.aiProviderConfig, s.geminiKey, kind)
        } else {
            null
        } ?: throw ApiException(ApiException.Kind.AI_OFF)
        return transcriber.transcribe(clip.bytes, clip.mime, VoiceInput.langHint(appLanguage), clip.durationMs)
    }
}
