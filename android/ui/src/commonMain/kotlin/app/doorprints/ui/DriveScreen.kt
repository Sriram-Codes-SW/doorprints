package app.doorprints.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.drive.connect.BackupRow
import app.doorprints.drive.connect.DeleteDialog
import app.doorprints.drive.connect.DeletePhase
import app.doorprints.drive.connect.DriveBusy
import app.doorprints.drive.connect.DriveDeleteChoice
import app.doorprints.drive.connect.DriveEvent
import app.doorprints.drive.connect.DriveMessage
import app.doorprints.drive.connect.DriveSettingsController
import app.doorprints.drive.connect.DriveStage
import app.doorprints.drive.connect.DriveUiState
import app.doorprints.drive.connect.RecoveryKeyStep
import app.doorprints.drive.connect.RecoveryStatus
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.back
import app.doorprints.ui.res.drive_account
import app.doorprints.ui.res.drive_auto
import app.doorprints.ui.res.drive_auto_hint
import app.doorprints.ui.res.drive_back_up_now
import app.doorprints.ui.res.drive_backing_up
import app.doorprints.ui.res.drive_backup_newest
import app.doorprints.ui.res.drive_backup_row
import app.doorprints.ui.res.drive_backups_empty
import app.doorprints.ui.res.drive_backups_title
import app.doorprints.ui.res.drive_connect
import app.doorprints.ui.res.drive_connecting
import app.doorprints.ui.res.drive_delete_all
import app.doorprints.ui.res.drive_delete_backup
import app.doorprints.ui.res.drive_delete_backup_for
import app.doorprints.ui.res.drive_delete_everything
import app.doorprints.ui.res.drive_delete_menu
import app.doorprints.ui.res.drive_delete_older
import app.doorprints.ui.res.drive_disconnect
import app.doorprints.ui.res.drive_disconnect_hint
import app.doorprints.ui.res.drive_dismiss
import app.doorprints.ui.res.drive_dlg_body
import app.doorprints.ui.res.drive_dlg_cancel
import app.doorprints.ui.res.drive_dlg_delete
import app.doorprints.ui.res.drive_dlg_delete_wait
import app.doorprints.ui.res.drive_dlg_everything_extra
import app.doorprints.ui.res.drive_dlg_foreign
import app.doorprints.ui.res.drive_dlg_save_copy
import app.doorprints.ui.res.drive_dlg_tick
import app.doorprints.ui.res.drive_dlg_title_all
import app.doorprints.ui.res.drive_dlg_title_everything
import app.doorprints.ui.res.drive_dlg_title_older
import app.doorprints.ui.res.drive_dlg_title_one
import app.doorprints.ui.res.drive_dlg_working
import app.doorprints.ui.res.drive_enrol_body
import app.doorprints.ui.res.drive_enrol_no_recovery
import app.doorprints.ui.res.drive_enrol_title
import app.doorprints.ui.res.drive_first_body
import app.doorprints.ui.res.drive_first_make
import app.doorprints.ui.res.drive_first_skip
import app.doorprints.ui.res.drive_first_title
import app.doorprints.ui.res.drive_gone_body
import app.doorprints.ui.res.drive_gone_start
import app.doorprints.ui.res.drive_gone_tick
import app.doorprints.ui.res.drive_gone_title
import app.doorprints.ui.res.drive_import_row
import app.doorprints.ui.res.drive_import_row_for
import app.doorprints.ui.res.drive_intro
import app.doorprints.ui.res.drive_key_body
import app.doorprints.ui.res.drive_key_check
import app.doorprints.ui.res.drive_key_copied
import app.doorprints.ui.res.drive_key_copy
import app.doorprints.ui.res.drive_key_group
import app.doorprints.ui.res.drive_key_label
import app.doorprints.ui.res.drive_key_saved
import app.doorprints.ui.res.drive_key_title
import app.doorprints.ui.res.drive_last_backup
import app.doorprints.ui.res.drive_last_none
import app.doorprints.ui.res.drive_needs_lock
import app.doorprints.ui.res.drive_open_lock_settings
import app.doorprints.ui.res.drive_paused_title
import app.doorprints.ui.res.drive_photos_mobile
import app.doorprints.ui.res.drive_photos_mobile_soon
import app.doorprints.ui.res.drive_photos_wifi
import app.doorprints.ui.res.drive_recovery_body
import app.doorprints.ui.res.drive_recovery_field
import app.doorprints.ui.res.drive_recovery_open
import app.doorprints.ui.res.drive_recovery_status_none
import app.doorprints.ui.res.drive_recovery_status_saved
import app.doorprints.ui.res.drive_recovery_status_unconfirmed
import app.doorprints.ui.res.drive_recovery_title
import app.doorprints.ui.res.drive_resume_note
import app.doorprints.ui.res.drive_retry
import app.doorprints.ui.res.drive_shrink_ok
import app.doorprints.ui.res.drive_skip_confirm
import app.doorprints.ui.res.drive_skip_tick
import app.doorprints.ui.res.drive_skip_warn
import app.doorprints.ui.res.drive_storage
import app.doorprints.ui.res.drive_title
import app.doorprints.ui.res.drive_working
import org.jetbrains.compose.resources.stringResource
import app.doorprints.drive.delete.DeletionLevel

