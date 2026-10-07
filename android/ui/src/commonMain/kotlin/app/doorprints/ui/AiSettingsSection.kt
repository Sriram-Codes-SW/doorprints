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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.doorprints.data.AiOff
import app.doorprints.data.AiProviderChoice
import app.doorprints.data.AppSettings
import app.doorprints.shared.ai.AiKind
import app.doorprints.shared.ai.BaseUrlReason
import app.doorprints.shared.api.ApiException
import app.doorprints.ui.res.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Where a free Gemini key is made. */
const val AI_STUDIO_KEYS_URL = "https://aistudio.google.com/apikey"

/**
 * Settings → *AI features* (docs/03 §12.1, §13.1, §13.2, ADR-26, ADR-35): this phone's switch, off until turned on; then
 * who answers, **Use my server** (when one is connected) or **Use my own AI on this phone**. For the own AI: the **AI
 * service** (Gemini, as before, Anthropic, or an OpenAI-compatible one: OpenAI, OpenRouter, Groq, Ollama, LM Studio, Custom), its
 * **Base URL** (shown by the presets, typed for Custom), **Model**, **API key** (optional for a service on this device),
 * *Save*, *Test* and *Remove key*, and the sentence that names the host the text goes to. The key is kept like the
 * server key (Android Keystore, iOS Keychain) and only ever sent to the service it was saved for; the field starts
 * empty and a saved key is shown by its last four characters (S4b-BL-150). The rules are in [AiProviderForm].
 */
