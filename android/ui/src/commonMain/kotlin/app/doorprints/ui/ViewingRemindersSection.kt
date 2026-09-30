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

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.settings_viewings_exact_allow
import app.doorprints.ui.res.settings_viewings_exact_note
import app.doorprints.ui.res.settings_viewings_remind
import app.doorprints.ui.res.settings_viewings_remind_hint
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Settings > Viewings' reminders (docs/11 5.8, slice 3b-2): *Remind me about viewings*, this device's own setting (on
 * unless turned off; off cancels every reminder). While the phone does not let the app set on-time alarms (Android
 * 12+ without *Alarms & reminders*), the 5.16 note says reminders may come up to about 10 minutes early and *Allow
 * on-time reminders* opens that system page; both are read again on every resume and hide once it is allowed, and
 * only show while reminders are on (with them off the note would be about nothing).
 */
@Composable
fun ViewingRemindersSection() {
    val platform = LocalPlatformServices.current
    val settings = LocalAppServices.current.repository.settings
    val scope = rememberCoroutineScope()
    val on by remember(settings) { settings.viewingsRemind() }.collectAsStateWithLifecycle(initialValue = true)
    var exact by remember { mutableStateOf(platform.canScheduleExactAlarms()) }
    LifecycleResumeEffect(platform) {
        exact = platform.canScheduleExactAlarms()
        onPauseOrDispose { }
    }
    SwitchRow(
        text = stringResource(Res.string.settings_viewings_remind),
        hint = stringResource(Res.string.settings_viewings_remind_hint),
        checked = on,
        horizontalPadding = 0.dp,
        onChange = { value -> scope.launch { settings.setViewingsRemind(value) } },
    )
    if (on && !exact) {
        Text(stringResource(Res.string.settings_viewings_exact_note), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { platform.openExactAlarmSettings() }, modifier = Modifier.heightIn(min = 48.dp)) {
            ButtonLabel(stringResource(Res.string.settings_viewings_exact_allow))
        }
    }
}
