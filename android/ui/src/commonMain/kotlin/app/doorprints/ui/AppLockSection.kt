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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.doorprints.data.AppLockTimes
import app.doorprints.data.AppSettings
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.settings_app_lock
import app.doorprints.ui.res.settings_app_lock_15m
import app.doorprints.ui.res.settings_app_lock_1m
import app.doorprints.ui.res.settings_app_lock_5m
import app.doorprints.ui.res.settings_app_lock_after
import app.doorprints.ui.res.settings_app_lock_confirm
import app.doorprints.ui.res.settings_app_lock_hint
import app.doorprints.ui.res.settings_app_lock_no_screen_lock
import app.doorprints.ui.res.settings_app_lock_not_changed
import app.doorprints.ui.res.settings_app_lock_now
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The app lock in Settings (docs/11 5.19, S4b-FR-5): *Lock Doorprints*, and while it is on, how long the app may stay
 * in the background before it locks again. Turning it on or off asks for the phone's own screen lock first, so the
 * person knows it works and someone handed the unlocked phone cannot quietly turn it off. Without a screen lock on the
 * phone the switch is off and says to set one (read again on every return, after the phone's settings).
 */
@Composable
fun AppLockSection(settings: AppSettings, gate: AppLockGate = processAppLock, modifier: Modifier = Modifier) {
    val platform = LocalPlatformServices.current
    val repo = LocalAppServices.current.repository
    val scope = rememberCoroutineScope()
    var hasScreenLock by remember { mutableStateOf(platform.hasScreenLock()) }
    LifecycleResumeEffect(platform) {
        hasScreenLock = platform.hasScreenLock()
        onPauseOrDispose { }
    }
    // The value the prompt is confirming, and whether the last one was cancelled.
    var wanted by remember { mutableStateOf<Boolean?>(null) }
    var notChanged by remember { mutableStateOf(false) }
    // Through the gate, so the prompt's own screen (the keyguard's, on API 26-28) is not counted as leaving the app.
    val check = rememberDeviceCredentialCheck(stringResource(Res.string.settings_app_lock_confirm)) { result ->
        gate.checkEnded(result)
        val value = wanted
        wanted = null
        notChanged = result != CredentialCheck.PASSED
        if (result == CredentialCheck.PASSED && value != null) scope.launch { repo.settings.saveAppLock(value) }
        if (result == CredentialCheck.NO_SCREEN_LOCK) hasScreenLock = false
    }
    SwitchRow(
        text = stringResource(Res.string.settings_app_lock),
        hint = stringResource(Res.string.settings_app_lock_hint),
        checked = settings.appLock,
        // Turning it off stays possible without a screen lock (there is nothing to ask then; the lock screen turns it
        // off by itself in that case, see AppLockHost).
        enabled = wanted == null && (hasScreenLock || settings.appLock),
        horizontalPadding = 0.dp,
        modifier = modifier,
        warning = if (!hasScreenLock) stringResource(Res.string.settings_app_lock_no_screen_lock) else null,
        onChange = { on ->
            notChanged = false
            if (hasScreenLock) {
                if (gate.startCheck()) {
                    wanted = on
                    check()
                }
            } else if (!on) {
                scope.launch { repo.settings.saveAppLock(false) }
            }
        },
    )
    if (notChanged) {
        Text(
            stringResource(Res.string.settings_app_lock_not_changed),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (settings.appLock) {
        Text(stringResource(Res.string.settings_app_lock_after), style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.selectableGroup()) {
            AppLockTimes.CHOICES.forEach { seconds ->
                val selected = settings.appLockAfterSeconds == seconds
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = {
                            if (!selected) scope.launch { repo.settings.saveAppLockAfter(seconds) }
                        }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(stringResource(lockAfterLabel(seconds)), modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

/** The label of one of [AppLockTimes.CHOICES]. */
private fun lockAfterLabel(seconds: Int): StringResource = when (seconds) {
    0 -> Res.string.settings_app_lock_now
    60 -> Res.string.settings_app_lock_1m
    300 -> Res.string.settings_app_lock_5m
    else -> Res.string.settings_app_lock_15m
}
