package com.househunt.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.househunt.app.HouseHuntApp
import com.househunt.app.R
import com.househunt.app.data.AppSettings
import com.househunt.app.data.ServerUrl
import com.househunt.app.export.AutoBackupWorker
import com.househunt.app.export.ExportProblem
import com.househunt.app.export.Saf
import com.househunt.app.export.ScreenWatch
import com.househunt.app.export.messageRes
import com.househunt.shared.export.ExportLanguages
import com.househunt.shared.sync.SyncOutcome
import com.househunt.app.i18n.AppLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


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
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium,
        modifier = modifier.semantics { heading() })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onOpenExport: () -> Unit = {}, onOpenImport: () -> Unit = {}) {
    val repo = repository()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repo.settings.settings.collectAsStateWithLifecycle(AppSettings())
    // What the user typed, kept across a rotation; null shows the saved URL. The key is plain remember on purpose:
    // the secret never goes into the saved-state Bundle.
    var typedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val url = typedUrl ?: settings.serverUrl
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    val urlFocus = remember { FocusRequester() }
    var urlError by remember { mutableStateOf<Int?>(null) }
    var radius by remember(settings.alertRadiusM) { mutableFloatStateOf(settings.alertRadiusM.toFloat()) }
    var stay by remember(settings.minStayMinutes) { mutableFloatStateOf(settings.minStayMinutes.toFloat()) }
    /** Result of the last "Save and test": houses on the server, or the error. */
    var testResult by remember { mutableStateOf<Result<Long>?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Bumped each time *Save and test* or *Sync now* finishes (round 8), so a result the same as the last one is
    // composed as a new node, and announced again.
    var statusRun by remember { mutableIntStateOf(0) }
    // The run count at which *Save and test* last rejected the address in the field, -1 for never (round 10). The
    // result stays withdrawn until the next run finishes (serverResultWithdrawn): typing clears the field's error but
    // does not bring an older result back, and *Sync now* after a rejected address shows its own result.
    var withdrawnRun by remember { mutableIntStateOf(-1) }
    val language = remember { AppLocale.current(context) }

    Column(
        // imePadding: with edge-to-edge the window no longer resizes for the keyboard, so the server fields would sit
        // under it. The scroll is full width; the settings are at most 640 dp wide and centred (UX review, whole-app
        // audit, round 8: ContentMaxWidth, as the house form, the Assistant and the web's --content-narrow pages).
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = ContentMaxWidth).fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })

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
                    if (code == null) {
                        Text(stringResource(R.string.settings_language_system), modifier = Modifier.padding(start = 12.dp))
                    } else {
                        // Tagged with its own locale, so TalkBack reads each name with the right voice.
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(localeList = LocaleList(code))) {
                                    append(ExportLanguages.nativeName(code))
                                }
                            },
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        SectionHeading(stringResource(R.string.settings_data))
        // The offline copy (Sprint 4a). Both screens work with no server and no account. Navigation rows, not
        // two buttons side by side: each title keeps its own hint (TalkBack reads them as one item), long Tamil
        // and Telugu labels wrap instead of squeezing each other, and they do not compete with "Save and test".
        NavRow(stringResource(R.string.settings_export), stringResource(R.string.settings_export_hint), onOpenExport)
        NavRow(stringResource(R.string.settings_import), stringResource(R.string.settings_import_hint), onOpenImport)

        // Weekly automatic backup (S4-07). The folder is picked once with OpenDocumentTree and the grant is made
        // persistable, so the worker can still write to it weeks later and after a reboot.
        LifecycleStartEffect(Unit) {
            ScreenWatch.settingsScreen = true
            onStopOrDispose { ScreenWatch.settingsScreen = false }
        }
        // True when the folder is being picked because the switch was turned on: then picking it also turns the
        // backup on. Picking a folder with the switch off only saves the folder — except straight after the backup
        // switched itself off because its folder had gone: choosing a folder again is then exactly the fix
        // "Choose it again" asked for, and it should put things back as they were.
        var enableAfterPick by rememberSaveable { mutableStateOf(false) }
        // The folder work runs in the app's scope, not this screen's: releasing an old grant waits on WorkManager
        // reads, and leaving Settings straight after picking must not skip it.
        val app = context.applicationContext
        val appScope = (app as HouseHuntApp).appScope
        val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            val switchedOn = enableAfterPick
            enableAfterPick = false
            if (tree == null) return@rememberLauncherForActivityResult
            // Taken now, while this activity still holds the picker's grant.
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(tree, flags) }
            val folder = tree.toString()
            appScope.launch {
                // The stored settings, never `settings` above: after process death while the picker was open, this
                // callback runs as soon as the launcher registers — before DataStore's first value, while `settings`
                // is still AppSettings() — and "keep 8" would be reset to 4, the old folder's grant never
                // released, and the "folder gone" recovery below skipped.
                val current = repo.settings.current()
                // Picking also turns the backup on when the switch asked for the folder, when it is already on
                // (changing folder), and straight after it switched itself off because its folder had gone.
                val enable = switchedOn || current.autoBackup ||
                    current.lastAutoBackupError == AutoBackupWorker.ERROR_NO_FOLDER
                repo.settings.saveAutoBackup(enable, folder, current.autoBackupKeep)
                AutoBackupWorker.schedule(app, enable)
                // Persisted grants are capped per app; do not hold on to a folder it no longer uses. After the save,
                // so a run still writing there sees the change and gives the grant back itself when it ends.
                val old = current.autoBackupFolder
                if (old != folder) AutoBackupWorker.releaseFolder(app, old)
            }
        }
        SwitchRow(
            text = stringResource(R.string.settings_auto_backup),
            hint = stringResource(R.string.settings_auto_backup_hint),
            checked = settings.autoBackup,
            horizontalPadding = 0.dp,
            onChange = { wanted ->
                if (wanted && settings.autoBackupFolder.isBlank()) {
                    enableAfterPick = true
                    pickFolder.launch(null)
                } else {
                    appScope.launch {
                        val current = repo.settings.current()
                        if (wanted) {
                            repo.settings.saveAutoBackup(true, current.autoBackupFolder, current.autoBackupKeep)
                            AutoBackupWorker.schedule(app, true)
                        } else {
                            // Off means off: the folder is forgotten and its grant given back (least privilege,
                            // and grants are capped per app), so turning it on again asks for a folder. The last
                            // error goes too — it described a setup that no longer exists. The grant is released
                            // before the runs are cancelled, see AutoBackupWorker.releaseFolder.
                            repo.settings.saveAutoBackup(false, "", current.autoBackupKeep)
                            if (current.lastAutoBackupError.isNotEmpty()) {
                                repo.settings.saveAutoBackupResult(current.lastAutoBackupAt, "")
                            }
                            AutoBackupWorker.releaseFolder(app, current.autoBackupFolder)
                            AutoBackupWorker.schedule(app, false)
                        }
                    }
                }
            },
        )
        // Where the backups go, so the user can find one when they need to restore. "Download/Doorprints" on the
        // phone's own storage; the folder's name elsewhere.
        val folderLabel by produceState<String?>(null, settings.autoBackupFolder) {
            val folder = settings.autoBackupFolder
            value = if (folder.isBlank()) null else withContext(Dispatchers.IO) {
                Saf.folderLabel(context, android.net.Uri.parse(folder))
            }
        }
        val nowFlow = remember(context) { AutoBackupWorker.observeNow(context) }
        val nowRuns by nowFlow.collectAsStateWithLifecycle(emptyList())
        val backingUp = nowRuns.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        val folderGone = settings.lastAutoBackupError == AutoBackupWorker.ERROR_NO_FOLDER

        // Everything that only applies while the backup is on, grouped in one card under its switch (Design review,
        // 2026-09-22) instead of up to seven loose items in the screen's rhythm, and easing in and out with the
        // switch (150 ms, scaled by the system animator duration scale). Only while it is on: "Saved to: …" or
        // "Keep the last 4 backups" under a switch that is off would read as if weekly backups were still being
        // made — a false sense of safety on a data-safety feature.
        AnimatedVisibility(
            visible = settings.autoBackup,
            enter = expandVertically(tween(ANIMATION_MS)) + fadeIn(tween(ANIMATION_MS)),
            exit = shrinkVertically(tween(ANIMATION_MS)) + fadeOut(tween(ANIMATION_MS)),
        ) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    folderLabel?.let {
                        Text(
                            stringResource(R.string.settings_auto_backup_folder_is, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    var keep by remember(settings.autoBackupKeep) {
                        mutableFloatStateOf(settings.autoBackupKeep.toFloat())
                    }
                    val keepText = pluralStringResource(R.plurals.settings_auto_backup_keep, keep.toInt(), keep.toInt())
                    // The visible label, for sighted users; TalkBack gets the same sentence on the slider itself.
                    Text(keepText, modifier = Modifier.clearAndSetSemantics { })
                    Slider(
                        value = keep,
                        onValueChange = { keep = it },
                        // A name and a spoken value, instead of a bare percentage read apart from the text above it.
                        modifier = Modifier.semantics {
                            contentDescription = keepText
                            stateDescription = keep.toInt().toString()
                        },
                        valueRange = 1f..8f,
                        steps = 6,
                        colors = brandSliderColors(),
                        onValueChangeFinished = {
                            scope.launch {
                                repo.settings.saveAutoBackup(true, settings.autoBackupFolder, keep.toInt())
                            }
                        },
                    )
                    // Side by side, wrapping for Tamil and Telugu at 200% font. "Back up now" proves the setup today
                    // instead of a week from now; it is the one tonal button, on the brand primaryContainer.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = { pickFolder.launch(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(
                                stringResource(
                                    if (settings.autoBackupFolder.isBlank()) R.string.settings_auto_backup_folder
                                    else R.string.settings_auto_backup_folder_change
                                )
                            )
                        }
                        FilledTonalButton(
                            onClick = { AutoBackupWorker.runNow(context) },
                            enabled = !backingUp,
                            colors = tonalPrimaryColors(),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.settings_auto_backup_now)) }
                    }
                    // The card's last row: running now, the last problem, the last backup, or not yet. Only while
                    // the backup is on, so it is not drawn twice while the card eases out.
                    if (settings.autoBackup) AutoBackupStatus(settings, backingUp)
                }
            }
        }
        // With the backup off, only the recovery is left: "The folder is no longer available. Choose it again."
        // and the button that does it. Turning the switch on already opens the folder picker, and a folder picked
        // with the switch off would be saved under a switch that stays off — except here, where picking a folder
        // turns the backup back on (see pickFolder).
        if (!settings.autoBackup && settings.lastAutoBackupError.isNotEmpty()) {
            AutoBackupStatus(settings, backingUp)
            if (folderGone) {
                OutlinedButton(onClick = { pickFolder.launch(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(
                        stringResource(
                            if (settings.autoBackupFolder.isBlank()) R.string.settings_auto_backup_folder
                            else R.string.settings_auto_backup_folder_change
                        )
                    )
                }
            }
        }

        HorizontalDivider()
        SectionHeading(stringResource(R.string.settings_hunt))
        // Each slider is named by its sentence and says its value in metres or minutes (WCAG 4.1.2), as the keep-backups
        // slider does; the visible sentence is hidden from TalkBack so it is not read twice.
        val radiusText = stringResource(R.string.settings_alert_radius, radius.toInt())
        Text(radiusText, modifier = Modifier.clearAndSetSemantics { })
        Slider(radius, { radius = it }, valueRange = 15f..100f, steps = 16, colors = brandSliderColors(),
            modifier = Modifier.semantics {
                contentDescription = radiusText
                stateDescription = radius.toInt().toString()
            },
            onValueChangeFinished = { scope.launch { repo.settings.saveTracking(radius.toInt(), stay.toInt()) } })
        val stayText = stringResource(R.string.settings_min_stay, stay.toInt())
        Text(stayText, modifier = Modifier.clearAndSetSemantics { })
        Slider(stay, { stay = it }, valueRange = 2f..15f, steps = 12, colors = brandSliderColors(),
            modifier = Modifier.semantics {
                contentDescription = stayText
                stateDescription = stay.toInt().toString()
            },
            onValueChangeFinished = { scope.launch { repo.settings.saveTracking(radius.toInt(), stay.toInt()) } })
        Text(stringResource(R.string.settings_gps_note), style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        SectionHeading(stringResource(R.string.settings_server))
        Text(stringResource(R.string.settings_server_intro), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            url, { typedUrl = it; urlError = null },
            label = { Text(stringResource(R.string.settings_url)) },
            isError = urlError != null,
            // The supporting text is always there, so its node exists before an error arrives; while it holds the
            // error it is an assertive live region, and focus moves to the field (3.3.1), so the message is heard.
            supportingText = {
                Text(
                    stringResource(urlError ?: R.string.settings_url_hint),
                    modifier = if (urlError != null) {
                        Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                    } else {
                        Modifier
                    },
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(urlFocus),
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
            // Show / Hide, as on the web (docs/05 3.3.8: the key can be pasted and checked).
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (showKey) VisibilityOffIcon else VisibilityIcon,
                        contentDescription = stringResource(if (showKey) R.string.settings_hide_key else R.string.settings_show_key),
                    )
                }
            },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        // FlowRow: in Telugu at 200% font "సేవ్ చేసి పరీక్షించండి" and "ఇప్పుడు సింక్ చేయండి" do not fit side by side,
        // and a plain Row squeezed the second button into a sliver. Here it wraps to its own line.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                when (val checked = ServerUrl.check(url)) {
                    is ServerUrl.Result.Ok -> scope.launch {
                        busy = true
                        repo.settings.saveServer(checked.url, key)
                        key = ""
                        testResult = repo.testConnection().map { it.houses }
                        repo.refreshAiStatus()
                        statusRun++
                        busy = false
                    }
                    // An address that is not checked has no result (round 9): the last one ("Connected: 12 houses")
                    // would sit under a field that shows an error. Withdrawn until the next run ends (round 10).
                    ServerUrl.Result.NotHttps -> {
                        urlError = R.string.settings_url_https
                        testResult = null
                        withdrawnRun = statusRun
                        runCatching { urlFocus.requestFocus() }
                    }
                    ServerUrl.Result.Invalid, ServerUrl.Result.Empty -> {
                        urlError = R.string.settings_url_invalid
                        testResult = null
                        withdrawnRun = statusRun
                        runCatching { urlFocus.requestFocus() }
                    }
                }
            }) { Text(stringResource(R.string.settings_save_test)) }
            OutlinedButton(enabled = !busy && settings.serverConfigured, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                scope.launch {
                    busy = true
                    val outcome = runCatching { repo.sync() }.getOrElse { SyncOutcome.fromError(it) }
                    repo.settings.saveSyncResult(outcome)
                    testResult = null
                    statusRun++
                    busy = false
                }
            }) { Text(stringResource(R.string.settings_sync_now)) }
        }
        val lastTest = testResult
        val statusText = lastTest?.fold(
            { stringResource(R.string.settings_connected, it) },
            { stringResource(R.string.settings_connect_failed, SyncOutcome.fromError(it).text()) },
        ) ?: settings.lastSync?.text()
        val statusTone = serverStatusTone(lastTest?.isSuccess, settings.lastSync?.kind)
        // Always composed (UX review, whole-app audit, round 7): a live region that appears together with its text is
        // not always announced, so "Connected…" or the failure after *Save and test* could go unsaid. Round 8: the
        // result is a card in its outcome's tone (a failure in the error colours with the warning sign, a success
        // with the tick; serverStatusTone), and a failure is assertive, as on the Map and the export screens. It is
        // keyed on the run, so a result the same as the last one is read again.
        // Round 9: while a run is busy the card keeps its slot with the progress bar along its foot, instead of
        // making way for a 4 dp bar, so Last sync, *Photos on Wi-Fi only* and Privacy no longer jump up by the card's
        // height and back a second later; the card is the same node until the run ends, so nothing is announced until
        // the new result. Round 10 (RefreshableResultCard, shared with the Assistant): only its icon and border are
        // dimmed, the text stays at full contrast for the 10-15 s a sync can take, and its state is "Updating…" for
        // TalkBack. With no result yet, the bar alone. No card for an address that *Save and test* rejected, until
        // the next run ends (withdrawnRun): the field's error, not the run, decided this in round 9, so typing brought
        // an older result back and *Sync now* after a rejected address ended with nothing shown.
        val slot = serverStatusSlot(
            hasResult = statusText != null && statusTone != null,
            busy = busy,
            resultWithdrawn = serverResultWithdrawn(withdrawnRun, statusRun),
        )
        val updatingText = stringResource(R.string.settings_status_updating)
        LiveMessage(assertive = statusTone == ResultTone.ERROR && !busy) {
            when (slot) {
                ServerStatusSlot.CARD, ServerStatusSlot.CARD_BUSY -> if (statusText != null && statusTone != null) {
                    // Qualified: `key` is also the API key field's state here.
                    androidx.compose.runtime.key(statusRun) {
                        RefreshableResultCard(
                            tone = statusTone,
                            text = statusText,
                            busyText = if (slot == ServerStatusSlot.CARD_BUSY) updatingText else null,
                        )
                    }
                }
                ServerStatusSlot.BAR -> ProgressBar()
                ServerStatusSlot.NONE -> Unit
            }
        }
        if (settings.lastSyncAt > 0) {
            Text(stringResource(R.string.settings_last_sync, settings.lastSyncAt.dateText()),
                style = MaterialTheme.typography.bodySmall)
        }
        SwitchRow(
            text = stringResource(R.string.settings_photos_wifi),
            hint = stringResource(R.string.settings_photos_wifi_hint),
            checked = settings.photosOnWifiOnly,
            // This column is already padded; the shared row adds no inset of its own here.
            horizontalPadding = 0.dp,
            onChange = { scope.launch { repo.settings.savePhotosOnWifiOnly(it) } },
        )

        HorizontalDivider()
        SectionHeading(stringResource(R.string.settings_privacy))
        Text(stringResource(R.string.settings_privacy_note), style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        SectionHeading(stringResource(R.string.settings_about))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodySmall)
        // The version, which support needs first.
        val version = remember { appVersion(context) }
        if (version != null) Text(stringResource(R.string.settings_version, version), style = MaterialTheme.typography.bodySmall)
    }
}

