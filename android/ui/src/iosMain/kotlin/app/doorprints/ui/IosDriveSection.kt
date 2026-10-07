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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.doorprints.crypto.DevicePlatform
import app.doorprints.drive.connect.DriveReason
import app.doorprints.drive.wiring.DriveLockRules
import app.doorprints.drive.wiring.LockNotice
import app.doorprints.ui.drive.ControllerDriveActions
import app.doorprints.ui.drive.Dp1EnrolmentCodec
import app.doorprints.ui.drive.DriveHost
import app.doorprints.ui.drive.DriveSettings
import app.doorprints.ui.drive.DriveViewModel
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.common_cancel
import app.doorprints.ui.res.lock_notice_ios_heading
import app.doorprints.ui.res.lock_notice_ios_key_lost
import app.doorprints.ui.res.lock_notice_ios_needs
import app.doorprints.ui.res.lock_notice_ios_paused
import app.doorprints.ui.res.qr_scan_hint
import app.doorprints.ui.res.qr_scan_not_doorprints
import app.doorprints.ui.res.qr_scan_too_long
import app.doorprints.ui.res.qr_scan_viewfinder
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Settings > Google Drive on the iPhone (docs/15 §2, §10.3): the common Drive screens ([DriveSettings]) with this app's
 * hand-offs, behind the passcode rule, as Android's `DriveSettingsSection`. Without a passcode and with Drive not in use,
 * the card is replaced by the documented words (Drive cannot be switched on; the iPhone has no link to *Face ID & Passcode*,
 * so the words say where it is); with Drive in use and the passcode gone, the paused words show above the card (the person
 * can still disconnect). Read again each time the screen resumes, so setting a passcode and coming back is enough.
 *
 * The QR scanner is the iPhone's own (`iosQrScanner`: AVFoundation, no library) with its words from the Compose strings;
 * *Copy* puts a secret on the pasteboard local-only and expiring ([IosClipboardSeam]). Without a Google client in this
 * build the card says Drive is not available (the controller's `UNAVAILABLE`, as on Android without one).
 */
@Composable
internal fun IosDriveSettingsSection(drive: IosDriveServices) {
    var notice by remember { mutableStateOf(drive.lockNotice()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { notice = drive.lockNotice() }
    }
    HorizontalDivider()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (notice != LockNotice.NONE) IosLockNoticeBlock(notice)
        if (DriveLockRules.showsCard(notice)) IosDriveCard(drive)
    }
}

/** The words for a notice. */
internal fun LockNotice.iosMessage(): StringResource = when (this) {
    LockNotice.NEEDS_LOCK -> Res.string.lock_notice_ios_needs
    LockNotice.KEY_LOST -> Res.string.lock_notice_ios_key_lost
    LockNotice.PAUSED, LockNotice.NONE -> Res.string.lock_notice_ios_paused
}

@Composable
private fun IosLockNoticeBlock(notice: LockNotice) {
    if (notice == LockNotice.NEEDS_LOCK) {
        Text(
            stringResource(Res.string.lock_notice_ios_heading), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
    }
    Text(stringResource(notice.iosMessage()))
}

@Composable
private fun IosDriveCard(drive: IosDriveServices) {
    val vm: DriveViewModel = viewModel {
        DriveViewModel(
            ControllerDriveActions(drive.controller, folderGone = drive.graph.folderGone),
            codec = Dp1EnrolmentCodec(drive.graph.crypto),
            deviceName = { IosDriveServices.iosDeviceName() },
            platform = DevicePlatform.IOS,
        )
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<DriveReason?>(null) }
    val scope = rememberCoroutineScope()
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
        clipboard = remember { IosClipboardSeam() },
    )
    val cancel = stringResource(Res.string.common_cancel)
    val hint = stringResource(Res.string.qr_scan_hint)
    val notDoorprints = stringResource(Res.string.qr_scan_not_doorprints)
    val tooLong = stringResource(Res.string.qr_scan_too_long)
    val viewfinder = stringResource(Res.string.qr_scan_viewfinder)
    val scanner = remember(cancel, hint, notDoorprints, tooLong, viewfinder) {
        iosQrScanner(QrScannerLabels(cancel, hint, notDoorprints, tooLong, viewfinder))
    }
    DriveSettings(vm, scanner = scanner, host = host)
}