@Composable
fun AiSettingsSection(settings: AppSettings, aiOff: AiOff?) {
    val repo = LocalAppServices.current.repository
    val scope = rememberCoroutineScope()
    val ownKey = settings.aiProvider == AiProviderChoice.DEVICE || !settings.serverConfigured

    SectionHeading(stringResource(Res.string.settings_ai_heading))
    SwitchRow(
        text = stringResource(Res.string.settings_ai_switch),
        hint = stringResource(Res.string.settings_ai_hint),
        checked = settings.aiFeatures,
        horizontalPadding = 0.dp,
        modifier = Modifier.tourTarget(TourTargets.SETTINGS_AI),
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

    if (ownKey) OwnAiForm(settings)

    val emulatorHost = LocalPlatformFeatures.current.emulatorHost
    val status = when {
        ownKey && !AiProviderForm.usable(settings.aiProviderConfig, settings.geminiKey, emulatorHost) -> Res.string.settings_ai_needs_key
        aiOff == AiOff.DEVICE -> Res.string.ai_off_for_device
        aiOff == AiOff.SERVER || aiOff == AiOff.NO_SERVER -> Res.string.settings_ai_server_off
        else -> Res.string.settings_ai_on
    }
    Text(stringResource(status), style = MaterialTheme.typography.bodySmall)
}

/**
 * The own-AI fields. Plain `remember` on purpose: the form holds the typed key, which never goes into the saved-state
 * Bundle. It is built when this part first shows (the settings are loaded by then) from what is saved.
 */
@Composable
private fun OwnAiForm(settings: AppSettings) {
    val repo = LocalAppServices.current.repository
    val platform = LocalPlatformServices.current
    val emulatorHost = LocalPlatformFeatures.current.emulatorHost
    val scope = rememberCoroutineScope()
    val saved = settings.aiProviderConfig
    val hasKey = settings.geminiKey.isNotBlank()
    var form by remember { mutableStateOf(AiProviderForm.initial(saved, emulatorHost)) }
    var showKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<ResultTone, String>?>(null) }
    var run by remember { mutableIntStateOf(0) }
    // What the last Save or Test found wrong, shown under the fields until they are edited.
    var urlReason by remember { mutableStateOf<BaseUrlReason?>(null) }
    var modelMissing by remember { mutableStateOf(false) }
    var keyMissing by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf<AiField?>(null) }
    var focusRun by remember { mutableIntStateOf(0) }
    val urlFocus = remember { FocusRequester() }
    val modelFocus = remember { FocusRequester() }
    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(focusRun) {
        when (focus) {
            AiField.BASE_URL -> urlFocus.requestFocus()
            AiField.MODEL -> modelFocus.requestFocus()
            AiField.KEY -> keyFocus.requestFocus()
            null -> Unit
        }
    }

    val ok = stringResource(Res.string.settings_gemini_ok)
    val rejected = stringResource(Res.string.settings_gemini_rejected)
    val savedText = stringResource(Res.string.settings_ai_saved)
    val okText = stringResource(Res.string.settings_ai_test_ok)
    val okLocalText = stringResource(Res.string.settings_ai_test_ok_local)
    val failureText = aiFailureText()
    val keySavedHere = form.keySavedHere(saved, hasKey)
    val host = form.host
    val urlError = urlReason?.let { stringResource(urlReasonText(it)) }
    val modelError = if (modelMissing) stringResource(Res.string.settings_ai_model_required) else null
    val keyError = if (keyMissing) stringResource(Res.string.settings_ai_key_required) else null

    fun clearErrors() {
        urlReason = null
        modelMissing = false
        keyMissing = false
    }

    fun show(tone: ResultTone, text: String) {
        result = tone to text
        run++
    }

    ServicePicker(form.service) { next ->
        form = form.choose(next, saved)
        showKey = false
        clearErrors()
        result = null
    }

    if (form.isGemini) {
        KeyField(
            value = form.key,
            onChange = { form = form.copy(key = it); result = null },
            label = stringResource(Res.string.settings_gemini_key),
            hint = if (keySavedHere && settings.geminiKeyHint.isNotEmpty()) stringResource(Res.string.settings_gemini_key_saved, settings.geminiKeyHint)
            else stringResource(Res.string.settings_gemini_key_hint),
            showKey = showKey, onShowKey = { showKey = !showKey },
            error = null, modifier = Modifier,
        )
        TextButton(onClick = { platform.openUrl(AI_STUDIO_KEYS_URL) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(Res.string.settings_gemini_get_key))
        }
    } else {
        OutlinedTextField(
            form.baseUrl, { form = form.copy(baseUrl = it); urlReason = null; result = null },
            label = { Text(stringResource(Res.string.settings_ai_base_url)) },
            supportingText = { Text(stringResource(Res.string.settings_ai_base_url_hint)) },
            readOnly = !form.urlEditable,
            isError = urlError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().focusRequester(urlFocus).fieldError(urlError),
        )
        FieldError(urlError)
        OutlinedTextField(
            form.model, { form = form.copy(model = it); modelMissing = false; result = null },
            label = { Text(stringResource(Res.string.settings_ai_model)) },
            placeholder = if (form.service.modelExample.isNotEmpty()) ({ Text(form.service.modelExample) }) else null,
            supportingText = { Text(stringResource(Res.string.settings_ai_model_hint)) },
            isError = modelError != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(modelFocus).fieldError(modelError),
        )
        FieldError(modelError)
        KeyField(
            value = form.key,
            onChange = { form = form.copy(key = it); keyMissing = false; result = null },
            label = stringResource(Res.string.settings_ai_key),
            hint = if (keySavedHere && settings.geminiKeyHint.isNotEmpty()) stringResource(Res.string.settings_gemini_key_saved, settings.geminiKeyHint)
            else stringResource(if (form.keyOptional) Res.string.settings_ai_key_optional else Res.string.settings_ai_key_hint),
            showKey = showKey, onShowKey = { showKey = !showKey },
            error = keyError,
            modifier = Modifier.focusRequester(keyFocus),
        )
        FieldError(keyError)
        if (form.service.keyPage.isNotEmpty()) {
            TextButton(onClick = { platform.openUrl(form.service.keyPage) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.settings_ai_get_key))
            }
        }
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (form.isGemini) {
            // Today's behaviour: ask Google first, so a mistyped key is not saved; Gemini keeps no address or model.
            Button(enabled = !busy && form.key.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp), onClick = {
                scope.launch {
                    busy = true
                    val key = form.key
                    val tested = repo.testGeminiKey(key)
                    if (tested.isSuccess) {
                        repo.saveGeminiKey(key)
                        form = form.copy(key = "")
                        show(ResultTone.SUCCESS, ok)
                    } else {
                        val e = tested.exceptionOrNull()!!
                        show(ResultTone.ERROR, if (e is ApiException && e.kind == ApiException.Kind.AI_KEY_REJECTED) rejected else failureText(AiFailure.of(e, "")))
                    }
                    busy = false
                }
            }) { Text(stringResource(Res.string.settings_gemini_save)) }
            if (keySavedHere) {
                OutlinedButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                    scope.launch {
                        busy = true
                        val e = repo.testGeminiKey(settings.geminiKey).exceptionOrNull()
                        when {
                            e == null -> show(ResultTone.SUCCESS, ok)
                            e is ApiException && e.kind == ApiException.Kind.AI_KEY_REJECTED -> show(ResultTone.ERROR, rejected)
                            else -> show(ResultTone.ERROR, failureText(AiFailure.of(e, "")))
                        }
                        busy = false
                    }
                }) { Text(stringResource(Res.string.settings_gemini_test)) }
            }
        } else {
            // What is on the screen, or the first thing wrong with it (then focused, and read out by its live region).
            fun checked() = form.validate(saved, hasKey).also {
                urlReason = it.urlReason
                modelMissing = it.modelMissing
                keyMissing = it.keyMissing
                if (it.config == null) {
                    focus = it.first
                    focusRun++
                }
            }.config
            Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                val config = checked() ?: return@Button
                scope.launch {
                    busy = true
                    val typed = form.key.trim()
                    // A saved key follows its own service only: another service or address saves what was typed, or none.
                    val key = if (typed.isNotEmpty()) typed else if (keySavedHere) settings.geminiKey else ""
                    val failed = runCatching { repo.saveAiProviderConfig(config, key) }.exceptionOrNull()
                    if (failed == null) {
                        form = form.copy(key = "")
                        show(ResultTone.SUCCESS, savedText)
                    } else {
                        show(ResultTone.ERROR, failureText(AiFailure.of(failed, host)))
                    }
                    busy = false
                }
            }) { Text(stringResource(Res.string.settings_ai_save)) }
            OutlinedButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                val config = checked() ?: return@OutlinedButton
                scope.launch {
                    busy = true
                    val key = form.keyForCall(saved, settings.geminiKey)
                    val e = repo.testAiProvider(config, key).exceptionOrNull()
                    if (e == null) {
                        show(ResultTone.SUCCESS, formatPositional(if (key.isEmpty()) okLocalText else okText, host))
                    } else {
                        show(ResultTone.ERROR, failureText(AiFailure.of(e, host)))
                    }
                    busy = false
                }
            }) { Text(stringResource(Res.string.settings_ai_test)) }
        }
        if (form.savedHere(saved, hasKey)) {
            OutlinedButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                scope.launch {
                    repo.removeGeminiKey()
                    form = form.copy(baseUrl = form.service.preset?.baseUrl.orEmpty(), model = "", key = "")
                    clearErrors()
                    result = null
                }
            }) { Text(stringResource(Res.string.settings_gemini_remove)) }
        }
    }
    LiveMessage(assertive = result?.first == ResultTone.ERROR) {
        result?.let { (tone, text) -> androidx.compose.runtime.key(run) { ResultCard(tone = tone, text = text) } }
    }
    if (form.isGemini) Text(stringResource(Res.string.settings_gemini_free_tier), style = MaterialTheme.typography.bodySmall)
    // Before anything is sent, where it goes (T-I43): the host of what is on the screen.
    if (host.isNotEmpty()) {
        Text(
            stringResource(Res.string.ai_disclosure_own_key_host, host),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun urlReasonText(reason: BaseUrlReason): StringResource = when (reason) {
    BaseUrlReason.EMPTY -> Res.string.settings_ai_base_url_invalid_empty
    BaseUrlReason.NOT_AN_URL -> Res.string.settings_ai_base_url_invalid_not_an_url
    BaseUrlReason.SCHEME -> Res.string.settings_ai_base_url_invalid_scheme
    BaseUrlReason.USERINFO -> Res.string.settings_ai_base_url_invalid_userinfo
    BaseUrlReason.QUERY -> Res.string.settings_ai_base_url_invalid_query
    BaseUrlReason.FRAGMENT -> Res.string.settings_ai_base_url_invalid_fragment
    BaseUrlReason.ENDPOINT -> Res.string.settings_ai_base_url_invalid_endpoint
    BaseUrlReason.INSECURE_HOST -> Res.string.settings_ai_base_url_invalid_insecure_host
}

private fun serviceLabel(service: AiService): StringResource = when (service) {
    AiService.GEMINI -> Res.string.settings_ai_service_gemini
    AiService.OPENAI -> Res.string.settings_ai_service_openai
    AiService.OPENROUTER -> Res.string.settings_ai_service_openrouter
    AiService.GROQ -> Res.string.settings_ai_service_groq
    AiService.OLLAMA -> Res.string.settings_ai_service_ollama
    AiService.LM_STUDIO -> Res.string.settings_ai_service_lmstudio
    AiService.ANTHROPIC -> Res.string.settings_ai_service_anthropic
    AiService.CUSTOM -> Res.string.settings_ai_service_custom
}

/** Names a field's problem for TalkBack and VoiceOver when the field is focused. */
private fun Modifier.fieldError(text: String?): Modifier =
    if (text == null) this else this.semantics { error(text) }

/**
 * A field's problem, in a live region that is on screen before the problem comes (as [LiveMessage] explains), so
 * TalkBack and VoiceOver read it out when Save or Test finds it.
 */
@Composable
private fun FieldError(text: String?) {
    LiveMessage {
        if (text != null) Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

/** The API key field, masked, with a 48 dp show and hide button (as the Gemini key field always had). */
@Composable
private fun KeyField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    hint: String,
    showKey: Boolean,
    onShowKey: () -> Unit,
    error: String?,
    modifier: Modifier,
) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
        supportingText = { Text(hint) },
        trailingIcon = {
            IconButton(onClick = onShowKey, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (showKey) VisibilityOffIcon else VisibilityIcon,
                    contentDescription = stringResource(if (showKey) Res.string.settings_hide_key else Res.string.settings_show_key),
                )
            }
        },
        isError = error != null,
        singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = modifier.fillMaxWidth().fieldError(error),
    )
}