/**
 * Settings > Google Drive (S4b-BL-117; docs/15 §5.7, §9.4, §3.2). It draws [DriveUiState] and calls the state holder
 * ([DriveSettingsController]); every decision, refusal and sentence is decided there and tested there. Strings are the
 * `drive_*` keys in the four languages (Hindi, Tamil and Telugu under review). Not shown at all without an OAuth client
 * id ([DriveServices.controller] is null).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(onBack: () -> Unit, onOpenExport: () -> Unit, onOpenImport: (file: String) -> Unit) {
    val services = LocalAppServices.current.drive
    val controller = services.controller
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.drive_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back)) }
                },
            )
        },
    ) { padding ->
        if (controller == null) return@Scaffold
        val state by controller.state.collectAsStateWithLifecycle()
        LaunchedEffect(controller) { controller.start() }
        LifecycleResumeEffect(controller) {
            controller.lockMayHaveChanged()
            onPauseOrDispose { }
        }
        LaunchedEffect(controller) {
            controller.events.collect { e ->
                when (e) {
                    DriveEvent.OpenSaveCopy -> onOpenExport()
                    DriveEvent.OpenLockSettings -> services.openLockSettings()
                    DriveEvent.OpenImportPreview -> services.takeImportFile()?.let(onOpenImport)
                }
            }
        }
        services.SecureScreen(state.stage == DriveStage.RECOVERY_KEY)
        DriveContent(state, controller, services, Modifier.padding(padding))
    }
}

@Composable
internal fun DriveContent(state: DriveUiState, c: DriveSettingsController, services: DriveServices, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.message?.let { Notice(it, c::dismissMessage, warn = state.stage == DriveStage.PROBLEM || state.stage == DriveStage.PAUSED) }
        when (state.stage) {
            DriveStage.HIDDEN -> Unit
            DriveStage.DISCONNECTED -> Disconnected(state, c)
            DriveStage.NEEDS_SCREEN_LOCK -> {
                WarnNote(stringResource(Res.string.drive_needs_lock), textStyle = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = c::openLockSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                    ButtonLabel(stringResource(Res.string.drive_open_lock_settings))
                }
            }
            DriveStage.WORKING -> Working(stringResource(Res.string.drive_connecting))
            DriveStage.FIRST_CONNECT -> FirstConnect(state, c)
            DriveStage.RECOVERY_KEY -> RecoveryKeyStepView(state, c, services)
            DriveStage.NEEDS_ENROLMENT -> RecoveryEntry(state, c, Res.string.drive_enrol_title, Res.string.drive_enrol_body, noKeyText = Res.string.drive_enrol_no_recovery)
            DriveStage.NEEDS_RECOVERY_KEY -> RecoveryEntry(state, c, Res.string.drive_recovery_title, Res.string.drive_recovery_body, noKeyText = null)
            DriveStage.FOLDER_GONE -> FolderGone(state, c)
            DriveStage.PAUSED -> {
                SectionHeading(stringResource(Res.string.drive_paused_title))
                OutlinedButton(onClick = c::retry, modifier = Modifier.heightIn(min = 48.dp)) { ButtonLabel(stringResource(Res.string.drive_retry)) }
                OutlinedButton(onClick = c::openLockSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                    ButtonLabel(stringResource(Res.string.drive_open_lock_settings))
                }
            }
            DriveStage.PROBLEM -> {
                OutlinedButton(onClick = c::retry, enabled = state.busy == null, modifier = Modifier.heightIn(min = 48.dp)) {
                    ButtonLabel(stringResource(Res.string.drive_retry))
                }
                DisconnectBlock(state, c)
            }
            DriveStage.READY -> Ready(state, c)
        }
    }
    state.dialog?.let { DeleteDialogView(it, c) }
}

@Composable
private fun Notice(message: DriveMessage, onDismiss: () -> Unit, warn: Boolean) {
    val text = stringResource(message.text())
    LiveMessage(assertive = warn) {
        if (warn) {
            WarnNote(text, action = stringResource(Res.string.drive_dismiss), onAction = onDismiss, textStyle = MaterialTheme.typography.bodyMedium)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { ButtonLabel(stringResource(Res.string.drive_dismiss)) }
            }
        }
    }
}

@Composable
private fun Working(label: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.padding(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Disconnected(state: DriveUiState, c: DriveSettingsController) {
    Text(stringResource(Res.string.drive_intro), style = MaterialTheme.typography.bodyMedium)
    Button(onClick = c::connect, enabled = state.busy == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        ButtonLabel(stringResource(if (state.busy == DriveBusy.CONNECTING) Res.string.drive_connecting else Res.string.drive_connect))
    }
}

@Composable
private fun FirstConnect(state: DriveUiState, c: DriveSettingsController) {
    var asking by remember { mutableStateOf(false) }
    var ticked by remember { mutableStateOf(false) }
    SectionHeading(stringResource(Res.string.drive_first_title))
    Text(stringResource(Res.string.drive_first_body), style = MaterialTheme.typography.bodyMedium)
    Button(
        onClick = { c.createFirstFolder() },
        enabled = state.busy == null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { ButtonLabel(stringResource(Res.string.drive_first_make)) }
    if (!asking) {
        TextButton(onClick = { asking = true }, enabled = state.busy == null, modifier = Modifier.heightIn(min = 48.dp)) {
            ButtonLabel(stringResource(Res.string.drive_first_skip))
        }
    } else {
        // The plain warning first, a tick, then the skip (docs/15 §9.4).
        WarnNote(stringResource(Res.string.drive_skip_warn), textStyle = MaterialTheme.typography.bodyMedium)
        TickRow(stringResource(Res.string.drive_skip_tick), ticked) { ticked = it }
        OutlinedButton(
            onClick = { c.createFirstFolder(skipRecoveryKey = true, skipAcknowledged = ticked) },
            enabled = ticked && state.busy == null,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { ButtonLabel(stringResource(Res.string.drive_skip_confirm)) }
    }
}

@Composable
private fun TickRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RecoveryKeyStepView(state: DriveUiState, c: DriveSettingsController, services: DriveServices) {
    val step: RecoveryKeyStep = state.recoveryKey ?: return Working(stringResource(Res.string.drive_working))
    var copied by remember { mutableStateOf(false) }
    val answers = remember(step.display) { mutableStateOf(List(step.askGroups.size) { "" }) }
    SectionHeading(stringResource(Res.string.drive_key_title))
    Text(stringResource(Res.string.drive_key_body), style = MaterialTheme.typography.bodyMedium)
    val label = stringResource(Res.string.drive_key_label)
    // TalkBack reads each character, not a word: the key is letters and digits.
    val spoken = step.display.filter { it != '-' }.toList().joinToString(" ")
    Text(
        step.display,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label: $spoken" },
    )
    OutlinedButton(
        onClick = {
            services.copyRecoveryKey(step.display)
            copied = true
        },
        modifier = Modifier.heightIn(min = 48.dp),
    ) { ButtonLabel(stringResource(if (copied) Res.string.drive_key_copied else Res.string.drive_key_copy)) }
    Text(stringResource(Res.string.drive_key_check, step.askGroups[0], step.askGroups[1]), style = MaterialTheme.typography.bodyMedium)
    step.askGroups.forEachIndexed { i, group ->
        OutlinedTextField(
            value = answers.value[i],
            onValueChange = { v -> answers.value = answers.value.toMutableList().also { it[i] = v } },
            label = { Text(stringResource(Res.string.drive_key_group, group)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Button(
        onClick = { c.confirmRecoveryKey(answers.value) },
        enabled = state.busy == null && answers.value.all { it.isNotBlank() },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { ButtonLabel(stringResource(Res.string.drive_key_saved)) }
}

@Composable
private fun RecoveryEntry(
    state: DriveUiState,
    c: DriveSettingsController,
    title: org.jetbrains.compose.resources.StringResource,
    body: org.jetbrains.compose.resources.StringResource,
    noKeyText: org.jetbrains.compose.resources.StringResource?,
) {
    var typed by remember { mutableStateOf("") }
    SectionHeading(stringResource(title))
    Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
    if (state.recoveryAvailable || noKeyText == null) {
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text(stringResource(Res.string.drive_recovery_field)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { c.enterRecoveryKey(typed) },
            enabled = typed.isNotBlank() && state.busy == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { ButtonLabel(stringResource(Res.string.drive_recovery_open)) }
    } else {
        Text(stringResource(noKeyText), style = MaterialTheme.typography.bodyMedium)
    }
    DisconnectBlock(state, c)
}

@Composable
private fun FolderGone(state: DriveUiState, c: DriveSettingsController) {
    var ticked by remember { mutableStateOf(false) }
    SectionHeading(stringResource(Res.string.drive_gone_title))
    Text(stringResource(Res.string.drive_gone_body), style = MaterialTheme.typography.bodyMedium)
    TickRow(stringResource(Res.string.drive_gone_tick), ticked) { ticked = it }
    Button(
        onClick = { c.startAgain(ticked) },
        enabled = ticked && state.busy == null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { ButtonLabel(stringResource(Res.string.drive_gone_start)) }
    DisconnectBlock(state, c)
}

@Composable
private fun DisconnectBlock(state: DriveUiState, c: DriveSettingsController) {
    HorizontalDivider()
    Text(stringResource(Res.string.drive_disconnect_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = c::disconnectThisDevice, enabled = state.busy == null, modifier = Modifier.heightIn(min = 48.dp)) {
        ButtonLabel(stringResource(Res.string.drive_disconnect))
    }
}

@Composable
private fun Ready(state: DriveUiState, c: DriveSettingsController) {
    val ready = state.ready
    val idle = state.busy == null
    state.account?.let { Text(stringResource(Res.string.drive_account, it), style = MaterialTheme.typography.bodyMedium) }
    ready?.storageUsedBytes?.let { Text(stringResource(Res.string.drive_storage, driveSizeText(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Text(
        ready?.lastBackupAt?.let { stringResource(Res.string.drive_last_backup, it.dateText()) } ?: stringResource(Res.string.drive_last_none),
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(onClick = c::backUpNow, enabled = idle, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        ButtonLabel(stringResource(if (state.busy == DriveBusy.BACKING_UP) Res.string.drive_backing_up else Res.string.drive_back_up_now))
    }
    if (state.busy != null && state.busy != DriveBusy.BACKING_UP) Working(stringResource(Res.string.drive_working))
    if (ready?.shrinkHoldBackupId != null) {
        OutlinedButton(onClick = c::confirmShrink, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) { ButtonLabel(stringResource(Res.string.drive_shrink_ok)) }
    }
    ready?.pendingDeletionLeft?.let { left ->
        WarnNote(stringResource(Res.string.drive_resume_note, left), textStyle = MaterialTheme.typography.bodyMedium, action = stringResource(Res.string.drive_retry), onAction = c::resumeDeletion)
    }

    HorizontalDivider()
    SwitchRow(
        text = stringResource(Res.string.drive_auto), hint = stringResource(Res.string.drive_auto_hint),
        checked = state.autoBackup, horizontalPadding = 0.dp, onChange = c::setAutoBackup,
    )
    SwitchRow(
        text = stringResource(Res.string.drive_photos_wifi), hint = null,
        checked = state.photosWifiOnly, horizontalPadding = 0.dp, onChange = c::setPhotosWifiOnly,
    )
    // The one-off button waits for S4b-BL-128; it is shown so the layout is final, and says why it does nothing yet.
    OutlinedButton(onClick = {}, enabled = state.mobileDataOneOffAvailable, modifier = Modifier.heightIn(min = 48.dp)) {
        ButtonLabel(stringResource(Res.string.drive_photos_mobile) + if (state.mobileDataOneOffAvailable) "" else " (" + stringResource(Res.string.drive_photos_mobile_soon) + ")")
    }
    Text(
        stringResource(
            when (state.recoveryStatus) {
                RecoveryStatus.SAVED -> Res.string.drive_recovery_status_saved
                RecoveryStatus.UNCONFIRMED -> Res.string.drive_recovery_status_unconfirmed
                else -> Res.string.drive_recovery_status_none
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
    )

    HorizontalDivider()
    SectionHeading(stringResource(Res.string.drive_backups_title))
    if (ready == null || ready.backups.isEmpty()) {
        Text(stringResource(Res.string.drive_backups_empty), style = MaterialTheme.typography.bodyMedium)
    }
    ready?.backups?.forEach { BackupItem(it, idle, c) }

    HorizontalDivider()
    SectionHeading(stringResource(Res.string.drive_delete_menu))
    val hasBackups = ready?.backups?.isNotEmpty() == true
    OutlinedButton(onClick = { c.openDelete(DriveDeleteChoice.OlderBackups) }, enabled = idle && (ready?.backups?.size ?: 0) > 1, modifier = Modifier.heightIn(min = 48.dp)) {
        ButtonLabel(stringResource(Res.string.drive_delete_older))
    }
    OutlinedButton(onClick = { c.openDelete(DriveDeleteChoice.AllBackups) }, enabled = idle && hasBackups, modifier = Modifier.heightIn(min = 48.dp)) {
        ButtonLabel(stringResource(Res.string.drive_delete_all))
    }
    OutlinedButton(onClick = { c.openDelete(DriveDeleteChoice.Everything) }, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) {
        ButtonLabel(stringResource(Res.string.drive_delete_everything))
    }
    DisconnectBlock(state, c)
}

@Composable
private fun BackupItem(row: BackupRow, enabled: Boolean, c: DriveSettingsController) {
    val date = row.createdAt.dateText()
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(Res.string.drive_backup_row, date, row.houses) + (if (row.isNewest) " · " + stringResource(Res.string.drive_backup_newest) else ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val importLabel = stringResource(Res.string.drive_import_row_for, date)
            TextButton(onClick = { c.importBackup(row.id) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = importLabel }) {
                ButtonLabel(stringResource(Res.string.drive_import_row))
            }
            val deleteLabel = stringResource(Res.string.drive_delete_backup_for, date)
            TextButton(onClick = { c.openDelete(DriveDeleteChoice.OneBackup(row.id)) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = deleteLabel }) {
                ButtonLabel(stringResource(Res.string.drive_delete_backup))
            }
        }
    }
}

@Composable
private fun DeleteDialogView(d: DeleteDialog, c: DriveSettingsController) {
    // The countdown of the 5 seconds (level three), redrawn twice a second; the state holder checks it again on confirm.
    var now by remember(d.openedAtMs) { mutableStateOf(nowMillis()) }
    LaunchedEffect(d.openedAtMs, d.delaySeconds) {
        while (d.delaySeconds > 0 && now - d.openedAtMs < d.delaySeconds * 1000L + 1000L) {
            kotlinx.coroutines.delay(250)
            now = nowMillis()
        }
    }
    val title = when (d.choice) {
        is DriveDeleteChoice.OneBackup -> Res.string.drive_dlg_title_one
        DriveDeleteChoice.OlderBackups -> Res.string.drive_dlg_title_older
        DriveDeleteChoice.AllBackups -> Res.string.drive_dlg_title_all
        DriveDeleteChoice.Everything -> Res.string.drive_dlg_title_everything
    }
    val working = d.phase != DeletePhase.CONFIRM
    val left = d.secondsLeft(now)
    AlertDialog(
        onDismissRequest = { if (!working) c.cancelDelete() },
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.drive_dlg_body, d.totalFiles, driveSizeText(d.totalBytes)))
                if (d.level == DeletionLevel.L3) Text(stringResource(Res.string.drive_dlg_everything_extra))
                if (d.foreignKept > 0) Text(stringResource(Res.string.drive_dlg_foreign), style = MaterialTheme.typography.bodySmall)
                if (d.needsTick) TickRow(stringResource(Res.string.drive_dlg_tick), d.ticked) { c.setDeleteTick(it) }
                if (working) Working(stringResource(Res.string.drive_dlg_working))
            }
        },
        confirmButton = {
            Button(onClick = c::confirmDelete, enabled = d.confirmEnabled(now), modifier = Modifier.heightIn(min = 48.dp)) {
                // The label counts down, so a screen reader hears the wait too.
                ButtonLabel(if (left > 0 && !working) stringResource(Res.string.drive_dlg_delete_wait, left) else stringResource(Res.string.drive_dlg_delete))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = c::saveCopyFirst, enabled = !working, modifier = Modifier.heightIn(min = 48.dp)) { ButtonLabel(stringResource(Res.string.drive_dlg_save_copy)) }
                TextButton(onClick = c::cancelDelete, enabled = !working, modifier = Modifier.heightIn(min = 48.dp)) { ButtonLabel(stringResource(Res.string.drive_dlg_cancel)) }
            }
        },
    )
}
