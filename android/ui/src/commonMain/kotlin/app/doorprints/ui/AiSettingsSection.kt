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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.doorprints.data.AiOff
import app.doorprints.data.AiProviderChoice
import app.doorprints.data.AppSettings
import app.doorprints.shared.api.ApiException
import app.doorprints.ui.res.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Where a free Gemini key is made. */
const val AI_STUDIO_KEYS_URL = "https://aistudio.google.com/apikey"

/**
 * Settings → *AI features* (docs/03 §12.1, §13.1, ADR-26): this phone's switch, off until turned on; then who answers,
 * **Use my server** (when one is connected) or **Use my own Gemini key on this phone** with the key field, *Save key*,
 * *Test key* and *Remove key*. The key is kept like the server key (Android Keystore, iOS Keychain) and only ever sent
 * to Google; the field starts empty and a saved key is shown by its last four characters.
 */
@Composable
fun AiSettingsSection(settings: AppSettings, aiOff: AiOff?) {
    val repo = LocalAppServices.current.repository
    val platform = LocalPlatformServices.current
    val scope = rememberCoroutineScope()
    // Plain remember on purpose: the key never goes into the saved-state Bundle.
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<ResultTone, String>?>(null) }
    var run by remember { mutableIntStateOf(0) }
    val ok = stringResource(Res.string.settings_gemini_ok)
    val rejected = stringResource(Res.string.settings_gemini_rejected)
    val errorText = aiErrorText()
    val ownKey = settings.aiProvider == AiProviderChoice.DEVICE || !settings.serverConfigured

    SectionHeading(stringResource(Res.string.settings_ai_heading))
    SwitchRow(
        text = stringResource(Res.string.settings_ai_switch),
        hint = stringResource(Res.string.settings_ai_hint),
        checked = settings.aiFeatures,
        horizontalPadding = 0.dp,
        onChange = { on -> scope.launch { repo.setAiFeatures(on) } },
    )
    if (!settings.aiFeatures) return

    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ProviderRow(
            selected = !ownKey,
            enabled = settings.serverConfigured,
            title = stringResource(Res.string.settings_ai_use_server),
            hint = stringResource(if (settings.serverConfigured) Res.string.settings_ai_use_server_hint else Res.string.settings_ai_use_server_none),
            onSelect = { scope.launch { repo.setAiProvider(AiProviderChoice.SERVER) } },
        )
        ProviderRow(
            selected = ownKey,
            enabled = true,
            title = stringResource(Res.string.settings_ai_use_own_key),
            hint = stringResource(Res.string.settings_ai_use_own_key_hint),
            onSelect = { scope.launch { repo.setAiProvider(AiProviderChoice.DEVICE) } },
        )
    }

    if (ownKey) {
        OutlinedTextField(
            key, { key = it; result = null },
            label = { Text(stringResource(Res.string.settings_gemini_key)) },
            supportingText = {
                Text(
                    if (settings.geminiKeyHint.isNotEmpty()) stringResource(Res.string.settings_gemini_key_saved, settings.geminiKeyHint)
                    else stringResource(Res.string.settings_gemini_key_hint),
                )
            },
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (showKey) VisibilityOffIcon else VisibilityIcon,
                        contentDescription = stringResource(if (showKey) Res.string.settings_hide_key else Res.string.settings_show_key),
                    )
                }
            },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { platform.openUrl(AI_STUDIO_KEYS_URL) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(Res.string.settings_gemini_get_key))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && key.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp), onClick = {
                scope.launch {
                    busy = true
                    val tested = repo.testGeminiKey(key)
                    if (tested.isSuccess) {
                        repo.saveGeminiKey(key)
                        key = ""
                        result = ResultTone.SUCCESS to ok
                    } else {
                        val e = tested.exceptionOrNull()
                        result = ResultTone.ERROR to
                            if (e is ApiException && e.kind == ApiException.Kind.AI_KEY_REJECTED) rejected else errorText(e!!)
                    }
                    run++
                    busy = false
                }
            }) { Text(stringResource(Res.string.settings_gemini_save)) }
            if (settings.geminiKey.isNotBlank()) {
                OutlinedButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                    scope.launch {
                        busy = true
                        val tested = repo.testGeminiKey(settings.geminiKey)
                        val e = tested.exceptionOrNull()
                        result = when {
                            e == null -> ResultTone.SUCCESS to ok
                            e is ApiException && e.kind == ApiException.Kind.AI_KEY_REJECTED -> ResultTone.ERROR to rejected
                            else -> ResultTone.ERROR to errorText(e)
                        }
                        run++
                        busy = false
                    }
                }) { Text(stringResource(Res.string.settings_gemini_test)) }
                OutlinedButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                    scope.launch {
                        repo.removeGeminiKey()
                        result = null
                    }
                }) { Text(stringResource(Res.string.settings_gemini_remove)) }
            }
        }
        LiveMessage(assertive = result?.first == ResultTone.ERROR) {
            result?.let { (tone, text) -> androidx.compose.runtime.key(run) { ResultCard(tone = tone, text = text) } }
        }
        Text(stringResource(Res.string.settings_gemini_free_tier), style = MaterialTheme.typography.bodySmall)
    }

    val status = when {
        ownKey && settings.geminiKey.isBlank() -> Res.string.settings_ai_needs_key
        aiOff == AiOff.DEVICE -> Res.string.ai_off_for_device
        aiOff == AiOff.SERVER || aiOff == AiOff.NO_SERVER -> Res.string.settings_ai_server_off
        else -> Res.string.settings_ai_on
    }
    Text(stringResource(status), style = MaterialTheme.typography.bodySmall)
}

/** One choice of who answers: a radio row with a title and a hint, the whole row a 48 dp target. */
@Composable
private fun ProviderRow(selected: Boolean, enabled: Boolean, title: String, hint: String, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp, top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall)
        }
    }
}