/** One status line for the automatic backup: running now, the last problem, the last backup, or not yet. */
@Composable
private fun AutoBackupStatus(settings: AppSettings, backingUp: Boolean) {
    when {
        backingUp -> {
            ProgressBar()
            Text(
                stringResource(R.string.auto_backup_working),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        // A failure is the screen's one error style (round 9): the red card with the warning sign, as the server
        // result above, the Map, the house form and Export/Import, not red text alone. The card sets no live region
        // of its own, so the box around it is the assertive one, as the house list's undo card does.
        settings.lastAutoBackupError.isNotEmpty() -> Box(
            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
        ) {
            ResultCard(
                tone = ResultTone.ERROR,
                text = if (settings.lastAutoBackupError == AutoBackupWorker.ERROR_NO_FOLDER) {
                    stringResource(R.string.settings_auto_backup_no_folder)
                } else {
                    // A stable code, shown as a translated reason (an English message saved by an older build reads
                    // as the generic one); see ExportProblem.
                    stringResource(
                        R.string.settings_auto_backup_failed,
                        stringResource(ExportProblem.fromCode(settings.lastAutoBackupError).messageRes()),
                    )
                },
            )
        }
        // "Last backup", not "Last automatic backup": a Back up now run updates it too, and this polite live region
        // changing is the "done" for that tap (UX review, 2026-09-22).
        settings.lastAutoBackupAt > 0 -> Text(
            stringResource(R.string.settings_auto_backup_last, settings.lastAutoBackupAt.dateText()),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        settings.autoBackup -> Text(
            stringResource(R.string.settings_auto_backup_never),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Unit
    }
}

/**
 * A row that opens a sub-screen (the standard M3 pattern): title, its own hint as supporting text, and a chevron.
 * One 56 dp target with a button role, so TalkBack reads title and hint together as one item.
 */
@Composable
private fun NavRow(title: String, hint: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The installed version name ("0.1.0"), or null if the package manager cannot say. */
private fun appVersion(context: Context): String? = runCatching {
    val pm = context.packageManager
    val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
        pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, 0)
    }
    info.versionName
}.getOrNull()

/** Material's "visibility" glyph (an eye), built from its path: the core icon set has none. */
private val VisibilityIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Visibility", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(
                "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5z" +
                    "M12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z" +
                    "M12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z"
            ),
            fill = SolidColor(Color.Black),
        ).build()
}

/** Material's "visibility off" glyph (an eye struck through). */
private val VisibilityOffIcon: ImageVector by lazy {
    ImageVector.Builder(name = "VisibilityOff", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(
                "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.43,-4.75 " +
                    "-1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16C10.74,7.13 11.35,7 12,7z" +
                    "M2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.3 " +
                    "4.38,-0.84l0.42,0.42L19.73,22 21,20.73 3.27,3 2,4.27z" +
                    "M7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3 0.22,0 0.44,-0.03 " +
                    "0.65,-0.08l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 0.53,-2.2z" +
                    "M11.84,9.02l3.15,3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z"
            ),
            fill = SolidColor(Color.Black),
        ).build()
}
