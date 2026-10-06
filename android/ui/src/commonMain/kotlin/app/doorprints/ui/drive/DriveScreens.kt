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

package app.doorprints.ui.drive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.doorprints.crypto.encodeQr
import app.doorprints.drive.connect.BackupSummary
import app.doorprints.drive.connect.DriveReason
import app.doorprints.drive.connect.ListedDevice
import app.doorprints.drive.delete.ItemKind
import app.doorprints.ui.ButtonLabel
import app.doorprints.ui.LiveMessage
import app.doorprints.ui.SwitchRow
import app.doorprints.ui.WarnNote
import app.doorprints.ui.dateText
import app.doorprints.ui.spellCode
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.common_cancel
import app.doorprints.ui.res.common_delete
import app.doorprints.ui.res.drive_connect_failed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/*
 * The Google Drive screens of Settings (S4b-BL-116 to -119, -126), written once for both phones (:ui is common code).
 * They follow the website's cards (web/src/app/pages/data/drive*.html) and draw only what DriveScreenState says.
 *
 * Accessibility (docs/05): every button and row is at least 48 dp; a state that changes by itself (connecting, a backup
 * done, an error, the countdown) is in a live region that is on screen before its text arrives; headings are marked;
 * the recovery key and the 8-digit code are read letter by letter; the QR code has a description; both themes use the
 * theme's colours; and a button label wraps instead of being clipped at 200 % text.
 */

/** The app's hooks the screens cannot supply themselves. */
class DriveHost(
    /** *Import a backup* from Drive: the app downloads the chosen backup into its staging file and opens the import preview. */
    val onImportBackup: (BackupSummary) -> Unit = {},
    /** *Save a copy first*: opens the Export screen. */
    val onSaveCopy: () -> Unit = {},
    /** True while the import hand-off is working, so the rows' buttons are off. */
    val importBusy: Boolean = false,
    /** The message of that hand-off when it failed (a dictionary key from the import, shown under the list). */
    val importError: DriveReason? = null,
    /** Copies a secret to the clipboard marked sensitive (Android 13+: `EXTRA_IS_SENSITIVE`). Null: a plain copy. */
    val clipboard: ClipboardSeam? = null,
)

/** The host's sensitive-copy seam, or a plain copy through Compose's clipboard where the app gave none. */
@Composable
private fun rememberClipboard(host: DriveHost): ClipboardSeam {
    val plain = LocalClipboardManager.current
    return host.clipboard ?: remember(plain) {
        object : ClipboardSeam {
            override fun copySensitive(text: String) = plain.setText(AnnotatedString(text))
        }
    }
}

@Composable
internal fun t(key: String, vararg args: Any): String {
    val res: StringResource = DRIVE_STRINGS[key] ?: Res.string.drive_connect_failed
    return stringResource(res, *args)
}

@Composable
private fun reasonText(reason: DriveReason): String = t(reasonKey(reason))

/**
 * Settings > Google Drive. [holder] is the state holder ([DriveViewModel] in the app); [scanner] the platform's QR
 * scanner ([NoQrScanner] without a camera); [host] the app's hand-offs.
 */
@Composable
fun DriveSettingsSection(holder: DriveHolder, scanner: QrScanner = NoQrScanner, host: DriveHost = DriveHost(), modifier: Modifier = Modifier) {
    val ui by holder.ui.collectAsState()
    DriveSettingsContent(ui, holder, scanner, host, modifier)
}

