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

package app.doorprints

import androidx.compose.runtime.Composable
import app.doorprints.data.Repository
import app.doorprints.data.SettingsStore
import app.doorprints.shared.ai.Transcript
import app.doorprints.shared.api.AskResponseDto
import app.doorprints.shared.api.HouseDraftDto
import app.doorprints.shared.model.SpaceView
import app.doorprints.shared.voice.RecordedClip
import app.doorprints.shared.voice.VoiceFailure
import app.doorprints.shared.voice.VoiceRecorder
import app.doorprints.shared.voice.VoiceRecorderException
import app.doorprints.shared.voice.VoiceTierGate
import app.doorprints.ui.VoiceServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The fakes of the voice tests (S4b-BL-222): no microphone, no permission dialog, no provider. A [FakeMicRecorder] counts how
 * it was opened and let go; [FakeVoiceServices] stands for the platform; [FakeVoiceRepository] says whether the person's own AI
 * answers and counts the AI calls a screen makes (a transcript must never make one on its own).
 */
class FakeMicRecorder(private val failOnStart: VoiceFailure? = null) : VoiceRecorder {
    var started = 0
    var stopped = 0
    var released = 0
    var ms = 3_000L
    override fun start() {
        started++
        failOnStart?.let { throw VoiceRecorderException(it) }
    }
    override fun elapsedMs() = ms
    override fun sizeBytes() = 1_000
    override fun failure(): VoiceFailure? = null
    override suspend fun stop(): RecordedClip {
        stopped++
        released++
        return RecordedClip(ByteArray(10) { 1 }, "audio/wav", ms)
    }
    override fun release() {
        released++
    }
}

class FakeVoiceServices(
    override val supported: Boolean = true,
    override val recordable: Boolean = true,
    var granted: Boolean = false,
    /** What the system prompt answers when it is shown. */
    var grantOnRequest: Boolean = true,
) : VoiceServices {
    val recorders = mutableListOf<FakeMicRecorder>()
    var failOnStart: VoiceFailure? = null
    var requests = 0
    var words = "two bhk near Baner"
    val clips = mutableListOf<RecordedClip>()
    val kinds = mutableListOf<String>()

    /** The free-plan rule (ADR-37); a test builds one over its own settings and a fake provider. */
    override var tierGate: VoiceTierGate? = null

    override fun newRecorder(): VoiceRecorder = FakeMicRecorder(failOnStart).also { recorders += it }
    override fun microphoneGranted() = granted

    @Composable
    override fun rememberMicrophoneRequest(onResult: (Boolean) -> Unit): () -> Unit = {
        requests++
        granted = grantOnRequest
        onResult(granted)
    }

    override suspend fun transcribe(clip: RecordedClip, kind: String, appLanguage: String?): Transcript {
        clips += clip
        kinds += kind
        return Transcript(words, "en-IN")
    }
}

class FakeVoiceRepository(real: Repository, private val store: SettingsStore) : Repository by real {
    override val settings: SettingsStore get() = store
    val on = MutableStateFlow(true)
    val own = MutableStateFlow(true)
    override val aiEnabled: StateFlow<Boolean> get() = on
    override val aiOwnKey: StateFlow<Boolean> get() = own
    val asked = mutableListOf<String>()
    val extracted = mutableListOf<String>()
    override suspend fun ask(question: String, space: SpaceView?): AskResponseDto {
        asked += question
        return AskResponseDto(answer = "An answer.", grounded = true)
    }
    override suspend fun extractListing(text: String): HouseDraftDto {
        extracted += text
        return HouseDraftDto()
    }
    override suspend fun extractListing(text: String, kind: String): HouseDraftDto = extractListing(text)
}
