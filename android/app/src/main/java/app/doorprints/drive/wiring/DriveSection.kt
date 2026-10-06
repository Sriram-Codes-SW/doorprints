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

package app.doorprints.drive.wiring

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.doorprints.R
import app.doorprints.drive.connect.DriveReason
import app.doorprints.ui.drive.ControllerDriveActions
import app.doorprints.ui.drive.DriveHost
import app.doorprints.ui.drive.DriveSettings
import app.doorprints.ui.drive.DriveViewModel
import kotlinx.coroutines.launch

/**
 * Settings > Google Drive on Android (docs/15 §2, §10.3): the common Drive screens ([DriveSettings]) with this app's
 * hand-offs, behind the screen-lock rule. Without a screen lock and with Drive not in use, the card is replaced by the
 * documented words and *Open settings* (Drive cannot be switched on); with Drive in use and the lock gone, the paused
 * words show above the card (the person can still disconnect). Read again each time the screen resumes, so setting a lock
 * and coming back is enough.
 *
 * QR scanning is Google's code scanner ([AndroidQrScanner], no CAMERA permission); without Play services the Scan button is
 * hidden and the screens offer the pasted `dp1.` text and the 8-digit code (docs/ops/android-drive-wiring-notes.md).
 */
@Composable
fun DriveSettingsSection(drive: DriveServices) {
    var notice by remember { mutableStateOf(drive.lockNotice()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { notice = drive.lockNotice() }
    }
    HorizontalDivider()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (notice != LockNotice.NONE) LockNoticeBlock(notice)
        if (DriveLockRules.showsCard(notice)) DriveCard(drive)
    }
}

/** The words for a notice (the same ones in Settings and in the notification of a paused run). */
internal fun LockNotice.messageRes(): Int = when (this) {
    LockNotice.NEEDS_LOCK -> R.string.drive_lock_needs
    LockNotice.KEY_LOST -> R.string.drive_lock_key_lost
    LockNotice.PAUSED, LockNotice.NONE -> R.string.drive_lock_paused
}

@Composable
internal fun LockNoticeBlock(notice: LockNotice) {
    val context = LocalContext.current
    if (notice == LockNotice.NEEDS_LOCK) {
        Text(
            stringResource(R.string.drive_lock_heading), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
    }
    Text(stringResource(notice.messageRes()))
    OutlinedButton(onClick = { openSecuritySettings(context) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.drive_lock_open_settings))
    }
}

@Composable
private fun DriveCard(drive: DriveServices) {
    val vm: DriveViewModel = viewModel {
        DriveViewModel(
            ControllerDriveActions(drive.controller, folderGone = drive.graph.folderGone),
            codec = Dp1EnrolmentCodec(drive.graph.crypto),
            deviceName = { Build.MODEL.orEmpty().ifBlank { "Android phone" } },
        )
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<DriveReason?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = remember(context) { AndroidClipboardSeam(context) }
    val host = DriveHost(
        onImportBackup = { backup ->
            if (!busy) {
                busy = true
                error = null
                scope.launch {
                    error = drive.importFromDrive(backup)
                    busy = false
                }
            }
        },
        onSaveCopy = drive::openSaveCopy,
        importBusy = busy,
        importError = error,
        clipboard = clipboard,
    )
    val scanner = remember(drive, context) { AndroidQrScanner(GmsQrScanBackend(drive.activities, context.applicationContext)) }
    DriveSettings(vm, scanner = scanner, host = host)
}

/** The phone's own security settings (docs/15 §10.3: `ACTION_SECURITY_SETTINGS`); the page of all settings when that is missing. */
private fun openSecuritySettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // Nothing can open settings on this phone; the words above still say what to do.
        }
    }
}