/** The section for one [ui] state: [DriveSettingsSection] draws the holder's, a screenshot test draws any. */
@Composable
fun DriveSettingsContent(ui: DriveUiState, holder: DriveHolder, scanner: QrScanner = NoQrScanner, host: DriveHost = DriveHost(), modifier: Modifier = Modifier) {
    val clipboard = rememberClipboard(host)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading(t("driveConnect.heading"), main = true)
        when (ui.card) {
            DriveCard.UNAVAILABLE -> Text(t("driveConnect.unavailable"))
            DriveCard.DISCONNECTED -> DisconnectedCard(ui, holder)
            DriveCard.CONNECTING -> LiveMessage { Text(t("driveConnect.connecting")) }
            DriveCard.RECOVERY_KEY -> RecoveryKeyCard(ui, holder, clipboard)
            DriveCard.JOIN -> JoinCard(ui, holder, scanner, clipboard, revoked = false)
            DriveCard.REVOKED -> JoinCard(ui, holder, scanner, clipboard, revoked = true)
            DriveCard.READY -> ReadyCard(ui, holder, scanner, host, clipboard)
            DriveCard.ERROR -> ErrorBox(ui.error, ui, holder)
            DriveCard.FOLDER_GONE -> FolderGoneCard(ui, holder)
        }
    }
}

// ---- Small parts --------------------------------------------------------------------------------------------------------------

