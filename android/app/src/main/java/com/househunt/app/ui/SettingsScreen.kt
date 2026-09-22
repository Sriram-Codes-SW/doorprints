package com.househunt.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.househunt.app.R
import com.househunt.app.data.AppSettings
import com.househunt.app.data.ServerUrl
import com.househunt.shared.sync.SyncOutcome
import com.househunt.app.i18n.AppLocale
import kotlinx.coroutines.launch

/** Native names are shown untranslated, so each language is recognisable whatever the current UI language. */
private val languageNames = mapOf("en" to "English", "hi" to "हिन्दी", "ta" to "தமிழ்", "te" to "తెలుగు")

@Composable
fun SyncOutcome.text(): String = when (kind) {
    SyncOutcome.Kind.OK -> if (photosWaiting > 0) {
        stringResource(R.string.sync_ok_photos_waiting, pushed, pulled, photosWaiting)
    } else {
        stringResource(R.string.sync_ok, pushed, pulled)
    }
    SyncOutcome.Kind.NOT_CONFIGURED -> stringResource(R.string.sync_not_configured)
    SyncOutcome.Kind.NETWORK -> stringResource(R.string.sync_err_network)
    SyncOutcome.Kind.AUTH -> stringResource(R.string.sync_err_auth)
    SyncOutcome.Kind.CAPTIVE_PORTAL -> stringResource(R.string.sync_err_captive)
    SyncOutcome.Kind.RATE_LIMITED -> stringResource(R.string.sync_err_rate)
    SyncOutcome.Kind.SERVER -> stringResource(R.string.sync_err_server, httpCode)
    SyncOutcome.Kind.UNKNOWN -> stringResource(R.string.sync_err_unknown)
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun SectionHeading(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() })
}

@Composable
fun SettingsScreen() {
    val repo = repository()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repo.settings.settings.collectAsStateWithLifecycle(AppSettings())
    var url by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var key by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<Int?>(null) }
    var radius by remember(settings.alertRadiusM) { mutableFloatStateOf(settings.alertRadiusM.toFloat()) }
    var stay by remember(settings.minStayMinutes) { mutableFloatStateOf(settings.minStayMinutes.toFloat()) }
    /** Result of the last "Save and test": houses on the server, or the error. */
    var testResult by remember { mutableStateOf<Result<Long>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val language = remember { AppLocale.current(context) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })

        SectionHeading(stringResource(R.string.settings_server))
        Text(stringResource(R.string.settings_server_intro), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            url, { url = it; urlError = null },
            label = { Text(stringResource(R.string.settings_url)) },
            isError = urlError != null,
            supportingText = { Text(stringResource(urlError ?: R.string.settings_url_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            key, { key = it },
            label = { Text(stringResource(R.string.settings_key)) },
            supportingText = {
                Text(
                    if (settings.apiKeyHint.isNotEmpty()) stringResource(R.string.settings_key_saved, settings.apiKeyHint)
                    else stringResource(R.string.settings_key_hint)
                )
            },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                when (val checked = ServerUrl.check(url)) {
                    is ServerUrl.Result.Ok -> scope.launch {
                        busy = true
                        repo.settings.saveServer(checked.url, key)
                        key = ""
                        testResult = repo.testConnection().map { it.houses }
                        repo.refreshAiStatus()
                        busy = false
                    }
                    ServerUrl.Result.NotHttps -> urlError = R.string.settings_url_https
                    ServerUrl.Result.Invalid, ServerUrl.Result.Empty -> urlError = R.string.settings_url_invalid
                }
            }) { Text(stringResource(R.string.settings_save_test)) }
            OutlinedButton(enabled = !busy && settings.serverConfigured, onClick = {
                scope.launch {
                    busy = true
                    val outcome = runCatching { repo.sync() }.getOrElse { SyncOutcome.fromError(it) }
                    repo.settings.saveSyncResult(outcome)
                    testResult = null
                    busy = false
                }
            }) { Text(stringResource(R.string.settings_sync_now)) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val statusText = testResult?.fold(
            { stringResource(R.string.settings_connected, it) },
            { stringResource(R.string.settings_connect_failed, SyncOutcome.fromError(it).text()) },
        ) ?: settings.lastSync?.text()
        statusText?.let {
            Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (settings.lastSyncAt > 0) {
            Text(stringResource(R.string.settings_last_sync, settings.lastSyncAt.dateText()),
                style = MaterialTheme.typography.bodySmall)
        }
        SwitchRow(
            text = stringResource(R.string.settings_photos_wifi),
            hint = stringResource(R.string.settings_photos_wifi_hint),
            checked = settings.photosOnWifiOnly,
            onChange = { scope.launch { repo.settings.savePhotosOnWifiOnly(it) } },
        )

        HorizontalDivider()
        SectionHeading(stringResource(R.string.settings_language))
        Column(Modifier.selectableGroup()) {
            val options = listOf<String?>(null) + AppLocale.SUPPORTED
            options.forEach { code ->
                val selected = language == code
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = {
                            if (!selected) context.findActivity()?.let { AppLocale.set(it, code) }
                        }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(
                        if (code == null) stringResource(R.string.settings_language_system) else languageNames.getValue(code),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }

        HorizontalDivider()
        SectionHeading(stringResource(R.string.settings_hunt))
        Text(stringResource(R.string.settings_alert_radius, radius.toInt()))
        Slider(radius, { radius = it }, valueRange = 15f..100f, steps = 16,
            onValueChangeFinished = { scope.launch { repo.settings.saveTracking(radius.toInt(), stay.toInt()) } })
        Text(stringResource(R.string.settings_min_stay, stay.toInt()))
        Slider(stay, { stay = it }, valueRange = 2f..15f, steps = 12,
            onValueChangeFinished = { scope.launch { repo.settings.saveTracking(radius.toInt(), stay.toInt()) } })
        Text(stringResource(R.string.settings_gps_note), style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        SectionHeading(stringResource(R.string.settings_privacy))
        Text(stringResource(R.string.settings_privacy_note), style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        SectionHeading(stringResource(R.string.settings_about))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodySmall)
    }
}

/** A whole-row switch: one 48 dp target, and TalkBack reads the label with the on/off state. */
@Composable
private fun SwitchRow(text: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
