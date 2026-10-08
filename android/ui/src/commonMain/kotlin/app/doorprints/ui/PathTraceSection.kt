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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.data.AppSettings
import app.doorprints.shared.trace.RepeatLook
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.common_cancel
import app.doorprints.ui.res.common_delete
import app.doorprints.ui.res.settings_path_trace
import app.doorprints.ui.res.settings_path_trace_clear
import app.doorprints.ui.res.settings_path_trace_cleared
import app.doorprints.ui.res.settings_path_trace_hint
import app.doorprints.ui.res.trace_alert_hint
import app.doorprints.ui.res.trace_alert_needs_trace
import app.doorprints.ui.res.trace_alert_permission_denied
import app.doorprints.ui.res.trace_alert_rationale
import app.doorprints.ui.res.trace_alert_title
import app.doorprints.ui.res.trace_repeat_look_clear
import app.doorprints.ui.res.trace_repeat_look_clear_desc
import app.doorprints.ui.res.trace_repeat_look_hint
import app.doorprints.ui.res.trace_repeat_look_off
import app.doorprints.ui.res.trace_repeat_look_off_desc
import app.doorprints.ui.res.trace_repeat_look_subtle
import app.doorprints.ui.res.trace_repeat_look_subtle_desc
import app.doorprints.ui.res.trace_repeat_look_title
import app.doorprints.ui.res.trace_settings_clear_hint
import app.doorprints.ui.res.trace_settings_delete_all
import app.doorprints.ui.res.trace_settings_delete_all_confirm
import app.doorprints.ui.res.trace_settings_saved
import app.doorprints.ui.res.trace_settings_transfer_note
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The path trace in Settings' Hunt mode section (docs/11 5.27, 5.27.1, S4b-FR-2, S4b-FR-15, S4b-FR-16): *Trace my path on
 * the map*, off by default, with the hint that says what is kept and where (this phone only; 30 days, or saved with a
 * house); *How repeated paths look* (Clear, Subtle, Off: a display choice, per device, applied at once, shown whether
 * or not the trace is on); *Warn me when I walk a path again* (off by default, only while the trace is on; turning it
 * on asks for the notification permission with the reason where the system asks, and stays off with a sentence when
 * that is refused); *Saved walks: n* with *Delete all saved walks*; and *Clear the path* (the 30-day trace only) while
 * there is one. Turning the trace switch off keeps everything, so a person who turns it off for a day does not lose
 * the week; the map draws the line only from what is kept. Nothing here is a location permission: the trace only
 * records while Hunt mode runs, which has its own.
 */
@Composable
fun PathTraceSection(settings: AppSettings) {
    val repo = LocalAppServices.current.repository
    val platform = LocalPlatformServices.current
    val features = LocalPlatformFeatures.current
    val scope = rememberCoroutineScope()
    val track by repo.trackPoints.collectAsStateWithLifecycle(initialValue = emptyList())
    val savedCount by repo.savedWalkCount.collectAsStateWithLifecycle(initialValue = 0)
    var cleared by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var notificationsRefused by remember { mutableStateOf(false) }
    val notifyAsk = rememberNotificationAsk(rationale = Res.string.trace_alert_rationale)

    SwitchRow(
        text = stringResource(Res.string.settings_path_trace),
        hint = stringResource(Res.string.settings_path_trace_hint),
        checked = settings.pathTrace,
        horizontalPadding = 0.dp,
        modifier = Modifier.tourTarget(TourTargets.SETTINGS_TRACE),
        onChange = { on -> scope.launch { repo.settings.savePathTrace(on) } },
    )

    // How repeated paths look: a radio group, each choice with its own line; shown, and enabled, whether or not the
    // trace is on (docs/11 5.27.4).
    Text(
        stringResource(Res.string.trace_repeat_look_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
    Text(stringResource(Res.string.trace_repeat_look_hint), style = MaterialTheme.typography.bodySmall)
    Column(Modifier.selectableGroup()) {
        for (look in RepeatLook.entries) {
            LookRow(
                selected = settings.repeatLook == look,
                title = stringResource(lookTitle(look)),
                description = stringResource(lookDescription(look)),
                onSelect = { scope.launch { repo.settings.saveRepeatLook(look) } },
            )
        }
    }

    SwitchRow(
        text = stringResource(Res.string.trace_alert_title),
        hint = stringResource(if (settings.pathTrace) Res.string.trace_alert_hint else Res.string.trace_alert_needs_trace),
        checked = settings.repeatAlert,
        enabled = settings.pathTrace,
        horizontalPadding = 0.dp,
        warning = if (notificationsRefused && !settings.repeatAlert) stringResource(Res.string.trace_alert_permission_denied) else null,
        onChange = { on ->
            if (!on) {
                notificationsRefused = false
                scope.launch { repo.settings.saveRepeatAlert(false) }
            } else {
                // The reason first, then the system's question where there is one; refused: the switch stays off.
                notifyAsk {
                    if (platform.canPostNotifications()) {
                        notificationsRefused = false
                        scope.launch { repo.settings.saveRepeatAlert(true) }
                    } else {
                        notificationsRefused = true
                    }
                }
            }
        },
    )

    Text(
        stringResource(Res.string.trace_settings_saved, savedCount),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (savedCount > 0) {
        OutlinedButton(onClick = { confirmDeleteAll = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(Res.string.trace_settings_delete_all))
        }
    }
    if (features.deviceTransferNote) {
        Text(stringResource(Res.string.trace_settings_transfer_note), style = MaterialTheme.typography.bodySmall)
    }
    if (track.isNotEmpty()) {
        OutlinedButton(
            onClick = { scope.launch { repo.clearTrack(); cleared = true } },
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(Res.string.settings_path_trace_clear)) }
        Text(stringResource(Res.string.trace_settings_clear_hint), style = MaterialTheme.typography.bodySmall)
    } else if (cleared) {
        // Confirmation in place of the button, announced once (WCAG 4.1.3); gone when the section is left.
        Text(
            stringResource(Res.string.settings_path_trace_cleared),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            text = { Text(stringResource(Res.string.trace_settings_delete_all_confirm, savedCount)) },
            confirmButton = {
                TextButton(
                    onClick = { confirmDeleteAll = false; scope.launch { repo.deleteAllSavedWalks() } },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { ButtonLabel(stringResource(Res.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    ButtonLabel(stringResource(Res.string.common_cancel))
                }
            },
        )
    }
}

/** The title of a repeated-path look in Settings. */
internal fun lookTitle(look: RepeatLook): StringResource = when (look) {
    RepeatLook.CLEAR -> Res.string.trace_repeat_look_clear
    RepeatLook.SUBTLE -> Res.string.trace_repeat_look_subtle
    RepeatLook.OFF -> Res.string.trace_repeat_look_off
}

/** The one-line description of a repeated-path look in Settings. */
internal fun lookDescription(look: RepeatLook): StringResource = when (look) {
    RepeatLook.CLEAR -> Res.string.trace_repeat_look_clear_desc
    RepeatLook.SUBTLE -> Res.string.trace_repeat_look_subtle_desc
    RepeatLook.OFF -> Res.string.trace_repeat_look_off_desc
}

/** One look: a radio row with a title and its one line, the whole row a 48 dp target. */
@Composable
private fun LookRow(selected: Boolean, title: String, description: String, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