@Composable
private fun Heading(text: String, main: Boolean = false) {
    Text(
        text, style = if (main) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun DriveButton(text: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false, danger: Boolean = false, description: String? = null) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).let { m -> if (description != null) m.semantics { contentDescription = description } else m }
    when {
        danger -> Button(
            onClick, modifier, enabled,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        ) { ButtonLabel(text) }
        primary -> Button(onClick, modifier, enabled) { ButtonLabel(text) }
        else -> OutlinedButton(onClick, modifier, enabled) { ButtonLabel(text) }
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(text, Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun ErrorLine(reason: DriveReason?) {
    LiveMessage(assertive = true) {
        if (reason != null) Text(reasonText(reason), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A recovery key on screen: monospace, wrapped, read letter by letter. */
@Composable
private fun KeyBox(text: String, label: String) {
    Text(
        text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)
            .semantics { contentDescription = label + ": " + spellCode(text) },
    )
}

/** A QR code drawn from the shared encoder: dark squares on a light ground with a four-square quiet zone, in both themes. */
@Composable
fun QrCodeView(text: String, description: String, modifier: Modifier = Modifier) {
    val modules = remember(text) { encodeQr(text.encodeToByteArray()) }
    val quiet = 4
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(240.dp).semantics { contentDescription = description }) {
            val n = modules.size + quiet * 2
            val cell = size.width / n
            drawRect(Color.White, Offset.Zero, size)
            for (y in modules.indices) for (x in modules[y].indices) {
                if (modules[y][x]) drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

// ---- Connect card -------------------------------------------------------------------------------------------------------------

@Composable
private fun ControlsFor(ui: DriveUiState, holder: DriveHolder, error: DriveReason?) {
    for (spec in connectControls(ui.card, error, ui.keySaved, ui.busy)) {
        when (spec.control) {
            DriveControl.CONNECT -> DriveButton(t("driveConnect.connect"), holder::connect, spec.enabled, primary = true)
            DriveControl.RETRY -> DriveButton(t("drive.common.retry"), holder::connect, spec.enabled)
            else -> Unit
        }
    }
}

@Composable
private fun DisconnectedCard(ui: DriveUiState, holder: DriveHolder) {
    if (ui.error == null) {
        Text(t("driveConnect.intro"))
        ControlsFor(ui, holder, null)
    } else {
        ErrorBox(ui.error, ui, holder)
    }
}

/** The folder was deleted: ask (docs/15 section 3.4). Nothing is created until *Start again*; *Disconnect* leaves Drive. */
@Composable
private fun FolderGoneCard(ui: DriveUiState, holder: DriveHolder) {
    LiveMessage(assertive = true) { Text(t("driveConnect.folderGoneAsk")) }
    for (spec in connectControls(ui.card, ui.error, ui.keySaved, ui.busy)) {
        when (spec.control) {
            DriveControl.START_AGAIN -> DriveButton(t("driveConnect.startAgain"), holder::startAgain, spec.enabled, primary = true)
            DriveControl.DISCONNECT -> DriveButton(t("driveConnect.disconnect"), holder::disconnect, spec.enabled)
            else -> Unit
        }
    }
}

@Composable
private fun ErrorBox(error: DriveReason?, ui: DriveUiState, holder: DriveHolder) {
    LiveMessage(assertive = true) {
        Text(reasonText(error ?: DriveReason.FAILED), color = MaterialTheme.colorScheme.error)
    }
    ControlsFor(ui, holder, error)
}

@Composable
private fun RecoveryKeyCard(ui: DriveUiState, holder: DriveHolder, clipboard: ClipboardSeam) {
    val key = ui.connectKey
    var copied by remember { mutableStateOf<Boolean?>(null) }
    Heading(t("driveConnect.firstConnect"))
    Text(t("driveConnect.recoveryKeyNote"), color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (recoveryKeyVisible(ui.card, key) && key != null) {
        KeyBox(key.text, t("driveJoin.label"))
        val enabled = connectControls(ui.card, null, ui.keySaved, ui.busy).first { it.control == DriveControl.COPY_KEY }.enabled
        DriveButton(
            if (copied == true) t("driveConnect.recoveryKeyCopied") else t("driveConnect.recoveryKeyCopy"),
            onClick = { copied = copyToClipboard(clipboard, key.text) },
            enabled = enabled,
        )
        LiveMessage { if (copied == false) Text(t("driveConnect.copyFailed"), color = MaterialTheme.colorScheme.error) }
    }
    WarnNote(t("driveConnect.recoveryKeyWarning"), textStyle = MaterialTheme.typography.bodyMedium)
    CheckRow(t("driveConnect.confirmSavedRecoveryKey"), ui.keySaved, enabled = !ui.busy, onChange = holder::setKeySaved)
    for (spec in connectControls(ui.card, null, ui.keySaved, ui.busy)) {
        when (spec.control) {
            DriveControl.KEY_NEXT -> DriveButton(t("drive.common.next"), holder::keyNext, spec.enabled, primary = true)
            DriveControl.KEY_SKIP -> DriveButton(t("drive.common.skip"), holder::keySkip, spec.enabled)
            else -> Unit
        }
    }
}

@Composable
private fun JoinCard(ui: DriveUiState, holder: DriveHolder, scanner: QrScanner, clipboard: ClipboardSeam, revoked: Boolean) {
    // The typed key lives only here, and is cleared the moment it is sent (the holder never keeps it).
    var typed by remember { mutableStateOf("") }
    if (revoked) {
        LiveMessage { Text(t(DriveReason.DEVICE_REVOKED.key)) }
    } else {
        Heading(t("driveConnect.needsEnrolmentHeading"))
        Text(t("driveConnect.needsEnrolmentMessage"))
    }
    Heading(t("driveJoin.heading"))
    Text(t("driveJoin.description"), color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (joinKeyFieldVisible(ui.card)) {
        OutlinedTextField(
            value = typed, onValueChange = { typed = it },
            label = { Text(t("driveJoin.label")) },
            supportingText = { Text(t("driveJoin.helpText")) },
            singleLine = true, enabled = !ui.busy,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    ErrorLine(ui.error)
    DriveButton(
        if (ui.busy) t("drive.common.loading") else t("driveJoin.joinButton"),
        onClick = {
            val text = typed
            typed = ""
            holder.join(text)
        },
        enabled = joinEnabled(typed, ui.busy), primary = true,
    )
    if (!revoked) {
        Text(t("driveJoin.lostKeyMessage"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        EnrolCard(ui, holder, scanner, clipboard, newDevice = true)
    }
    val disconnectLabel = t("driveJoin.disconnectAriaLabel")
    TextButton(
        onClick = holder::disconnect, enabled = !ui.busy,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = disconnectLabel },
    ) { Text(t("driveJoin.disconnect")) }
}

// ---- Enrolment ----------------------------------------------------------------------------------------------------------------------

@Composable
private fun EnrolCard(ui: DriveUiState, holder: DriveHolder, scanner: QrScanner, clipboard: ClipboardSeam, newDevice: Boolean) {
    val scope = rememberCoroutineScope()
    var pasted by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    Heading(t(if (newDevice) "driveEnrol.headingJoin" else "driveEnrol.headingApprove"))
    when (val e = ui.enrol) {
        EnrolUi.Idle -> {
            if (newDevice) {
                DriveButton(t("driveDevices.showQr"), holder::showQr, !ui.busy, primary = true)
            } else {
                DriveButton(t("driveDevices.approve"), holder::startApprove, !ui.busy)
            }
        }
        is EnrolUi.Newcomer -> {
            Text(t("driveEnrol.newHelp"))
            QrCodeView(e.offer.qrText, t("driveEnrol.qrDescription"))
            CodeLine(e.offer.code)
            DriveButton(if (copied) t("driveEnrol.copied") else t("driveEnrol.copyCode"), { copied = copyToClipboard(clipboard, e.offer.qrText) })
            ScanOrPaste(t("driveEnrol.pasteReply"), pasted, { pasted = it }, scanner, e.cameraMissing, scope, holder, ui.busy) { text ->
                holder.submitReply(text)
            }
            ErrorLine(e.error)
            DriveButton(t("driveEnrol.joinNow"), { holder.submitReply(pasted) }, enabled = pasted.isNotBlank() && !ui.busy, primary = true)
            DriveButton(stringResource(Res.string.common_cancel), holder::closeEnrol)
        }
        is EnrolUi.ApproverInput -> {
            Text(t("driveEnrol.approveHelp"))
            ScanOrPaste(t("driveEnrol.pasteOffer"), pasted, { pasted = it }, scanner, e.cameraMissing, scope, holder, ui.busy) { text ->
                holder.submitOffer(text)
            }
            ErrorLine(e.error)
            DriveButton(t("drive.common.next"), { holder.submitOffer(pasted) }, enabled = pasted.isNotBlank() && !ui.busy, primary = true)
            DriveButton(stringResource(Res.string.common_cancel), holder::closeEnrol)
        }
        is EnrolUi.ApproverCheck -> {
            CodeLine(e.offer.code)
            Text(t("driveEnrol.confirmMatch"))
            OutlinedTextField(
                value = e.name, onValueChange = holder::setApproveName, label = { Text(t("driveEnrol.deviceName")) },
                singleLine = true, enabled = !ui.busy, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            Text(t("driveEnrol.deviceKind"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.selectableGroup()) {
                for (kind in DeviceKind.entries) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = e.kind == kind, enabled = !ui.busy, role = Role.RadioButton, onClick = { holder.setApproveKind(kind) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = e.kind == kind, onClick = null, enabled = !ui.busy)
                        Text(t(if (kind == DeviceKind.PHONE) "driveEnrol.kindPhone" else "driveEnrol.kindComputer"), Modifier.padding(start = 12.dp))
                    }
                }
            }
            Text(t("driveDevices.deviceCheckNote"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ErrorLine(e.error)
            DriveButton(t("driveDevices.codesMatch"), holder::confirmCodesMatch, enabled = !ui.busy, primary = true)
            DriveButton(stringResource(Res.string.common_cancel), holder::closeEnrol)
        }
        is EnrolUi.ApproverReply -> {
            LiveMessage { Text(t("driveEnrol.done")) }
            QrCodeView(e.replyText, t("driveEnrol.qrDescription"))
            DriveButton(if (copied) t("driveEnrol.copied") else t("driveEnrol.copyCode"), { copied = copyToClipboard(clipboard, e.replyText) })
            DriveButton(t("drive.common.close"), holder::closeEnrol, primary = true)
        }
    }
}

@Composable
private fun CodeLine(code: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(t("driveEnrol.codeLabel"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            codeGrouped(code), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { contentDescription = codeSpoken(code) },
        )
    }
}

/** A scan button (where the platform has a camera) and a paste field: the same two ways on the website. */
@Composable
private fun ScanOrPaste(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    scanner: QrScanner,
    cameraMissing: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    holder: DriveHolder,
    busy: Boolean,
    onText: (String) -> Unit,
) {
    if (scanner.isAvailable) {
        DriveButton(t("driveDevices.scanQr"), {
            scope.launch {
                when (val r = scanner.scan()) {
                    is QrScan.Scanned -> onText(r.text)
                    QrScan.NoCamera -> holder.scannerMissing()
                    QrScan.Cancelled -> Unit
                }
            }
        }, enabled = !busy)
    }
    LiveMessage { if (cameraMissing) Text(t("driveEnrol.cameraMissing"), color = MaterialTheme.colorScheme.error) }
    OutlinedTextField(
        value = value, onValueChange = onValue, label = { Text(label) }, enabled = !busy, minLines = 2, maxLines = 4,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---- Ready: backups, sync, devices, deleting, disconnect ------------------------------------------------------------------------------

@Composable
private fun ReadyCard(ui: DriveUiState, holder: DriveHolder, scanner: QrScanner, host: DriveHost, clipboard: ClipboardSeam) {
    LiveMessage { Text(t("driveConnect.ready")) }
    BackupsCard(ui, holder, host)
    HorizontalDivider()
    SyncCard(ui, holder)
    HorizontalDivider()
    DevicesCard(ui, holder)
    HorizontalDivider()
    EnrolCard(ui, holder, scanner, clipboard, newDevice = false)
    HorizontalDivider()
    DeleteCard(ui, holder, host)
    HorizontalDivider()
    Heading(t("driveConnect.disconnect"))
    Text(t("driveDevices.threeDisconnect"), style = MaterialTheme.typography.bodySmall)
    DriveButton(t("driveConnect.disconnect"), holder::disconnect, enabled = !ui.busy, danger = true)
}

@Composable
private fun BackupsCard(ui: DriveUiState, holder: DriveHolder, host: DriveHost) {
    val b = ui.backups
    Heading(t("driveBackups.backups"))
    val last = b.lastBackupAt
    Text(
        if (last != null && b.lastBackupHouses != null) t("driveBackups.housesBackedUp", b.lastBackupHouses, last.dateText())
        else t("driveConnect.lastBackup") + ": " + t("driveConnect.never"),
    )
    DriveButton(t("driveBackups.backUpNow"), holder::backUpNow, enabled = backUpNowEnabled(b.busy), primary = true)
    LiveMessage(assertive = true) { b.error?.let { Text(reasonText(it), color = MaterialTheme.colorScheme.error) } }
    if (shrinkQuestionVisible(b.shrinkHoldId)) {
        Heading(t("driveBackups.shrinkHeading"))
        Text(t("driveBackups.shrinkBody"))
        Text(t("driveBackups.keepOlderBackupsNote"), style = MaterialTheme.typography.bodySmall)
        DriveButton(t("driveBackups.keepOlder"), holder::keepOlderBackups, enabled = !b.busy)
        DriveButton(t("driveBackups.shrinkConfirm"), holder::confirmShrink, enabled = !b.busy, primary = true)
    }
    if (b.missingNewer) WarnNote(t("driveBackups.missingNewer"), textStyle = MaterialTheme.typography.bodyMedium)
    when (b.listState) {
        ListState.LOADING -> LiveMessage { Text(t("driveBackups.loadingState")) }
        ListState.ERROR -> {
            LiveMessage(assertive = true) { Text(b.listError?.let { reasonText(it) } ?: t("driveBackups.errorState"), color = MaterialTheme.colorScheme.error) }
            DriveButton(t("driveBackups.retry"), holder::loadBackups)
        }
        ListState.EMPTY -> LiveMessage { Text(t("driveBackups.emptyState")) }
        ListState.LIST -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (backup in b.list) BackupRow(
                backup, enabled = importEnabled(host.importBusy, b.busy), onImport = { host.onImportBackup(backup) },
                deleteEnabled = deleteBackupEnabled(ui.busy, b.busy, ui.delete.phase),
                onDelete = { holder.startDelete(DeleteChoice.ONE_BACKUP, backup.id) },
            )
        }
    }
    // The delete of one backup shows here, under the list the person tapped in (not at the bottom of the screen).
    if (deleteFlowInBackups(ui.delete.choice, ui.delete.phase)) DeleteFlow(ui, holder, host)
    host.importError?.let { ErrorLine(it) }
    SwitchRow(
        text = t("driveBackups.autoBackup"), hint = null, checked = b.auto, enabled = !b.busy, horizontalPadding = 0.dp,
        onChange = holder::setAutoBackup,
    )
}

@Composable
private fun BackupRow(backup: BackupSummary, enabled: Boolean, onImport: () -> Unit, deleteEnabled: Boolean, onDelete: () -> Unit) {
    val whenText = backup.createdAt.dateText()
    val size = backup.bytes?.let { sizeText(it) } ?: t("driveBackups.sizeUnknown")
    val importLabel = t("driveBackups.import") + ", " + whenText
    val deleteLabel = t("driveDelete.oneBackup") + ", " + whenText
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(whenText)
        Text("${t("driveBackups.houses")}: ${backup.houses} · $size", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onImport, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = importLabel },
            ) { ButtonLabel(t("driveBackups.import")) }
            OutlinedButton(
                onClick = onDelete, enabled = deleteEnabled,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = deleteLabel },
            ) { ButtonLabel(t("driveDelete.oneBackup")) }
        }
    }
}

@Composable
private fun SyncCard(ui: DriveUiState, holder: DriveHolder) {
    val s = ui.sync
    Heading(t("driveSync.heading"))
    val dateTexts = HashMap<Long, String>()
    s.info.lastSyncAt?.let { dateTexts[it] = it.dateText() }
    val line = syncLine(s.info) { dateTexts[it] ?: "" }
    LiveMessage {
        Text(
            t(line.key, *line.args.toTypedArray()),
            color = if (line.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
    DriveButton(t("driveSync.syncNow"), { holder.syncNow() }, enabled = syncNowEnabled(s.busy), primary = true)
    if (syncConfirmVisible(s.info) && !s.confirmDismissed) {
        Heading(t("driveSync.shrinkConfirm"))
        Text(t("driveSync.shrinkConfirmDetails"))
        DriveButton(t("driveSync.applyShrink"), holder::applySyncShrink, enabled = !s.busy, primary = true)
        DriveButton(t("driveSync.notNow"), holder::notNowSyncShrink, enabled = !s.busy)
    }
    SwitchRow(
        text = t("driveConnect.uploadPhotos"), hint = t(photoStatusKey(s.photoStatus)), checked = s.wifiOnly, enabled = !s.busy,
        horizontalPadding = 0.dp, onChange = holder::setWifiOnly,
    )
    if (uploadNowOffered(s.wifiOnly, s.pendingBytes)) {
        DriveButton(t("driveSync.uploadNow", sizeText(s.pendingBytes ?: 0L)), holder::uploadPhotosNow, enabled = !s.busy)
    }
    LiveMessage(assertive = true) { if (s.photoError) Text(t("driveSync.photoError"), color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun DevicesCard(ui: DriveUiState, holder: DriveHolder) {
    val d = ui.devices
    Heading(t("driveDevices.heading"))
    d.account?.let { Text(t("driveDevices.email", it)) }
    Text(t("driveDevices.deviceCheckNote"), style = MaterialTheme.typography.bodySmall)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (device in d.devices) DeviceRow(device, enabled = !ui.busy, onRevoke = { holder.askRevoke(device) })
    }
    val key = d.newKey
    if (revokeKeyVisible(key) && key != null) {
        Heading(t("driveDevices.newRecoveryKey"))
        Text(t("driveDevices.revokeNote"), style = MaterialTheme.typography.bodySmall)
        KeyBox(key.text, t("driveJoin.label"))
        CheckRow(t("driveConnect.confirmSavedRecoveryKey"), d.newKeySaved, onChange = holder::setNewKeySaved)
        DriveButton(t("drive.common.next"), holder::dismissNewKey, enabled = d.newKeySaved && !ui.busy, primary = true)
    }
    ErrorLine(d.error)
    DriveButton(t("driveDevices.disconnectAll"), holder::askDisconnectAll, enabled = !ui.busy, danger = true)
    Text(t("driveDevices.threeRemove"), style = MaterialTheme.typography.bodySmall)
    d.revoking?.let { device ->
        AlertDialog(
            onDismissRequest = holder::cancelRevoke,
            title = { Text(t("driveDevices.revoke")) },
            text = { Text(t("driveDevices.revokeConfirm", device.name)) },
            confirmButton = { TextButton(holder::confirmRevoke, Modifier.heightIn(min = 48.dp)) { Text(t("driveDevices.revoke"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(holder::cancelRevoke, Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }
    if (d.confirmingDisconnectAll) {
        AlertDialog(
            onDismissRequest = holder::cancelDisconnectAll,
            title = { Text(t("driveDevices.disconnectAll")) },
            text = { Text(t("driveDevices.threeRemove")) },
            confirmButton = { TextButton(holder::confirmDisconnectAll, Modifier.heightIn(min = 48.dp)) { Text(t("driveDevices.disconnectAll"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(holder::cancelDisconnectAll, Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }
}

@Composable
private fun DeviceRow(device: ListedDevice, enabled: Boolean, onRevoke: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (device.self) "${device.name} (${t("driveDevices.thisDevice")})" else device.name, Modifier.weight(1f))
        if (revokeOffered(device)) {
            val revokeLabel = t("driveDevices.revoke") + ": " + device.name
            OutlinedButton(
                onClick = onRevoke, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = revokeLabel },
            ) { ButtonLabel(t("driveDevices.revoke")) }
        }
    }
}

// ---- Deleting ------------------------------------------------------------------------------------------------------------------------------

@Composable
private fun DeleteCard(ui: DriveUiState, holder: DriveHolder, host: DriveHost) {
    Heading(t("driveDelete.heading"))
    // One backup's delete is drawn under the backups list; the menu stays here meanwhile.
    if (ui.delete.phase == DeletePhase.MENU || deleteFlowInBackups(ui.delete.choice, ui.delete.phase)) {
        for (choice in deleteMenu(null)) {
            DriveButton(t(deleteChoiceKey(choice)), { holder.startDelete(choice) }, enabled = !ui.busy && ui.delete.phase == DeletePhase.MENU, danger = choice == DeleteChoice.EVERYTHING)
        }
    } else {
        DeleteFlow(ui, holder, host)
    }
}

/** The steps of a delete after the choice: plan, confirm, run, partial, done. */
@Composable
private fun DeleteFlow(ui: DriveUiState, holder: DriveHolder, host: DriveHost) {
    val d = ui.delete
    when (d.phase) {
        DeletePhase.MENU -> Unit
        DeletePhase.PLAN -> {
            Heading(t("driveDelete.planHeading"))
            d.plan?.let { PlanLines(it) }
            Text(t("driveDelete.planWarning"))
            TextButton(host.onSaveCopy, Modifier.heightIn(min = 48.dp)) { Text(t("driveDelete.saveCopyFirst")) }
            DriveButton(t("driveDelete.proceed"), holder::proceedToConfirm, enabled = !ui.busy, primary = true)
            DriveButton(stringResource(Res.string.common_cancel), holder::cancelDelete, enabled = !ui.busy)
        }
        DeletePhase.CONFIRM -> ConfirmStep(ui, holder, host)
        DeletePhase.RUNNING -> LiveMessage { Text(t("driveDelete.deleting")) }
        DeletePhase.PARTIAL -> {
            LiveMessage(assertive = true) { Text(t("driveDelete.left", d.left, d.total), color = MaterialTheme.colorScheme.error) }
            d.error?.let { ErrorLine(it) }
            Text(t("driveDelete.deviceCheckNote"), style = MaterialTheme.typography.bodySmall)
            DriveButton(t("driveDelete.tryAgain"), holder::tryAgain, enabled = deleteTryAgainOffered(d.phase) && !ui.busy, primary = true)
            DriveButton(stringResource(Res.string.common_cancel), holder::cancelDelete, enabled = !ui.busy)
        }
        DeletePhase.DONE -> {
            LiveMessage { Text(t("driveDelete.done")) }
            DriveButton(t("drive.common.close"), holder::cancelDelete)
        }
        DeletePhase.ERROR -> {
            ErrorLine(d.error)
            DriveButton(stringResource(Res.string.common_cancel), holder::cancelDelete)
        }
    }
}

@Composable
private fun PlanLines(plan: app.doorprints.drive.delete.DeletionPlan) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        for (kind in PlanKind.entries) {
            val itemKind = when (kind) {
                PlanKind.BACKUP -> ItemKind.BACKUP
                PlanKind.SYNC -> ItemKind.SYNC
                PlanKind.PHOTO -> ItemKind.PHOTO
                PlanKind.SHARED -> ItemKind.SHARED
                PlanKind.OTHER -> null
            }
            val count = if (itemKind != null) plan.totals[itemKind]?.count ?: 0 else listOf(ItemKind.README, ItemKind.CONTROL, ItemKind.KEYS).sumOf { plan.totals[it]?.count ?: 0 }
            if (count > 0) Text(t(kind.key, count))
        }
        Text(t("driveDelete.plan.total", plan.fileCount, sizeText(plan.totalBytes)), style = MaterialTheme.typography.bodyMedium)
        if (plan.foreignKept > 0) Text(t("driveDelete.plan.foreign", plan.foreignKept), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ConfirmStep(ui: DriveUiState, holder: DriveHolder, host: DriveHost) {
    val d = ui.delete
    val info = d.info ?: return
    var now by remember(d.confirmShownAtMs) { mutableLongStateOf(holder.now()) }
    val fields = deleteConfirmFields(info)
    LaunchedEffect(d.confirmShownAtMs, info.delayMs) {
        while (countdownSecondsLeft(info.delayMs, now - d.confirmShownAtMs) > 0) {
            delay(250)
            now = holder.now()
        }
    }
    Heading(t("driveDelete.confirmHeading"))
    Text(t("driveDelete.confirmWarning"))
    TextButton(host.onSaveCopy, Modifier.heightIn(min = 48.dp)) { Text(t("driveDelete.saveCopyFirst")) }
    if (DeleteField.DEVICE_CHECK_NOTE in fields && deviceCheckWordingShown(info.level)) {
        Heading(t("driveDelete.deviceCheckHeading"))
        Text(t("driveDelete.deviceCheckNote"))
    }
    if (DeleteField.TICK_BOX in fields) CheckRow(t("driveDelete.confirmCheckbox"), d.ticked, enabled = !ui.busy) { holder.toggleTick() }
    LiveMessage(assertive = true) { if (d.tickHint) Text(t("driveDelete.tickRequired"), color = MaterialTheme.colorScheme.error) }
    val left = countdownSecondsLeft(info.delayMs, now - d.confirmShownAtMs)
    if (DeleteField.COUNTDOWN in fields) LiveMessage { if (left > 0) Text(t("driveDelete.countdown", left)) }
    ErrorLine(d.error)
    DriveButton(
        if (deleteForGoodLabel(info)) t("driveDelete.deleteForGood") else stringResource(Res.string.common_delete),
        holder::confirmDelete,
        enabled = deleteConfirmEnabled(info, d.ticked, now - d.confirmShownAtMs, ui.busy), danger = true,
    )
    DriveButton(stringResource(Res.string.common_cancel), holder::cancelDelete, enabled = !ui.busy)
}
