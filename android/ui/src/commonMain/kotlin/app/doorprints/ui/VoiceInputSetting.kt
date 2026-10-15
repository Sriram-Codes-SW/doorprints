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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.shared.voice.VoiceChoice
import app.doorprints.shared.voice.VoiceInput
import app.doorprints.ui.res.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Tag of the *Voice input* block in Settings > *AI features*, for tests. */
const val VOICE_SETTING_TAG = "voice-setting"

/**
 * The *Voice input* switch under the AI service (voice input, docs/03 ADR-37, S4b-BL-222): off until the person turns it
 * on, on this phone only (a plain preference, never in a backup, copy or sync file). It works with the person's **own** AI
 * key and a service that takes audio (Gemini, OpenAI, Groq, OpenRouter); for the connected server's AI, Anthropic, Ollama,
 * LM Studio or a custom address it says why there is no switch instead of offering one that does nothing. The disclosure
 * is on screen with the switch, before it is turned on: where the audio goes, the list price of a minute, and that a
 * number or a name said aloud is sent as audio and is not redacted as typed text is. Nothing is drawn while the own AI has
 * no usable key yet (the form above says what is missing) or where the platform has no voice input.
 *
 * [serverChosen] is *Use my server* on the screen above (so the reason is shown for it).
 */
@Composable
internal fun VoiceInputSetting(serverChosen: Boolean) {
    val services = LocalAppServices.current
    val voice = services.voice
    if (!voice.supported) return
    val repo = services.repository
    val scope = rememberCoroutineScope()
    val ownActive by repo.aiOwnKey.collectAsStateWithLifecycle()
    val choice by repo.settings.voiceChoice.collectAsStateWithLifecycle(VoiceChoice())
    if (!ownActive && !serverChosen) return
    Column(Modifier.fillMaxWidth().testTag(VOICE_SETTING_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (VoiceInput.providerOk(ownActive, choice.config)) {
            SwitchRow(
                text = stringResource(Res.string.voice_switch),
                hint = stringResource(Res.string.voice_switch_hint),
                checked = choice.enabled,
                horizontalPadding = 0.dp,
                onChange = { on -> scope.launch { repo.settings.saveVoiceInput(on) } },
            )
            Text(
                stringResource(Res.string.voice_disclosure, voiceHost(choice.config), VoiceInput.priceInr(choice.config).orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (choice.enabled && !voice.recordable) {
                Text(stringResource(Res.string.voice_no_recorder), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text(
                stringResource(Res.string.voice_needs_own_key),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
