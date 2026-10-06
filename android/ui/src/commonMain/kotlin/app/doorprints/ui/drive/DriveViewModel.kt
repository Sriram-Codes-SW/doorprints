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

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.doorprints.crypto.DevicePlatform
import app.doorprints.ui.nowMillis

/**
 * The Google Drive screens' view model: [DriveHolder] on the view model's scope, so the section keeps its state (the
 * recovery key on screen, a delete half way) over a rotation. In memory only: nothing here is saved, so a key that
 * is on screen is gone when the process dies. The system prompt lines come from the screen ([DriveSettings] sets
 * [prompts] from the string resources), because a view model cannot read them.
 */
class DriveViewModel(
    actions: DriveActions,
    codec: EnrolmentCodec? = null,
    deviceName: () -> String = { "Android phone" },
    platform: DevicePlatform = DevicePlatform.ANDROID,
) : ViewModel() {
    var prompts: DrivePrompts = DrivePrompts("", "", "", "")

    val holder = DriveHolder(actions, viewModelScope, { prompts }, codec, deviceName, platform, ::nowMillis)
}

/** [DriveSettingsSection] over a [DriveViewModel]. */
@Composable
fun DriveSettings(viewModel: DriveViewModel, scanner: QrScanner = NoQrScanner, host: DriveHost = DriveHost(), modifier: Modifier = Modifier) {
    viewModel.prompts = DrivePrompts(
        delete = t("driveDelete.deviceCheckPrompt"),
        revoke = t("driveDevices.promptRevoke"),
        approve = t("driveDevices.promptApprove"),
        disconnectAll = t("driveDevices.promptDisconnectAll"),
    )
    DriveSettingsSection(viewModel.holder, scanner, host, modifier)
}
