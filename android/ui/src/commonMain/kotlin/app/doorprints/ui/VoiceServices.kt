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
import app.doorprints.shared.ai.Transcript
import app.doorprints.shared.api.ApiException
import app.doorprints.shared.voice.RecordedClip
import app.doorprints.shared.voice.VoiceFailure
import app.doorprints.shared.voice.VoiceRecorder
import app.doorprints.shared.voice.VoiceRecorderException

/**
 * What *Voice input* needs from the phone around it (docs/03 ADR-37, S4b-BL-222): the microphone, its permission and the
 * call to the person's own AI. The rules are common ([app.doorprints.shared.voice.VoiceInput], `VoiceSession`); only these
 * four things are the platform's. Android: `AndroidVoiceServices` in `:app` (`AudioRecord`, the `RECORD_AUDIO` runtime
 * permission). The iPhone follows in its own change (`AVAudioRecorder`, voice PR 6, S4b-BL-223): until then it has [NoVoice],
 * which draws no microphone at all.
 */
interface VoiceServices {
    /** True where this platform has voice input at all (Android); false draws not even the setting. */
    val supported: Boolean get() = false

    /** True on the iPhone, whose hint and rationale name iOS's own question instead of Android's (the `_ios` strings, as the area wake-up's). */
    val iphoneWording: Boolean get() = false

    /** True where this phone can record: a microphone and a recorder. False draws no microphone button and asks for nothing. */
    val recordable: Boolean get() = false

    /** A new recorder, used once; it opens the microphone only when [VoiceRecorder.start] is called (the person's tap). */
    fun newRecorder(): VoiceRecorder = throw VoiceRecorderException(VoiceFailure.NO_DEVICE)

    /** Whether the microphone permission is held now. Never asks. */
    fun microphoneGranted(): Boolean = false

    /**
     * Returns what shows the system's microphone prompt, and [onResult] with whether it was granted (also when it was
     * refused or cannot be shown again). Called only after the person tapped the microphone and read the rationale.
     */
    @Composable
    fun rememberMicrophoneRequest(onResult: (Boolean) -> Unit): () -> Unit = { onResult(false) }

    /** The words in [clip] from the person's own AI (the existing own-key path); [kind] is the space on screen. Refused with AI_OFF when not allowed. */
    suspend fun transcribe(clip: RecordedClip, kind: String, appLanguage: String?): Transcript = throw ApiException(ApiException.Kind.AI_OFF)
}

/** No voice input (the default; the iPhone until S4b-BL-223): the microphone is not drawn. */
object NoVoice : VoiceServices
