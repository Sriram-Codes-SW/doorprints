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