/** *AI service*: the chosen one on a 48 dp button that opens the list (the house form's Broker menu works the same). */
@Composable
private fun ServicePicker(current: AiService, onPick: (AiService) -> Unit) {
    val label = stringResource(Res.string.settings_ai_service)
    val currentText = stringResource(serviceLabel(current))
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = label
                stateDescription = currentText
                role = Role.DropdownList
            },
        ) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(currentText, maxLines = 2)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.selectableGroup()) {
            AiService.entries.forEach { option ->
                val isCurrent = option == current
                DropdownMenuItem(
                    text = { Text(stringResource(serviceLabel(option))) },
                    onClick = {
                        open = false
                        if (!isCurrent) onPick(option)
                    },
                    leadingIcon = { RadioButton(selected = isCurrent, onClick = null) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        selected = isCurrent
                        role = Role.RadioButton
                    },
                )
            }
        }
    }
}

/**
 * The sentence before text is sent: where it goes. With the person's own AI it names the host (*sent from this phone
 * straight to api.groq.com with your own key*); with the server it names the provider set up on the server. Nothing
 * until the settings are loaded, and nothing while the own AI has no valid address yet (then AI is off anyway).
 */
@Composable
fun AiDisclosure(style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val settings = LocalAppServices.current.repository.settings.settings.collectAsState(null).value ?: return
    val host = AiProviderForm.disclosureHost(settings, LocalPlatformFeatures.current.emulatorHost)
    when {
        host == null -> Text(stringResource(Res.string.ai_disclosure), style = style, color = color, modifier = modifier)
        host.isNotEmpty() -> Text(stringResource(Res.string.ai_disclosure_own_key_host, host), style = style, color = color, modifier = modifier)
    }
}

/**
 * The host of the person's own OpenAI-compatible or Anthropic service, for the words of a failure that names it; empty with Gemini
 * (those words name Google) or when the server answers.
 */
@Composable
fun ownAiHost(): String {
    val settings = LocalAppServices.current.repository.settings.settings.collectAsState(null).value ?: return ""
    if (settings.aiProviderConfig.kind == AiKind.GEMINI) return ""
    return AiProviderForm.disclosureHost(settings, LocalPlatformFeatures.current.emulatorHost).orEmpty()
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
