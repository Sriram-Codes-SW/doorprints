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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.shared.voice.VoiceChoice
import app.doorprints.shared.voice.VoiceInput
import app.doorprints.shared.voice.VoiceProvider
import app.doorprints.ui.res.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Tag of the *Voice input* block in Settings > *AI features*, for tests. */
const val VOICE_SETTING_TAG = "voice-setting"

/** Tags of the Sarvam key field and its buttons, for tests. */
const val VOICE_SARVAM_KEY_TAG = "voice-sarvam-key"
const val VOICE_SARVAM_SAVE_TAG = "voice-sarvam-save"
const val VOICE_SARVAM_REMOVE_TAG = "voice-sarvam-remove"

/**
 * The *Voice input* setting under the AI service (voice input, docs/03 ADR-37, S4b-BL-222, S4b-BL-224): off until the
 * person turns it on, on this phone only (a plain preference, never in a backup, copy or sync file). Two services can write
 * out the words: the person's **own** AI key with a service that takes audio (Gemini, OpenAI, Groq, OpenRouter), or **Sarvam**
 * with a Sarvam key of its own, kept in a slot apart from the AI key (never in a backup or copy; [VoiceProvider]). For the
 * connected server's AI, Anthropic, Ollama, LM Studio or a custom address, the first choice says why there is no switch
 * instead of offering one that does nothing. The disclosure is on screen with the switch, before it is turned on: where the
 * audio goes (the host is named), the list price of a minute, and that a number or a name said aloud is sent as audio and is
 * not redacted as typed text is. Nothing is drawn while the own AI has no usable key yet (the form above says what is
 * missing) or where the platform has no voice input.
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
    val keyHint by repo.settings.sarvamKeyHint.collectAsStateWithLifecycle("")
    var typed by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    if (!ownActive && !serverChosen) return
    val sarvam = choice.provider == VoiceProvider.SARVAM
    Column(Modifier.fillMaxWidth().testTag(VOICE_SETTING_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(Res.string.voice_provider), style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup()) {
            ProviderRow(
                selected = !sarvam, enabled = true, title = stringResource(Res.string.voice_provider_own), hint = "",
                onSelect = { scope.launch { repo.settings.saveVoiceProvider(VoiceProvider.OWN_AI) } },
            )
            ProviderRow(
                selected = sarvam, enabled = true, title = stringResource(Res.string.voice_provider_sarvam), hint = "",
                onSelect = { scope.launch { repo.settings.saveVoiceProvider(VoiceProvider.SARVAM) } },
            )
        }
        if (sarvam) {
            KeyField(
                value = typed,
                onChange = { typed = it },
                label = stringResource(Res.string.voice_sarvam_key),
                hint = if (choice.sarvamKeySaved && keyHint.isNotEmpty()) stringResource(Res.string.voice_sarvam_key_saved, keyHint)
                else if (choice.sarvamKeySaved) stringResource(Res.string.voice_sarvam_key_saved, "")
                else stringResource(Res.string.voice_sarvam_key_hint),
                showKey = showKey, onShowKey = { showKey = !showKey },
                error = null, modifier = Modifier.testTag(VOICE_SARVAM_KEY_TAG),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = typed.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag(VOICE_SARVAM_SAVE_TAG),
                    onClick = {
                        val key = typed
                        typed = ""
                        scope.launch { repo.settings.saveSarvamKey(key) }
                    },
                ) { Text(stringResource(Res.string.voice_sarvam_save)) }
                if (choice.sarvamKeySaved) {
                    OutlinedButton(
                        modifier = Modifier.heightIn(min = 48.dp).testTag(VOICE_SARVAM_REMOVE_TAG),
                        onClick = { scope.launch { repo.settings.removeSarvamKey() } },
                    ) { Text(stringResource(Res.string.voice_sarvam_remove)) }
                }
            }
        }
        if (VoiceInput.usable(choice, ownActive)) {
            SwitchRow(
                text = stringResource(Res.string.voice_switch),
                hint = stringResource(if (sarvam) Res.string.voice_switch_hint_sarvam else Res.string.voice_switch_hint),
                checked = choice.enabled,
                horizontalPadding = 0.dp,
                onChange = { on -> scope.launch { repo.settings.saveVoiceInput(on) } },
            )
            Text(
                stringResource(
                    if (sarvam) Res.string.voice_disclosure_sarvam else Res.string.voice_disclosure,
                    VoiceInput.host(choice, voiceHost(choice.config)), VoiceInput.price(choice).orEmpty(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (choice.enabled && !voice.recordable) {
                Text(stringResource(Res.string.voice_no_recorder), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text(
                stringResource(if (sarvam) Res.string.voice_needs_sarvam_key else Res.string.voice_needs_own_key),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
